package app.glimpse

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.glimpse.assistant.AssistantAction
import app.glimpse.assistant.AssistantActionStrip
import app.glimpse.assistant.AssistantActivity
import app.glimpse.assistant.CapturePermissionActivity
import app.glimpse.data.AppPreferences
import app.glimpse.data.ImageSession
import app.glimpse.data.Images
import app.glimpse.ui.GlimpseTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect real controls and captured glyphs, rather than reimplementing the palette resolver. */
@RunWith(AndroidJUnit4::class)
class ThemeContrastTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = (context.applicationContext as GlimpseApplication).graph
    private val device = UiDevice.getInstance(instrumentation)
    private val uiMode = context.getSystemService(UiModeManager::class.java)
    private lateinit var previous: AppPreferences
    private var previousNightMode = UiModeManager.MODE_NIGHT_AUTO
    private val previousImage = ImageSession.image.value

    @Before
    fun saveConfiguration() {
        previous = runBlocking { graph.preferences.values.first() }
        previousNightMode = uiMode.nightMode
    }

    @After
    fun restoreConfiguration() {
        runBlocking {
            graph.preferences.set("theme", previous.theme)
            graph.preferences.set("dynamicColor", previous.dynamicColor)
        }
        setNightMode(previousNightMode)
        ImageSession.image.value = previousImage
    }

    @Test
    fun assistantStripAndRenderedPanelsHonorSavedThemeWithOppositeOsTheme() {
        for (dark in listOf(false, true)) {
            oppositeSystemTheme(dark)
            for (dynamic in listOf(false, true)) {
                saveTheme(dark, dynamic)
                ImageSession.offer(Images.sample(), "Theme contrast source", assistantOnly = true)
                ActivityScenario.launch<AssistantActivity>(
                        Intent(context, AssistantActivity::class.java)
                            .putExtra("capture_error", "Theme contrast result"),
                    )
                    .use { scenario ->
                        compose.onNodeWithText("Theme contrast result").assertExists()
                        var stripSurface = Color.TRANSPARENT
                        scenario.onActivity { activity ->
                            val strip =
                                descendants(activity.window.decorView)
                                    .filterIsInstance<AssistantActionStrip>()
                                    .single()
                            stripSurface = backgroundAt(strip)
                            assertBrightness("Strip", stripSurface, dark)
                            // The capture bridge constructs controls before any Compose parent
                            // exists.
                            val nativeStrip =
                                AssistantActionStrip(activity).apply {
                                    measure(
                                        View.MeasureSpec.makeMeasureSpec(
                                            strip.width,
                                            View.MeasureSpec.EXACTLY,
                                        ),
                                        View.MeasureSpec.makeMeasureSpec(
                                            0,
                                            View.MeasureSpec.UNSPECIFIED,
                                        ),
                                    )
                                    layout(0, 0, measuredWidth, measuredHeight)
                                }
                            assertEquals(
                                "Immediate native controls must use the cached app theme",
                                stripSurface,
                                backgroundAt(nativeStrip),
                            )
                            assertStripContrast(nativeStrip)
                            strip.query = "Readable search query"
                            for (action in listOf(null) + AssistantAction.entries) {
                                strip.selectedAction = action
                                assertStripContrast(strip)
                            }
                            strip.selectedAction = null
                        }
                        val resultSurface = assertCapturedText("Theme contrast result", dark)
                        assertEquals(
                            "Result and strip must share the opaque app surface",
                            stripSurface,
                            resultSurface,
                        )
                        for (icon in listOf("Swap translation languages", "Collapse result")) {
                            assertCapturedContent(
                                compose.onNodeWithContentDescription(icon),
                                icon,
                                dark,
                            )
                        }
                        screenshot("result", dark, dynamic)
                        // Exercise the actual workspace action rather than a stand-in MusicPanel.
                        scenario.onActivity { activity ->
                            descendants(activity.window.decorView)
                                .filterIsInstance<AssistantActionStrip>()
                                .single()
                                .let { strip ->
                                    descendants(strip)
                                        .filterIsInstance<TextView>()
                                        .single { it.text.toString() == "Music" }
                                        .performClick()
                                }
                        }
                        val musicSurface = assertCapturedText("Identify a song", dark)
                        assertEquals(
                            "Music and strip must share the opaque app surface",
                            stripSurface,
                            musicSurface,
                        )
                        for (icon in listOf("Music settings", "Close music")) {
                            assertCapturedContent(
                                compose.onNodeWithContentDescription(icon),
                                icon,
                                dark,
                            )
                        }
                        val listen =
                            if (app.glimpse.music.MusicSession.state.value.matches.isEmpty())
                                "Listen"
                            else "Listen again"
                        for (label in listOf("Both", "Phone", "Mic", listen)) {
                            compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
                            assertCapturedText(label, dark, checkBackgroundBrightness = false)
                        }
                        screenshot("music", dark, dynamic)
                        compose
                            .onNodeWithContentDescription("Close music")
                            .performScrollTo()
                            .performClick()
                    }
            }
        }
    }

    @Test
    fun existingStripRecolorsWithoutLosingQueryFocusOrSelection() {
        oppositeSystemTheme(true)
        saveTheme(dark = true, dynamic = false)
        ActivityScenario.launch<AssistantActivity>(Intent(context, AssistantActivity::class.java))
            .use { scenario ->
                lateinit var original: AssistantActionStrip
                scenario.onActivity { activity ->
                    original =
                        descendants(activity.window.decorView)
                            .filterIsInstance<AssistantActionStrip>()
                            .single()
                    original.query = "Keep this query"
                    descendants(original)
                        .filterIsInstance<TextView>()
                        .single { it.text.toString() == "Mark to search" }
                        .performClick()
                }
                compose.waitForIdle()
                scenario.onActivity {
                    descendants(original).filterIsInstance<EditText>().single().apply {
                        requestFocus()
                        setSelection(2, 6)
                    }
                }
                for (dark in listOf(false, true)) {
                    for (dynamic in listOf(true, false)) {
                        saveTheme(dark, dynamic)
                        compose.waitForIdle()
                        scenario.onActivity { activity ->
                            val strip =
                                descendants(activity.window.decorView)
                                    .filterIsInstance<AssistantActionStrip>()
                                    .single()
                            assertSame(original, strip)
                            assertEquals("Keep this query", strip.query)
                            val search = descendants(strip).filterIsInstance<EditText>().single()
                            assertTrue(search.hasFocus())
                            assertEquals(2, search.selectionStart)
                            assertEquals(6, search.selectionEnd)
                            assertBrightness("Recolored strip", backgroundAt(strip), dark)
                            assertEquals(AssistantAction.MARK, strip.selectedAction)
                            assertStripContrast(strip)
                        }
                    }
                }
            }
    }

    @Test
    fun nativeCaptureDialogAndRealDropdownRowsHonorSavedTheme() {
        for (dark in listOf(false, true)) {
            oppositeSystemTheme(dark)
            for (dynamic in listOf(false, true)) {
                saveTheme(dark, dynamic)
                ActivityScenario.launch<CapturePermissionActivity>(
                        Intent(context, CapturePermissionActivity::class.java)
                            .setAction(CapturePermissionActivity.LIVE_TRANSLATE),
                    )
                    .use { scenario ->
                        await {
                            var shown = false
                            scenario.onActivity { shown = dialogRoot() != null }
                            shown
                        }
                        scenario.onActivity {
                            val root = dialogRoot()!!
                            val positive = root.findViewById<TextView>(android.R.id.button1)
                            assertNotNull("Keep the platform positive button", positive)
                            assertTrue(positive.isEnabled)
                            val labels =
                                descendants(root)
                                    .filterIsInstance<TextView>()
                                    .filter { it.isShown && it.isEnabled && it.text.isNotBlank() }
                                    .toList()
                            assertTrue(labels.any { it.text.toString() == "Translate your screen" })
                            labels.forEach { assertTextContrast(it) }
                            val spinners = descendants(root).filterIsInstance<Spinner>().toList()
                            assertEquals(2, spinners.size)
                            spinners.forEach { spinner ->
                                assertEquals(
                                    if (dark) Configuration.UI_MODE_NIGHT_YES
                                    else Configuration.UI_MODE_NIGHT_NO,
                                    spinner.resources.configuration.uiMode and
                                        Configuration.UI_MODE_NIGHT_MASK,
                                )
                                assertTextContrast(spinner.getChildAt(0) as TextView)
                                assertBrightness(
                                    "Spinner",
                                    backgroundAt(spinner.getChildAt(0)),
                                    dark,
                                )
                            }
                            spinners.first().setSelection(0)
                        }
                        screenshot("capture-dialog", dark, dynamic)
                        scenario.onActivity {
                            descendants(dialogRoot()!!)
                                .filterIsInstance<Spinner>()
                                .first()
                                .performClick()
                        }
                        await {
                            var shown = false
                            scenario.onActivity { shown = popupRows().size >= 2 }
                            shown
                        }
                        scenario.onActivity {
                            val rows = popupRows()
                            rows.forEach { assertTextContrast(it) }
                            assertTrue(
                                "Selected and unselected rows need distinct containers",
                                rows.map { backgroundAt(it) }.distinct().size >= 2,
                            )
                        }
                        screenshot("language-dropdown", dark, dynamic)
                        device.pressBack()
                    }
            }
        }
    }

    @Test
    fun unsurfacedComposeTextUsesAppForegroundAtThemeRoot() {
        for (dark in listOf(false, true)) {
            oppositeSystemTheme(dark)
            for (dynamic in listOf(false, true)) {
                saveTheme(dark, dynamic)
                val prefs = graph.currentPreferences.value
                ActivityScenario.launch<AssistantActivity>(
                        Intent(context, AssistantActivity::class.java),
                    )
                    .use { scenario ->
                        scenario.onActivity { activity ->
                            activity.setContent {
                                GlimpseTheme(prefs) {
                                    Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                                        // Deliberately no Surface or explicit text color.
                                        Text("Unsurfaced root text", Modifier.padding(24.dp))
                                    }
                                }
                            }
                        }
                        assertCapturedText("Unsurfaced root text", dark)
                    }
            }
        }
    }

    private fun assertStripContrast(strip: AssistantActionStrip) {
        descendants(strip)
            .filterIsInstance<TextView>()
            .filter { it.isEnabled }
            .forEach {
                assertTextContrast(it)
                if (it is EditText)
                    assertContrast("Search hint", it.currentHintTextColor, backgroundAt(it))
            }
        val submit = descendants(strip).filterIsInstance<ImageButton>().single()
        assertContrast(
            "Search icon",
            submit.imageTintList!!.getColorForState(submit.drawableState, 0),
            backgroundAt(submit),
        )
        val rendered = Bitmap.createBitmap(submit.width, submit.height, Bitmap.Config.ARGB_8888)
        val background = backgroundAt(submit)
        rendered.eraseColor(background)
        submit.draw(Canvas(rendered))
        val ink =
            (0 until rendered.height)
                .flatMap { y ->
                    (0 until rendered.width).map { x -> rendered.getPixel(x, y) }
                }
                .groupingBy { it }
                .eachCount()
                .filterValues { it >= 3 }
                .keys
                .maxBy { ColorUtils.calculateContrast(it, background) }
        rendered.recycle()
        assertContrast("Rendered search icon", ink, background)
    }

    private fun assertTextContrast(view: TextView) =
        assertContrast(view.text.toString(), view.currentTextColor, backgroundAt(view))

    private fun assertContrast(label: String, foreground: Int, background: Int) {
        val ratio = ColorUtils.calculateContrast(foreground, background)
        assertTrue(
            "$label contrast was $ratio; foreground=${Integer.toHexString(foreground)}, background=${Integer.toHexString(background)}",
            ratio >= 4.5,
        )
    }

    /** Read pixels produced by Compose, including its inherited content color and compositing. */
    private fun assertCapturedText(
        text: String,
        dark: Boolean,
        checkBackgroundBrightness: Boolean = true,
    ): Int =
        assertCapturedContent(
            compose.onNodeWithText(text, useUnmergedTree = true),
            text,
            dark,
            checkBackgroundBrightness,
        )

    private fun assertCapturedContent(
        node: SemanticsNodeInteraction,
        text: String,
        dark: Boolean,
        checkBackgroundBrightness: Boolean = true,
    ): Int {
        val pixels = node.captureToImage().toPixelMap()
        val histogram = mutableMapOf<Int, Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y].toArgb()
            histogram[color] = (histogram[color] ?: 0) + 1
        }
        val background = histogram.maxBy { it.value }.key
        assertEquals("Captured text must have an opaque background", 255, Color.alpha(background))
        if (checkBackgroundBrightness) assertBrightness(text, background, dark)
        // Ignore single-pixel antialiasing fringes; inspect the actual, repeated glyph ink.
        val ink =
            histogram
                .filterValues { it >= 3 }
                .keys
                .maxBy { ColorUtils.calculateContrast(it, background) }
        assertContrast("Rendered $text", ink, background)
        if (checkBackgroundBrightness) {
            assertTrue(
                "$text must be ${if (dark) "light on dark" else "dark on light"}",
                (ColorUtils.calculateLuminance(ink) > ColorUtils.calculateLuminance(background)) ==
                    dark,
            )
        }
        return background
    }

    private fun assertBrightness(label: String, color: Int, dark: Boolean) {
        val luminance = ColorUtils.calculateLuminance(color)
        assertTrue(
            "$label ignored saved ${if (dark) "dark" else "light"} theme: $luminance",
            if (dark) luminance < .1 else luminance > .6,
        )
    }

    private fun backgroundAt(view: View): Int {
        val parent = view.parent as? View
        val beneath = parent?.let(::backgroundAt) ?: Color.WHITE
        val drawable = view.background ?: return beneath
        val bitmap =
            Bitmap.createBitmap(
                view.width.coerceAtLeast(2),
                view.height.coerceAtLeast(2),
                Bitmap.Config.ARGB_8888,
            )
        drawable.bounds = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        bitmap.recycle()
        return ColorUtils.compositeColors(color, beneath)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup)
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun dialogRoot(): View? =
        WindowInspector.getGlobalWindowViews().firstOrNull { root ->
            descendants(root).filterIsInstance<TextView>().any {
                it.text.toString() == "Translate your screen"
            }
        }

    private fun popupRows(): List<TextView> =
        WindowInspector.getGlobalWindowViews()
            .flatMap { descendants(it).filterIsInstance<ListView>().toList() }
            .flatMap { list ->
                descendants(list)
                    .filterIsInstance<TextView>()
                    .filter { it.isShown && it.text.isNotBlank() }
                    .toList()
            }

    private fun saveTheme(dark: Boolean, dynamic: Boolean) {
        val theme = if (dark) "dark" else "light"
        runBlocking {
            graph.preferences.set("theme", theme)
            graph.preferences.set("dynamicColor", dynamic)
        }
        await {
            graph.currentPreferences.value.let { it.theme == theme && it.dynamicColor == dynamic }
        }
    }

    private fun oppositeSystemTheme(appDark: Boolean) {
        setNightMode(if (appDark) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES)
        await {
            val systemDark =
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            systemDark != appDark
        }
    }

    private fun setNightMode(mode: Int) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.MODIFY_DAY_NIGHT_MODE",
        )
        try {
            uiMode.setNightMode(mode)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(kind: String, dark: Boolean, dynamic: Boolean) {
        assertTrue(
            device.takeScreenshot(
                File(
                    context.cacheDir,
                    "theme-$kind-${if (dark) "dark" else "light"}-${if (dynamic) "dynamic" else "static"}.png",
                ),
            ),
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            assertTrue(
                "Timed out waiting for themed UI",
                android.os.SystemClock.elapsedRealtime() < deadline,
            )
            android.os.SystemClock.sleep(50)
        }
    }
}
