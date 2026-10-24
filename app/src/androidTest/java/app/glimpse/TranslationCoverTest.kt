package app.glimpse

import android.content.Intent
import android.graphics.*
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.glimpse.assistant.TranslationLabelCover
import app.glimpse.data.AppPreferences
import app.glimpse.ui.nativeThemeColors
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verify actual WindowManager composition, including source glyphs beneath a touch-through label.
 */
@RunWith(AndroidJUnit4::class)
class TranslationCoverTest {
    @Test
    fun originalTextIsCoveredInBothThemesWithoutInterceptingTouches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        fun shell(command: String) =
            instrumentation.uiAutomation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
                    .readBytes()
                    .toString(Charsets.UTF_8)
            }
        val oldMode =
            shell("appops get app.glimpse SYSTEM_ALERT_WINDOW").let {
                Regex("SYSTEM_ALERT_WINDOW: (\\w+)").find(it)?.groupValues?.get(1) ?: "default"
            }
        shell("appops set app.glimpse SYSTEM_ALERT_WINDOW allow")
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
                scenario ->
                val clicks = AtomicInteger()
                lateinit var windows: WindowManager
                scenario.onActivity { activity ->
                    windows = activity.getSystemService(WindowManager::class.java)
                    activity.setContentView(
                        object : View(activity) {
                                override fun onDraw(canvas: Canvas) {
                                    canvas.drawColor(Color.WHITE)
                                    val paint =
                                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                                    for (x in 0 until width step 48) canvas.drawRect(
                                        x.toFloat(),
                                        0f,
                                        x + 24f,
                                        height.toFloat(),
                                        paint,
                                    )
                                    paint.textSize = 100f
                                    canvas.drawText("Original lettering", 70f, height / 2f, paint)
                                }
                            }
                            .apply { setOnClickListener { clicks.incrementAndGet() } },
                    )
                }
                device.waitForIdle()
                val source = instrumentation.uiAutomation.takeScreenshot()!!
                val bounds =
                    Rect(
                        source.width / 6,
                        source.height / 4,
                        source.width * 5 / 6,
                        source.height * 2 / 3,
                    )
                for (dark in listOf(false, true)) {
                    val theme =
                        nativeThemeColors(
                            context,
                            AppPreferences(
                                theme = if (dark) "dark" else "light",
                                dynamicColor = false,
                            ),
                        )
                    val background = TranslationLabelCover.readableColor(theme.surface)
                    val foreground = TranslationLabelCover.readableColor(theme.foreground)
                    assertTrue(ColorUtils.calculateContrast(foreground, background) >= 4.5)
                    val cover =
                        TranslationLabelCover.render(source, bounds) { canvas ->
                            canvas.drawColor(background)
                            canvas.drawText(
                                "Translation",
                                24f,
                                100f,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = foreground
                                    textSize = 64f
                                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                                },
                            )
                        }
                    lateinit var overlay: View
                    scenario.onActivity { activity ->
                        overlay =
                            object : View(activity) {
                                override fun onDraw(canvas: Canvas) {
                                    canvas.drawBitmap(
                                        cover,
                                        bounds.left.toFloat(),
                                        bounds.top.toFloat(),
                                        null,
                                    )
                                }
                            }
                        windows.addView(
                            overlay,
                            WindowManager.LayoutParams(
                                    -1,
                                    -1,
                                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                    PixelFormat.TRANSLUCENT,
                                )
                                .apply {
                                    alpha = TranslationLabelCover.WINDOW_ALPHA
                                    setFitInsetsTypes(0)
                                    layoutInDisplayCutoutMode =
                                        WindowManager.LayoutParams
                                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                                    windowAnimations = 0
                                },
                        )
                    }
                    try {
                        val deadline = android.os.SystemClock.uptimeMillis() + 5000
                        var composed: Bitmap
                        do {
                            android.os.SystemClock.sleep(100)
                            composed = instrumentation.uiAutomation.takeScreenshot()!!
                        } while (
                            distance(
                                composed.getPixel(bounds.centerX(), bounds.bottom - 40),
                                background,
                            ) > 5 && android.os.SystemClock.uptimeMillis() < deadline
                        )
                        // These points cross both black source glyphs/stripes and white source
                        // background. Both must compose to the SAME solid label backdrop.
                        for (y in bounds.top + 180 until bounds.bottom - 20 step 31) for (x in
                            bounds.left + 16 until bounds.right - 16 step 19) assertTrue(
                            "Source bled through in ${if (dark) "dark" else "light"} theme at $x,$y",
                            distance(composed.getPixel(x, y), background) <= 5,
                        )
                        var translatedInk = 0
                        for (y in bounds.top + 30 until bounds.top + 110) for (x in
                            bounds.left + 24 until minOf(bounds.right, bounds.left + 400)) if (
                            distance(composed.getPixel(x, y), foreground) <= 5
                        )
                            translatedInk++
                        assertTrue("Translated glyphs must remain visible", translatedInk > 100)
                        device.takeScreenshot(
                            File(
                                context.cacheDir,
                                "translation-cover-${if (dark) "dark" else "light"}.png",
                            ),
                        )
                        val before = clicks.get()
                        device.click(bounds.centerX(), bounds.centerY())
                        val touchDeadline = android.os.SystemClock.uptimeMillis() + 3000
                        while (
                            clicks.get() == before &&
                                android.os.SystemClock.uptimeMillis() < touchDeadline
                        ) android.os.SystemClock.sleep(50)
                        assertEquals(
                            "The covering label must not consume the app's input",
                            before + 1,
                            clicks.get(),
                        )
                    } finally {
                        instrumentation.runOnMainSync { windows.removeView(overlay) }
                    }
                }
            }
        } finally {
            shell("appops set app.glimpse SYSTEM_ALERT_WINDOW $oldMode")
        }
    }

    private fun distance(first: Int, second: Int) =
        maxOf(
            kotlin.math.abs(Color.red(first) - Color.red(second)),
            kotlin.math.abs(Color.green(first) - Color.green(second)),
            kotlin.math.abs(Color.blue(first) - Color.blue(second)),
        )
}
