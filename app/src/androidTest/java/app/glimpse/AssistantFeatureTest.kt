package app.glimpse

import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.glimpse.assistant.AssistantActivity
import app.glimpse.assistant.CapturePermissionActivity
import app.glimpse.core.*
import app.glimpse.data.ImageSession
import app.glimpse.data.Images
import app.glimpse.music.*
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantFeatureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun overlayHasNoBrandingAndKeepsScreenActionsAndMusicInputsAvailable() {
        ImageSession.offer(Images.sample(), "Screen fixture", assistantOnly = true)
        ActivityScenario.launch<AssistantActivity>(Intent(context, AssistantActivity::class.java))
            .use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("Close assistant")), 15000))
                assertFalse(device.hasObject(By.text("Glimpse").pkg(context.packageName)))
                assertTrue(device.hasObject(By.desc("Translate screen")))
                assertTrue(device.hasObject(By.desc("Open camera")))
                assertTrue(device.hasObject(By.desc("Recognize music")))
                await {
                    var ready = false
                    scenario.onActivity {
                        ready =
                            ViewModelProvider(it)[GlimpseViewModel::class.java]
                                .state
                                .value
                                .recognition != null
                    }
                    ready
                }
                device.takeScreenshot(File(context.cacheDir, "assistant-overlay.png"))
                device.findObject(By.desc("Recognize music")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Identify a song")), 5000))
                assertTrue(device.hasObject(By.text("Both")))
                assertTrue(device.hasObject(By.text("Phone")))
                assertTrue(device.hasObject(By.text("Mic")))
                device.findObject(By.desc("Close music")).click()
                device.findObject(By.desc("Translate screen")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Live screen translate")), 5000))
                device.findObject(By.desc("Close assistant")).click()
                await { ImageSession.image.value == null }
            }
    }

    @Test
    fun assistantScreenshotsDoNotReplaceTheAppsExistingWorkspace() {
        ImageSession.clear()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { main
            ->
            main.onActivity {
                ViewModelProvider(it)[GlimpseViewModel::class.java].receiveText(
                    "Keep this workspace",
                )
            }
            ImageSession.offer(Images.sample(), "Private assistant fixture", assistantOnly = true)
            ActivityScenario.launch<AssistantActivity>(
                    Intent(context, AssistantActivity::class.java),
                )
                .use { assistant ->
                    await {
                        var recognized = false
                        assistant.onActivity {
                            recognized =
                                ViewModelProvider(it)[GlimpseViewModel::class.java]
                                    .state
                                    .value
                                    .recognition != null
                        }
                        recognized
                    }
                    main.onActivity {
                        val state = ViewModelProvider(it)[GlimpseViewModel::class.java].state.value
                        assertEquals("Keep this workspace", state.text)
                        assertNull(state.image)
                    }
                }
            await { ImageSession.image.value == null }
        }
    }

    @Test
    fun delayedRecognitionErrorCannotRepopulateAResetLiveSession() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ocr =
            object : OcrEngine {
                override val info = EngineInfo("fake", "Fake", "", true)
                override val scripts = OcrScript.entries.toSet()

                override suspend fun recognize(image: Bitmap, script: OcrScript): Recognition {
                    started.complete(Unit)
                    release.await()
                    throw ModelRequiredException(setOf("ja"))
                }

                override fun close() {}
            }
        val engines = (context.applicationContext as GlimpseApplication).graph.engines
        val controller = LiveTranslationController(EngineRegistry(listOf(ocr), engines.translation))
        val job = launch {
            controller.analyze(
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                LiveTranslationOptions("fake", "mlkit-translation"),
            )
        }
        started.await()
        controller.reset()
        release.complete(Unit)
        job.join()
        assertEquals(LiveState(), controller.state.value)
    }

    @Test
    fun apiCredentialsAreEncryptedAndCanBeRemoved() {
        val credentials = MusicCredentials(context)
        val old = credentials.read()
        try {
            credentials.write("instrumentation-test-token")
            assertEquals("instrumentation-test-token", credentials.read())
            val stored =
                context.getSharedPreferences("music_credentials", 0).getString("audd", "")!!
            assertFalse(stored.contains("instrumentation-test-token"))
            credentials.write("")
            assertEquals("", credentials.read())
        } finally {
            credentials.write(old)
        }
    }

    @Test
    fun unsupportedAndPathEscapingSongPacksAreRejected() {
        for (json in
            listOf(
                """{"format":"unknown","id":"pack","name":"Pack","version":1,"songs":[]}""",
                """{"format":"glimpse-landmarks-v1","id":"../escape","name":"Pack","version":1,"songs":[]}""",
            )) assertTrue(runCatching { SongPackRepository.decode(json) }.isFailure)
    }

    @Test
    fun microphoneCaptureStartsAndStopsWithoutGoogleServicesOrAnOnlineKey() = runBlocking {
        val preferences = (context.applicationContext as GlimpseApplication).graph.preferences
        val previous = preferences.values.first().musicMode
        shell("pm grant app.glimpse android.permission.RECORD_AUDIO")
        preferences.set("musicMode", "offline_only")
        try {
            instrumentation.runOnMainSync {
                context.startActivity(
                    Intent(context, CapturePermissionActivity::class.java)
                        .setAction(CapturePermissionActivity.MUSIC)
                        .putExtra("input", "microphone")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            await {
                MusicSession.state.value.active &&
                    MusicSession.state.value.status[AudioSource.MICROPHONE] == "Listening…"
            }
            context.stopService(Intent(context, MusicCaptureService::class.java))
            await { !MusicSession.state.value.active }
            assertEquals("Stopped", MusicSession.state.value.status[AudioSource.MICROPHONE])
        } finally {
            context.stopService(Intent(context, MusicCaptureService::class.java))
            preferences.set("musicMode", previous)
        }
    }

    @Test
    fun simultaneousInputsRecognizeInternalPlaybackOfflineAndReportItsMediaSession() = runBlocking {
        val preferences = (context.applicationContext as GlimpseApplication).graph.preferences
        val previous = preferences.values.first().musicMode
        preferences.set("musicMode", "offline_only")
        shell("pm grant app.glimpse android.permission.RECORD_AUDIO")
        shell("appops set app.glimpse PROJECT_MEDIA allow")
        shell("cmd notification allow_listener app.glimpse/app.glimpse.music.PlayingAppListener")
        val notes =
            Random(923).let { random ->
                DoubleArray(60) { 180.0 * 2.0.pow(random.nextInt(24) / 12.0) }
            }
        val samples =
            ShortArray(18 * 8000) { position ->
                val time = position / 8000.0
                val frequency = notes[(time * 3).toInt()]
                ((sin(2 * PI * frequency * time) * .4 +
                        sin(2 * PI * frequency * 1.5 * time) * .18) * 32767)
                    .toInt()
                    .toShort()
            }
        val pack =
            JSONObject()
                .put("format", "glimpse-landmarks-v1")
                .put("id", "instrumentation-audio")
                .put("name", "Audio fixture")
                .put("version", 1)
                .put(
                    "songs",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "fixture-song")
                                .put("title", "Fixture melody")
                                .put("artist", "Test artist")
                                .put(
                                    "fingerprints",
                                    JSONArray().apply {
                                        MusicFingerprints.extract(samples).forEach {
                                            put(JSONArray().put(it.hash).put(it.frame))
                                        }
                                    },
                                ),
                        ),
                )
        val directory = File(context.filesDir, "song-packs").apply { mkdirs() }
        val file =
            File(directory, "instrumentation-audio.json").apply { writeText(pack.toString()) }
        val player =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(8000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        val session =
            MediaSession(context, "music-fixture").apply {
                setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture melody")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test artist")
                        .build(),
                )
                setPlaybackState(
                    PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build(),
                )
                isActive = true
            }
        try {
            player.write(samples, 0, samples.size)
            player.setLoopPoints(0, samples.size, -1)
            player.play()
            instrumentation.runOnMainSync {
                context.startActivity(
                    Intent(context, CapturePermissionActivity::class.java)
                        .setAction(CapturePermissionActivity.MUSIC)
                        .putExtra("input", "both")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            await { MusicSession.state.value.matches.any { it.song.id == "fixture-song" } }
            val match = MusicSession.state.value.matches.first { it.song.id == "fixture-song" }
            assertEquals(setOf(AudioSource.PHONE), match.evidence.map { it.source }.toSet())
            assertTrue(match.evidence.all { it.backend == "Offline · Audio fixture" })
            assertEquals("Glimpse", match.evidence.first().playingApp)
            assertTrue(MusicSession.state.value.status.containsKey(AudioSource.MICROPHONE))
        } finally {
            context.stopService(Intent(context, MusicCaptureService::class.java))
            await { !MusicSession.state.value.active }
            player.stop()
            player.release()
            session.release()
            file.delete()
            preferences.set("musicMode", previous)
            shell("appops set app.glimpse PROJECT_MEDIA default")
            shell(
                "cmd notification disallow_listener app.glimpse/app.glimpse.music.PlayingAppListener",
            )
        }
    }

    @Test
    fun liveScreenCaptureHasWorkingPauseAndStopControls() {
        shell("appops set app.glimpse SYSTEM_ALERT_WINDOW allow")
        shell("appops set app.glimpse PROJECT_MEDIA allow")
        try {
            instrumentation.runOnMainSync {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                context.startActivity(
                    Intent(context, CapturePermissionActivity::class.java)
                        .setAction(CapturePermissionActivity.LIVE_TRANSLATE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            assertTrue(device.wait(Until.hasObject(By.text("Choose a single app")), 10000))
            device.findObject(By.res("android:id/button1")).click()
            val controlsVisible = device.wait(Until.hasObject(button("Stop")), 15000)
            device.takeScreenshot(File(context.cacheDir, "live-screen.png"))
            device.dumpWindowHierarchy(File(context.cacheDir, "live-screen.xml"))
            assertTrue(controlsVisible)
            // The preapproved full-display grant exercises the feedback guard as well as lifecycle.
            assertTrue(
                device.wait(
                    Until.hasObject(By.textContains("Entire-screen sharing detected")),
                    15000,
                ),
            )
            device.findObject(button("Pause")).click()
            assertTrue(device.wait(Until.hasObject(button("Resume")), 5000))
            device.findObject(button("Stop")).click()
            assertTrue(device.wait(Until.gone(button("Stop")), 5000))
        } finally {
            context.stopService(
                Intent(context, app.glimpse.assistant.ScreenTranslationService::class.java),
            )
            shell("appops set app.glimpse PROJECT_MEDIA default")
            shell("appops set app.glimpse SYSTEM_ALERT_WINDOW default")
        }
    }

    @Test
    fun singleAppLiveCaptureTranslatesWhileTheUnderlyingAppRemainsInteractive() = runBlocking {
        ImageSession.clear()
        val preferences = (context.applicationContext as GlimpseApplication).graph.preferences
        val previous = preferences.values.first()
        preferences.set("source", "en")
        preferences.set("target", "en")
        shell("appops set app.glimpse SYSTEM_ALERT_WINDOW allow")
        shell("appops set app.glimpse PROJECT_MEDIA default")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                assertTrue(device.wait(Until.hasObject(By.text("Try an example")), 10000))
                instrumentation.runOnMainSync {
                    context.startActivity(
                        Intent(context, CapturePermissionActivity::class.java)
                            .setAction(CapturePermissionActivity.LIVE_TRANSLATE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                assertTrue(device.wait(Until.hasObject(By.text("Choose a single app")), 10000))
                device.findObject(By.res("android:id/button1")).click()
                assertTrue(
                    device.wait(
                        Until.hasObject(By.res("android:id/button1").pkg("com.android.systemui")),
                        10000,
                    ),
                )
                device.dumpWindowHierarchy(File(context.cacheDir, "projection-dialog.xml"))
                device.findObject(By.res("android:id/button1").pkg("com.android.systemui")).click()
                val task =
                    device.wait(
                        Until.findObject(By.text("Glimpse").pkg("com.android.systemui")),
                        10000,
                    )
                if (task == null)
                    device.dumpWindowHierarchy(File(context.cacheDir, "projection-chooser.xml"))
                assertNotNull("Select Glimpse's existing task in Android's app chooser", task)
                task!!.click()
                assertTrue(device.wait(Until.hasObject(button("Stop")), 15000))
                assertTrue(
                    device.wait(Until.hasObject(By.textContains("On-device translation")), 15000),
                )
                device.findObject(By.text("Try an example")).click()
                assertTrue(
                    device.wait(Until.hasObject(By.text("Circle an area or tap text")), 15000),
                )
                device.setOrientationLeft()
                assertTrue(device.wait(Until.hasObject(button("Stop")), 10000))
                device.takeScreenshot(File(context.cacheDir, "live-single-app.png"))
                device.findObject(button("Stop")).click()
                assertTrue(device.wait(Until.gone(button("Stop")), 5000))
            }
        } finally {
            context.stopService(
                Intent(context, app.glimpse.assistant.ScreenTranslationService::class.java),
            )
            device.setOrientationNatural()
            device.unfreezeRotation()
            preferences.set("source", previous.source)
            preferences.set("target", previous.target)
            shell("appops set app.glimpse SYSTEM_ALERT_WINDOW default")
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (!condition()) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline)
                fail("Timed out waiting for assistant state: ${MusicSession.state.value}")
            android.os.SystemClock.sleep(100)
        }
    }

    private fun button(label: String) =
        By.text(java.util.regex.Pattern.compile(label, java.util.regex.Pattern.CASE_INSENSITIVE))
            .pkg(context.packageName)

    private fun shell(command: String) =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
}
