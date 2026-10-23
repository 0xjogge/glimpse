package app.glimpse.music

import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import app.glimpse.GlimpseApplication
import app.glimpse.assistant.captureNotification
import app.glimpse.core.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MusicState(
    val active: Boolean = false,
    val status: Map<AudioSource, String> = emptyMap(),
    val levels: Map<AudioSource, Float> = emptyMap(),
    val matches: List<SongMatch> = emptyList(),
    val error: String? = null,
)

object MusicSession {
    private val generation = AtomicLong()
    private val mutable = MutableStateFlow(MusicState())
    val state = mutable.asStateFlow()

    fun begin(): Long {
        val session = generation.incrementAndGet()
        mutable.value = MusicState(active = true)
        return session
    }

    fun error(message: String) {
        mutable.update { it.copy(active = false, error = message) }
    }

    fun status(source: AudioSource, status: String) {
        mutable.update { it.copy(status = it.status + (source to status)) }
    }

    fun level(source: AudioSource, level: Float) {
        mutable.update { it.copy(levels = it.levels + (source to level)) }
    }

    fun match(match: SongMatch) {
        mutable.update { it.copy(matches = mergeMusicMatches(it.matches + match)) }
    }

    fun finish(session: Long) {
        if (session != generation.get()) return
        mutable.update {
            it.copy(
                active = false,
                levels = emptyMap(),
                status =
                    it.status.mapValues { (_, value) ->
                        if (value == "Listening…" || value == "Matching…") "Stopped" else value
                    },
            )
        }
    }
}

class PlayingAppListener : NotificationListenerService()

class MusicCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorders = Collections.synchronizedList(mutableListOf<AudioRecord>())
    private var projection: MediaProjection? = null
    private var job: Job? = null
    private var sessionId = 0L

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val input = intent.getStringExtra("input") ?: "both"
        val sources =
            when (input) {
                "microphone" -> listOf(AudioSource.MICROPHONE)
                "phone" -> listOf(AudioSource.PHONE)
                else -> AudioSource.entries
            }
        try {
            val types =
                (if (AudioSource.MICROPHONE in sources)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else 0) or
                    (if (AudioSource.PHONE in sources)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    else 0)
            startForeground(42, captureNotification("Recognizing music"), types)
            if (AudioSource.PHONE in sources) {
                projection =
                    getSystemService(MediaProjectionManager::class.java)
                        .getMediaProjection(
                            intent.getIntExtra("result_code", 0),
                            requireNotNull(
                                intent.getParcelableExtra("result_data", Intent::class.java),
                            ),
                        )
                projection!!.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopSelf()
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
            sessionId = MusicSession.begin()
            job = scope.launch {
                try {
                    withTimeout(25_000) {
                        val prefs =
                            (application as GlimpseApplication).graph.preferences.values.first()
                        val packs =
                            withContext(Dispatchers.IO) {
                                SongPackRepository(this@MusicCaptureService).installed()
                            }
                        val key = MusicCredentials(this@MusicCaptureService).read()
                        val engines =
                            withContext(Dispatchers.Default) {
                                buildList {
                                    if (prefs.musicMode != "online_only")
                                        add(OfflinePackRecognition(packs))
                                    if (prefs.musicMode != "offline_only" && key.isNotBlank())
                                        add(AudDRecognition(key))
                                }
                            }
                        sources
                            .map { source ->
                                launch(Dispatchers.IO) {
                                    try {
                                        recognizeSource(
                                            source,
                                            prefs.musicMode,
                                            MusicRecognizer(engines),
                                            key,
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        currentCoroutineContext().ensureActive()
                                        MusicSession.status(
                                            source,
                                            e.localizedMessage ?: "Audio input unavailable",
                                        )
                                    }
                                }
                            }
                            .joinAll()
                    }
                } catch (_: TimeoutCancellationException) {
                    sources
                        .filter { source ->
                            MusicSession.state.value.matches.none { match ->
                                match.evidence.any { it.source == source }
                            }
                        }
                        .forEach { MusicSession.status(it, "Listening timed out") }
                } finally {
                    MusicSession.finish(sessionId)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            MusicSession.error(e.localizedMessage ?: "Could not start audio capture")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun recognizeSource(
        source: AudioSource,
        mode: String,
        recognizer: MusicRecognizer,
        key: String,
    ) {
        val format =
            AudioFormat.Builder()
                .setSampleRate(8000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        val minimum =
            AudioRecord.getMinBufferSize(
                8000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        require(minimum > 0) { "8 kHz audio capture is unavailable" }
        val builder =
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum * 2, 16000))
        if (source == AudioSource.PHONE)
            builder.setAudioPlaybackCaptureConfig(
                AudioPlaybackCaptureConfiguration.Builder(requireNotNull(projection))
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build(),
            )
        else builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        val recorder = builder.build()
        recorders.add(recorder)
        try {
            require(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Audio input could not initialize"
            }
            recorder.startRecording()
            val samples = ShortArray(12 * 8000)
            var size = 0
            for (seconds in intArrayOf(6, 12)) {
                MusicSession.status(source, "Listening…")
                while (size < seconds * 8000) {
                    currentCoroutineContext().ensureActive()
                    val count =
                        recorder.read(
                            samples,
                            size,
                            minOf(800, seconds * 8000 - size),
                            AudioRecord.READ_BLOCKING,
                        )
                    currentCoroutineContext().ensureActive()
                    require(count > 0) { "Audio capture stopped or became unavailable" }
                    var power = 0.0
                    for (i in size until size + count) power += samples[i].toDouble() * samples[i]
                    MusicSession.level(
                        source,
                        (kotlin.math.sqrt(power / count) / 8000).toFloat().coerceIn(0f, 1f),
                    )
                    size += count
                }
                val clip = samples.copyOf(size)
                val silent = clip.count { kotlin.math.abs(it.toInt()) > 80 } < clip.size / 100
                if (silent) {
                    MusicSession.status(
                        source,
                        if (source == AudioSource.PHONE)
                            "No capturable phone audio. The player may disable capture, or playback may be silent."
                        else "Microphone is quiet or unavailable",
                    )
                    continue
                }
                MusicSession.status(source, "Matching…")
                val recognition = recognizer.recognize(clip)
                currentCoroutineContext().ensureActive()
                if (recognition != null) {
                    val song = recognition.song
                    MusicSession.match(
                        SongMatch(
                            song,
                            setOf(
                                MusicEvidence(
                                    source,
                                    recognition.backend,
                                    if (source == AudioSource.PHONE) playingApp(song) else null,
                                ),
                            ),
                        ),
                    )
                    MusicSession.status(source, "Matched")
                    return
                }
                MusicSession.status(
                    source,
                    when {
                        mode == "offline_only" -> "No match in installed offline packs"
                        key.isBlank() ->
                            "No offline match. Add an AudD API key for online recognition."
                        else -> "No song matched"
                    },
                )
            }
        } finally {
            synchronized(recorders) {
                recorders.remove(recorder)
                runCatching { recorder.stop() }
                recorder.release()
            }
        }
    }

    private fun playingApp(song: Song): String? = runCatching {
        val sessions =
            getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(this, PlayingAppListener::class.java))
                .filter {
                    it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                        it.playbackInfo?.playbackType ==
                            android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
                }
        val matching = sessions.filter { controller ->
            val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            title.isNotBlank() && title.equals(song.title, true)
        }
        matching.singleOrNull()?.packageName?.let { packageName ->
            runCatching {
                    packageManager
                        .getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
                        .toString()
                }
                .getOrDefault(packageName)
        }
    }
        .getOrNull()

    override fun onDestroy() {
        scope.cancel()
        synchronized(recorders) { recorders.forEach { runCatching { it.stop() } } }
        projection?.stop()
        MusicSession.finish(sessionId)
        super.onDestroy()
    }
}
