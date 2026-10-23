package app.glimpse

import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.glimpse.core.*
import app.glimpse.data.ImageSession
import app.glimpse.data.Images
import app.glimpse.search.VisualSearchActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlimpseDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model
        get() = ViewModelProvider(compose.activity)[GlimpseViewModel::class.java]

    @Before
    fun reset() {
        compose.runOnIdle {
            model.home()
            model.message(null)
        }
    }

    @Test
    fun bundledOcrReadsJapaneseAndEnglishWithoutGooglePlayServices() = runBlocking {
        val context = compose.activity
        val playInstalled = runCatching {
            context.packageManager.getPackageInfo("com.google.android.gms", 0)
        }
            .isSuccess
        assertFalse("Run this suite on the Google-free AOSP emulator", playInstalled)
        val result = model.graph.engines.ocr("mlkit-ocr").recognize(Images.sample())
        assertTrue(result.text, result.text.contains("こんにちは"))
        assertTrue(result.text, result.text.contains("Hello", true))
        assertTrue(result.lines.all { it.bounds.left >= 0 && it.bounds.right <= 1 })
    }

    @Test
    fun userCanOpenExampleTapJapaneseCopyAndReturnHome() {
        compose.onNodeWithText("Try an example").performClick()
        compose.waitUntil(60_000) {
            model.state.value.recognition?.lines?.any { it.text.contains("こんにちは") } == true
        }
        val state = model.state.value
        val line = state.recognition!!.lines.first { it.text.contains("こんにちは") }
        val node = compose.onNodeWithContentDescription("Image selection.", substring = true)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val p =
            ImagePlacement.fit(
                state.image!!.bitmap.width,
                state.image.bitmap.height,
                bounds.width,
                bounds.height,
            )
        node.performTouchInput {
            click(
                Offset(
                    p.left + (line.bounds.left + line.bounds.right) / 2 * p.width,
                    p.top + (line.bounds.top + line.bounds.bottom) / 2 * p.height,
                ),
            )
        }
        compose.waitUntil(5000) { model.state.value.text == line.text }
        compose.onNodeWithContentDescription("Copy", useUnmergedTree = true).performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.READ_CLIPBOARD_IN_BACKGROUND")
        try {
            compose.runOnIdle {
                assertEquals(
                    line.text,
                    compose.activity
                        .getSystemService(ClipboardManager::class.java)
                        .primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
        compose.onNodeWithContentDescription("Open camera").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("A closer look.").assertIsDisplayed()
    }

    @Test
    fun importedImageAndProcessTextUseTheSameWorkspace() {
        compose.runOnIdle { model.receiveText("日本へようこそ") }
        compose.onNodeWithText("日本へようこそ").assertIsDisplayed()
        compose.onNodeWithContentDescription("Translate").assertIsEnabled()
        compose.onNodeWithContentDescription("Search image").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open camera").assertIsDisplayed()
    }

    @Test
    fun themeSettingsAndModelsAreReachableAndScreenshotsAreCaptured() {
        val context = compose.activity
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { model.set("theme", theme) }
            compose.waitUntil(5000) { model.preferences.value.theme == theme }
            compose.waitForIdle()
            // System-bar icon colors animate outside Compose's idling resources.
            android.os.SystemClock.sleep(500)
            val device =
                androidx.test.uiautomator.UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation(),
                )
            device.takeScreenshot(File(context.cacheDir, "glimpse-$theme.png"))
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Offline languages").performScrollTo().performClick()
        compose.onNodeWithText("Japanese", substring = false).assertExists()
        compose.runOnIdle { model.set("theme", "system") }
    }

    @Test
    fun cameraCanBeOpenedFromTheAssistantWorkspace() {
        compose.runOnIdle { model.receiveText("Hello") }
        shell("pm grant app.glimpse android.permission.CAMERA")
        compose.onNodeWithContentDescription("Open camera").performClick()
        compose.onNodeWithText("Live translate").assertIsDisplayed()
        compose.onNodeWithContentDescription("Take picture").assertExists()
        compose.onNodeWithContentDescription("Offline languages").assertExists()
        compose.waitUntil(20_000) {
            compose
                .onAllNodes(hasContentDescription("Take picture") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription("Take picture").performClick()
        compose.waitUntil(20_000) { model.state.value.image?.origin == "Camera photo" }
        compose.onNodeWithContentDescription("Search image").assertIsEnabled()
    }

    @Test
    fun defaultAssistantCapturesTheUnderlyingAppAndKeepsCameraAvailable() {
        val role = "android.app.role.ASSISTANT"
        val holders =
            shell("cmd role get-role-holders --user 0 $role").trim().lines().filter {
                it.isNotBlank()
            }
        try {
            shell("cmd role add-role-holder --user 0 $role app.glimpse")
            shell("settings put secure assist_screenshot_enabled 1")
            shell("settings put secure assist_structure_enabled 1")
            compose.runOnUiThread {
                compose.activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
            val device =
                androidx.test.uiautomator.UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation(),
                )
            device.waitForIdle()
            device.pressKeyCode(android.view.KeyEvent.KEYCODE_ASSIST)
            compose.waitUntil(20_000) { ImageSession.image.value?.origin == "Your screen" }
            assertTrue(ImageSession.image.value!!.bitmap.width > 0)
            assertTrue(
                device.wait(
                    androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.desc("Open camera"),
                    ),
                    10_000,
                ),
            )
            // A delayed timeout must not replace the successful screenshot with the home screen.
            android.os.SystemClock.sleep(1800)
            assertEquals("Your screen", ImageSession.image.value?.origin)
        } finally {
            shell("cmd role remove-role-holder --user 0 $role app.glimpse")
            holders
                .filter { it != "app.glimpse" }
                .forEach { shell("cmd role add-role-holder --user 0 $role $it") }
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }

    @Test
    fun visualUploadSuppliesOnlyTheSelectedCropToThePage() = runBlocking {
        val crop =
            Images.shareCrop(compose.activity, Images.sample(), ImageRegion(.25f, .2f, .75f, .6f))
        val intent =
            Intent(compose.activity, VisualSearchActivity::class.java)
                .putExtra("url", "https://www.google.com/imghp")
                .putExtra("provider", "Upload fixture")
                .putExtra("crop", crop.toString())
        ActivityScenario.launch<VisualSearchActivity>(intent).use { scenario ->
            fun findBrowser(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup)
                    for (index in 0 until view.childCount) findBrowser(view.getChildAt(index))
                        ?.let {
                            return it
                        }
                return null
            }
            var browser: WebView? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (browser == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { browser = findBrowser(it.window.decorView) }
                android.os.SystemClock.sleep(100)
            }
            assertNotNull(browser)
            scenario.onActivity {
                browser!!.stopLoading()
                browser!!.loadDataWithBaseURL(
                    "https://fixture.invalid/",
                    """
                    <html><meta name="viewport" content="width=device-width,initial-scale=1"><body>
                    <button style="position:absolute;top:120px;left:20px;width:280px;height:100px;font-size:24px"
                      onclick="this.textContent='Opening image';document.getElementById('file').click()">Upload fixture image</button>
                    <input id="file" type="file" accept="image/*" style="display:none" onchange="
                      document.getElementById('status').textContent='File received';
                      const img = new Image(); img.onload = () => { document.body.textContent = 'Crop ' + img.width + 'x' + img.height; };
                      img.onerror = () => { document.body.textContent = 'Image decode failed'; };
                      img.src = window.URL.createObjectURL(this.files[0]);">
                    <p id="status"></p>
                    </body></html>
                    """
                        .trimIndent(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            val device =
                androidx.test.uiautomator.UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation(),
                )
            assertTrue(
                device.wait(
                    androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.text("Upload fixture image"),
                    ),
                    10_000,
                ),
            )
            device.waitForIdle()
            val focused = java.util.concurrent.atomic.AtomicBoolean(false)
            val focusDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (!focused.get() && android.os.SystemClock.elapsedRealtime() < focusDeadline) {
                scenario.onActivity { focused.set(it.hasWindowFocus()) }
                android.os.SystemClock.sleep(100)
            }
            assertTrue("Visual-search window must own input focus", focused.get())
            device.findObject(androidx.test.uiautomator.By.text("Upload fixture image")).click()
            val uploaded =
                device.wait(
                    androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.text("Crop 540x560"),
                    ),
                    20_000,
                )
            if (!uploaded) {
                val done = CountDownLatch(1)
                var details = "No DOM result"
                scenario.onActivity {
                    browser!!.evaluateJavascript("document.body.innerText") {
                        details = it
                        done.countDown()
                    }
                }
                done.await(5, TimeUnit.SECONDS)
                device.takeScreenshot(File(compose.activity.cacheDir, "upload-failure.png"))
                fail("Upload fixture did not decode the selected crop: $details")
            }
        }
    }

    @Test
    fun downloadedJapaneseTranslatesStaticAndLiveFramesOffline() = runBlocking {
        Assume.assumeTrue(
            "Opt in with --download-models for the network-assisted model setup",
            InstrumentationRegistry.getArguments().getString("downloadModels") == "true",
        )
        val engine = model.graph.engines.translator("mlkit-translation")
        engine.download("ja", false)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            automation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        shell("svc wifi disable")
        shell("svc data disable")
        try {
            val translated = engine.translate("こんにちは", "ja", "en")
            assertTrue(translated.text, translated.text.isNotBlank() && translated.text != "こんにちは")
            val reverse = engine.translate("Welcome to Japan", "en", "ja")
            assertTrue(reverse.text, reverse.text.any { it in '\u3040'..'\u9fff' })
            model.resetLive()
            val frame = Images.sample()
            model.analyzeFrame(frame)
            model.analyzeFrame(frame)
            assertTrue(model.live.value.toString(), model.live.value.translations.isNotEmpty())
        } finally {
            shell("svc wifi enable")
            shell("svc data enable")
        }
    }
}
