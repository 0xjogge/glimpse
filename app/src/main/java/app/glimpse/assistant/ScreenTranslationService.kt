package app.glimpse.assistant

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.glimpse.GlimpseApplication
import app.glimpse.core.*
import app.glimpse.data.AppPreferences
import kotlinx.coroutines.*

class ScreenTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windows: WindowManager
    private lateinit var translator: LiveTranslationController
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var latestFrame: Bitmap? = null
    @Volatile private var prefs = AppPreferences()
    @Volatile private var paused = false
    @Volatile private var targetVisible = true
    @Volatile private var blockedReason: String? = null
    private var labels: Labels? = null
    private var controls: LinearLayout? = null
    private var status: TextView? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        try {
            startForeground(
                41,
                captureNotification("Live screen translation"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            windows = getSystemService(WindowManager::class.java)
            val graph = (application as GlimpseApplication).graph
            translator = LiveTranslationController(graph.engines)
            scope.launch { graph.preferences.values.collect { prefs = it } }
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

                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                        targetVisible = isVisible
                        latestFrame = null
                        translator.reset()
                        labels?.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
                        updateStatus(translator.state.value.status)
                    }
                },
                Handler(mainLooper),
            )
            addWindows()
            scope.launch {
                translator.state.collect { state ->
                    labels?.state =
                        if (paused || !targetVisible || blockedReason != null) LiveState()
                        else state
                    updateStatus(state.status)
                }
            }
            val bounds = windows.maximumWindowMetrics.bounds
            configureReader(bounds.width(), bounds.height())
            display =
                projection!!.createVirtualDisplay(
                    "Live translation",
                    bounds.width(),
                    bounds.height(),
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface,
                    null,
                    null,
                )
            scope.launch(Dispatchers.Default) { processFrames() }
        } catch (e: Exception) {
            android.util.Log.e("ScreenTranslation", "Could not start capture", e)
            android.widget.Toast.makeText(
                    this,
                    e.localizedMessage ?: "Could not start live translation",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun processFrames() {
        while (currentCoroutineContext().isActive) {
            try {
                // Poll the latest buffer at analysis cadence. Screen capture may emit only on
                // damage, so an unchanged cached frame must still get its second stabilization
                // pass. Throttling image callbacks would otherwise lose the final scroll frame.
                reader?.acquireLatestImage()?.use { image ->
                    if (!paused && targetVisible && blockedReason == null) {
                        val plane = image.planes[0]
                        val padded =
                            Bitmap.createBitmap(
                                plane.rowStride / plane.pixelStride,
                                image.height,
                                Bitmap.Config.ARGB_8888,
                            )
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        if (containsOverlay(bitmap)) {
                            withContext(Dispatchers.Main) {
                                blockedReason =
                                    "Entire-screen sharing detected. Stop and choose a single app."
                                latestFrame = null
                                translator.reset()
                                updateStatus("")
                            }
                        } else {
                            val scale = minOf(1f, 1440f / maxOf(bitmap.width, bitmap.height))
                            latestFrame =
                                if (scale < 1)
                                    Bitmap.createScaledBitmap(
                                        bitmap,
                                        (bitmap.width * scale).toInt(),
                                        (bitmap.height * scale).toInt(),
                                        true,
                                    )
                                else bitmap
                        }
                    }
                }
                if (!paused && targetVisible && blockedReason == null)
                    latestFrame?.let { frame ->
                        val options = prefs
                        translator.analyze(
                            frame,
                            LiveTranslationOptions(
                                options.ocrEngine,
                                options.translationEngine,
                                options.script,
                                options.source,
                                options.target,
                            ),
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus(e.localizedMessage ?: "Screen frame unavailable")
                }
            }
            delay(650)
        }
    }

    private fun configureReader(width: Int, height: Int) {
        val previous = reader
        latestFrame = null
        display?.surface = null
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display?.resize(width, height, resources.displayMetrics.densityDpi)
        display?.surface = reader!!.surface
        previous?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::windows.isInitialized && display != null) {
            translator.reset()
            val bounds = windows.maximumWindowMetrics.bounds
            configureReader(bounds.width(), bounds.height())
        }
    }

    private fun addWindows() {
        labels =
            Labels().also {
                windows.addView(
                    it,
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
                            // Android's untrusted-touch limit is .8: retain real touch-through
                            // scrolling.
                            alpha = .75f
                            setFitInsetsTypes(0)
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        },
                )
            }
        controls =
            LinearLayout(this)
                .apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 8, 16, 8)
                    setBackgroundColor(Color.rgb(24, 35, 30))
                    status =
                        TextView(this@ScreenTranslationService).apply {
                            setTextColor(Color.WHITE)
                            text = "Hold steady to translate"
                            textSize = 12f
                        }
                    addView(status, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(
                        Button(this@ScreenTranslationService).apply {
                            text = "Pause"
                            setOnClickListener {
                                paused = !paused
                                text = if (paused) "Resume" else "Pause"
                                translator.reset()
                                updateStatus("Hold steady to translate")
                            }
                        },
                    )
                    addView(
                        Button(this@ScreenTranslationService).apply {
                            text = "Stop"
                            setOnClickListener { stopSelf() }
                        },
                    )
                }
                .also {
                    windows.addView(
                        it,
                        WindowManager.LayoutParams(
                                -1,
                                -2,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                PixelFormat.TRANSLUCENT,
                            )
                            .apply { gravity = Gravity.BOTTOM },
                    )
                }
    }

    private fun updateStatus(value: String) {
        status?.text =
            blockedReason
                ?: if (!targetVisible) "Return to the shared app to translate"
                else if (paused) "Paused" else value
    }

    private fun containsOverlay(bitmap: Bitmap): Boolean {
        for (dy in -bitmap.height / 8..bitmap.height / 8) {
            val y = bitmap.height / 2 + dy
            if (y < 0 || y + 8 >= bitmap.height || bitmap.width < 12) continue
            val first = bitmap.getPixel(8, y)
            val second = bitmap.getPixel(8, y + 8)
            if (
                near(first, MARKER_A) &&
                    near(second, MARKER_B) &&
                    Color.red(second) - Color.red(first) > 40 &&
                    Color.green(first) - Color.green(second) > 20 &&
                    Color.blue(second) - Color.blue(first) > 10
            )
                return true
        }
        return false
    }

    private fun near(a: Int, b: Int) =
        kotlin.math.abs(Color.red(a) - Color.red(b)) < 72 &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) < 72 &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 72

    private data class Label(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val layout: StaticLayout,
    )

    private inner class Labels : View(this@ScreenTranslationService) {
        private val background = Paint().apply { color = Color.rgb(24, 35, 30) }
        private val first = Paint().apply { color = MARKER_A }
        private val second = Paint().apply { color = MARKER_B }
        private val rim =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
        private var boxes = emptyList<Label>()
        var state = LiveState()
            set(value) {
                field = value
                layoutLabels()
                invalidate()
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            rim.shader =
                LinearGradient(
                    0f,
                    0f,
                    w.toFloat(),
                    h.toFloat(),
                    MARKER_A,
                    MARKER_B,
                    Shader.TileMode.CLAMP,
                )
            layoutLabels()
        }

        private fun layoutLabels() {
            val recognition = state.recognition
            if (recognition == null || width == 0 || height == 0) {
                boxes = emptyList()
                return
            }
            val placement =
                ImagePlacement.fit(
                    recognition.imageWidth,
                    recognition.imageHeight,
                    width.toFloat(),
                    height.toFloat(),
                )
            val density = resources.displayMetrics.density
            boxes =
                recognition.lines.mapNotNull { line ->
                    val text = state.translations[line.text] ?: return@mapNotNull null
                    val box = line.bounds
                    val left = placement.left + box.left * placement.width
                    val top = placement.top + box.top * placement.height
                    val w =
                        (box.width * placement.width)
                            .coerceAtLeast(40 * density)
                            .coerceAtMost(width - left)
                    if (w < 1) return@mapNotNull null
                    val paint =
                        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            textSize =
                                (box.height * placement.height * .74f).coerceIn(
                                    12 * density,
                                    26 * density,
                                )
                        }
                    val layout =
                        StaticLayout.Builder.obtain(text, 0, text.length, paint, w.toInt())
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setIncludePad(false)
                            .setMaxLines(5)
                            .build()
                    Label(
                        left,
                        top,
                        w,
                        maxOf(box.height * placement.height, layout.height.toFloat()),
                        layout,
                    )
                }
        }

        override fun onDraw(canvas: Canvas) {
            val radius = 36f * resources.displayMetrics.density
            canvas.drawRoundRect(2f, 2f, width - 2f, height - 2f, radius, radius, rim)
            boxes.forEach { box ->
                canvas.drawRoundRect(
                    box.x - 3,
                    box.y - 3,
                    box.x + box.width + 3,
                    box.y + box.height + 3,
                    5f,
                    5f,
                    background,
                )
                canvas.save()
                canvas.translate(box.x, box.y)
                box.layout.draw(canvas)
                canvas.restore()
            }
            canvas.drawRect(4f, height / 2f, 13f, height / 2f + 7, first)
            canvas.drawRect(4f, height / 2f + 8, 13f, height / 2f + 15, second)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        latestFrame = null
        display?.release()
        reader?.close()
        projection?.stop()
        if (::windows.isInitialized) {
            labels?.let { runCatching { windows.removeView(it) } }
            controls?.let { runCatching { windows.removeView(it) } }
        }
        super.onDestroy()
    }

    companion object {
        private const val MARKER_A = 0xFF3BD6C6.toInt()
        private const val MARKER_B = 0xFFC28CFA.toInt()
    }
}
