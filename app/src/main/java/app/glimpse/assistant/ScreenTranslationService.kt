package app.glimpse.assistant

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.glimpse.GlimpseApplication
import app.glimpse.core.*
import app.glimpse.data.AppPreferences
import app.glimpse.ui.NativeThemeColors
import app.glimpse.ui.nativeThemeColors
import app.glimpse.ui.nativeThemeContext
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update

/** Whole-display capture: composited frames monitor motion; only clean settled frames reach OCR. */
class ScreenTranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captureThread = HandlerThread("Glimpse screen frames")
    private lateinit var capture: Handler
    private lateinit var windows: WindowManager
    private lateinit var translator: LiveTranslationController
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null // Accessed exclusively on captureThread.
    private val frames = Channel<Frame>(Channel.CONFLATED)
    private var prefs = AppPreferences()
    private var labels: Labels? = null
    private var controls: LinearLayout? = null
    private var status: TextView? = null
    private var pair: Button? = null
    private var pauseButton: Button? = null
    private var originalButton: Button? = null
    private var downloadButton: Button? = null
    private var stopButton: Button? = null
    private lateinit var colors: NativeThemeColors
    private var paused = false
    private var original = false
    private var showing = false
    private var awaitingPresentation = false
    private var priming = false
    private var cleanReference: IntArray? = null
    private var expectedPresentation: IntArray? = null
    private var minimumCleanTimestamp = Long.MAX_VALUE
    private var completedScene = -1L
    @Volatile private var stopped = false
    private var phase = "Starting screen capture"
    private var scene = 0L
    @Volatile private var epoch = 0L
    @Volatile private var suppressPresentation = true
    @Volatile private var dock = Rect()
    @Volatile private var geometry = 0L
    @Volatile private var screenTop = 0
    @Volatile private var screenBottom = 0
    private var baseline: IntArray? = null
    private var latestClean: Frame? = null
    private var quiet: Job? = null
    private var analysis: Job? = null
    private var samplingTimeout: Job? = null
    private var download: Job? = null
    private var result = LiveState()
    private var cleanFrames = 0L
    private var rejectedFrames = 0L
    private var lastChange = 0L
    private var targetWidth = 0
    private var targetHeight = 0

    private data class Frame(
        val epoch: Long,
        val geometry: Long,
        val timestamp: Long,
        val sample: IntArray,
        val contaminated: Boolean,
        val bitmap: Bitmap?,
        val ignored: Rect,
    )

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) {
            when (intent.action) {
                PAUSE -> {
                    paused = !paused
                    invalidateScene()
                    if (paused) phase = "Paused" else acquireCleanScene()
                    updateControls()
                }
                ORIGINAL -> {
                    original = !original
                    acquireCleanScene()
                    updateControls()
                }
                REFRESH -> acquireCleanScene()
                DOWNLOAD -> downloadModels()
            }
            return START_NOT_STICKY
        }
        if (intent.action != START) return START_NOT_STICKY
        try {
            startForeground(
                41,
                captureNotification("Live screen translation"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            windows = getSystemService(WindowManager::class.java)
            val graph = (application as GlimpseApplication).graph
            prefs = graph.currentPreferences.value
            colors = nativeThemeColors(this, prefs)
            translator = LiveTranslationController(graph.engines)
            captureThread.start()
            capture = Handler(captureThread.looper)
            ScreenTranslationSession.mutable.value = ScreenTranslationState(active = true)
            scope.launch {
                graph.preferences.values.collect { next ->
                    val changed = options(prefs) != options(next)
                    val nextColors = nativeThemeColors(this@ScreenTranslationService, next)
                    val themeChanged = colors != nextColors
                    prefs = next
                    colors = nextColors
                    if (themeChanged) applyDockColors()
                    if (changed || themeChanged) {
                        translator.invalidate(clearCache = changed)
                        acquireCleanScene()
                    }
                    updateControls()
                }
            }
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

                    override fun onCapturedContentResize(width: Int, height: Int) {
                        if (
                            !stopped &&
                                width > 0 &&
                                height > 0 &&
                                (width != targetWidth || height != targetHeight)
                        )
                            configureReader(width, height)
                    }
                },
                Handler(mainLooper),
            )
            addWindows()
            scope.launch { for (frame in frames) consume(frame) }
            scope.launch {
                translator.state.collect {
                    result = it
                    updateControls()
                }
            }
            val bounds = windows.maximumWindowMetrics.bounds
            configureReader(bounds.width(), bounds.height())
        } catch (e: Exception) {
            android.util.Log.e("ScreenTranslation", "Could not start capture", e)
            android.widget.Toast.makeText(
                    this,
                    e.localizedMessage ?: "Could not start translation",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun options(p: AppPreferences) =
        LiveTranslationOptions(
            p.ocrEngine,
            p.translationEngine,
            p.script,
            p.source,
            p.target,
        )

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (stopped || !::windows.isInitialized) return
        updateCaptureInsets()
        val nextColors = nativeThemeColors(this, prefs)
        if (nextColors != colors) {
            colors = nextColors
            applyDockColors()
            acquireCleanScene()
        }
        controls?.let { view ->
            val density = resources.displayMetrics.density
            val params = view.layoutParams as WindowManager.LayoutParams
            params.width =
                minOf(
                    windows.maximumWindowMetrics.bounds.width() - (32 * density).toInt(),
                    (400 * density).toInt(),
                )
            params.y = (16 * density).toInt()
            windows.updateViewLayout(view, params)
            view.requestApplyInsets()
        }
    }

    private fun configureReader(width: Int, height: Int) {
        if (stopped) return
        targetWidth = width
        targetHeight = height
        val readerGeometry = ++geometry
        acquireCleanScene()
        val currentEpoch = epoch
        capture.post {
            if (stopped || geometry != readerGeometry) return@post
            try {
                display?.surface = null
                reader?.close()
                val next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                reader = next
                var lastSample = 0L
                var lastTimestamp = Long.MIN_VALUE
                var scheduled = false
                val readLatest = Runnable {
                    scheduled = false
                    if (stopped || reader !== next || geometry != readerGeometry) return@Runnable
                    try {
                        val token = epoch
                        next.acquireLatestImage()?.use { image ->
                            val now = SystemClock.uptimeMillis()
                            if (image.timestamp <= lastTimestamp) return@use
                            lastSample = now
                            lastTimestamp = image.timestamp
                            val hidden = suppressPresentation
                            val ignored = Rect(dock)
                            val contaminated = containsPresentation(image)
                            val sample = sample(image, ignored)
                            val bitmap = if (hidden && !contaminated) copySource(image) else null
                            if (!stopped && token == epoch)
                                frames.trySend(
                                    Frame(
                                        token,
                                        readerGeometry,
                                        image.timestamp,
                                        sample,
                                        contaminated,
                                        bitmap,
                                        ignored,
                                    ),
                                )
                        }
                    } catch (_: IllegalStateException) {
                        // A replaced/revoked reader cannot contribute to the next geometry epoch.
                    }
                }
                next.setOnImageAvailableListener(
                    {
                        if (!scheduled) {
                            scheduled = true
                            // Delay acquisition, not delivery: retain the final damage frame even
                            // if
                            // capture stops emitting as soon as the user finishes scrolling.
                            capture.postDelayed(
                                readLatest,
                                (90 - (SystemClock.uptimeMillis() - lastSample)).coerceAtLeast(0),
                            )
                        }
                    },
                    capture,
                )
                if (epoch != currentEpoch) {
                    // Scene changes are allowed while the reader is being installed; geometry is
                    // not.
                    if (width != targetWidth || height != targetHeight) return@post
                }
                val density = resources.displayMetrics.densityDpi
                if (display == null) {
                    display =
                        projection?.createVirtualDisplay(
                            "Glimpse whole-screen translation",
                            width,
                            height,
                            density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            next.surface,
                            null,
                            capture,
                        )
                } else {
                    display?.resize(width, height, density)
                    display?.surface = next.surface
                }
            } catch (e: Exception) {
                if (!stopped)
                    scope.launch {
                        android.util.Log.e("ScreenTranslation", "Capture surface unavailable", e)
                        stopSelf()
                    }
            }
        }
    }

    private fun invalidateScene() {
        scene++
        epoch++
        quiet?.cancel()
        analysis?.cancel()
        samplingTimeout?.cancel()
        if (::translator.isInitialized) translator.invalidate()
        result = LiveState()
        latestClean = null
        baseline = null
        showing = false
        awaitingPresentation = false
        priming = false
        cleanReference = null
        expectedPresentation = null
        minimumCleanTimestamp = Long.MAX_VALUE
        suppressPresentation = true
        labels?.visibility = View.INVISIBLE
        phase = if (paused) "Paused" else "Scroll, then hold steady"
    }

    private fun acquireCleanScene() {
        invalidateScene()
        lastChange = SystemClock.uptimeMillis()
        if (!paused) {
            // Observe the marked surface in the capture stream, then require a newer unmarked
            // buffer. Do not discard a hide-damage frame: it may be the last frame of a still page.
            if (labels != null) {
                // A static display may emit no new buffers. Briefly submit only the ring/signature
                // to generate damage, then hide it once its actual projection frame is observed.
                priming = true
                suppressPresentation = false
                labels?.apply {
                    state = LiveState()
                    visibility = View.VISIBLE
                }
            }
            samplingTimeout = scope.launch {
                delay(4000)
                if (latestClean == null) {
                    phase = "Waiting for a clean screen · tap the language control to retry"
                    updateControls()
                }
            }
        }
        updateControls()
    }

    private fun consume(frame: Frame) {
        if (stopped || frame.epoch != epoch || frame.geometry != geometry) return
        if (priming) {
            if (frame.contaminated) {
                rejectedFrames++
                priming = false
                minimumCleanTimestamp = frame.timestamp
                suppressPresentation = true
                labels?.visibility = View.INVISIBLE
            }
            return
        }
        if (showing) {
            if (awaitingPresentation) {
                // Showing the stationary ring/labels is our own change, not source motion.
                if (!frame.contaminated) return
                val transition = labels?.withoutRing(frame.sample)
                val expected = expectedPresentation
                if (
                    transition != null &&
                        expected != null &&
                        (ScreenMotion.changed(expected, transition) ||
                            labels?.changedUnderLabels(expected, transition) == true)
                ) {
                    acquireCleanScene()
                    return
                }
                awaitingPresentation = false
                baseline = frame.sample
                publishSession()
                return
            }
            if (
                baseline?.let {
                    ScreenMotion.changed(it, frame.sample) ||
                        labels?.changedUnderLabels(it, frame.sample) == true
                } == true
            ) {
                acquireCleanScene()
            }
            return
        }
        if (
            frame.contaminated || frame.bitmap == null || frame.timestamp <= minimumCleanTimestamp
        ) {
            rejectedFrames++
            publishSession()
            return
        }
        cleanFrames++
        samplingTimeout?.cancel()
        val moved = baseline?.let { ScreenMotion.changed(it, frame.sample) } ?: true
        latestClean = frame
        if (paused) {
            baseline = frame.sample
            publishSession()
            return
        }
        if (moved) {
            analysis?.cancel()
            translator.invalidate()
            scene++
            result = LiveState()
            lastChange = SystemClock.uptimeMillis()
            baseline = frame.sample
            phase = "Scroll, then hold steady"
            scheduleQuiet()
        } else if (quiet == null && analysis?.isActive != true && completedScene != scene)
            scheduleQuiet()
        updateControls()
    }

    private fun scheduleQuiet() {
        quiet?.cancel()
        quiet = scope.launch {
            delay((450 - (SystemClock.uptimeMillis() - lastChange)).coerceAtLeast(0))
            quiet = null
            val clean = latestClean ?: return@launch
            if (paused || clean.epoch != epoch) return@launch
            val current = scene
            phase = "Translating on device"
            updateControls()
            analysis = scope.launch {
                withContext(Dispatchers.Default) {
                    val source = clean.bitmap!!
                    val scale = minOf(1f, 1440f / maxOf(source.width, source.height))
                    val ocrImage =
                        if (scale < 1)
                            Bitmap.createScaledBitmap(
                                source,
                                (source.width * scale).toInt().coerceAtLeast(1),
                                (source.height * scale).toInt().coerceAtLeast(1),
                                true,
                            )
                        else source.copy(Bitmap.Config.ARGB_8888, true)
                    // Mask only OCR input. Covering pixels need the unmodified native capture,
                    // including source glyphs next to the dock's exclusion margin.
                    Canvas(ocrImage).apply {
                        val mask = Paint().apply { color = BACKGROUND }
                        drawRect(
                            RectF(clean.ignored).apply {
                                left *= scale
                                top *= scale
                                right *= scale
                                bottom *= scale
                            },
                            mask,
                        )
                        drawRect(0f, 0f, ocrImage.width.toFloat(), screenTop * scale, mask)
                        drawRect(
                            0f,
                            (source.height - screenBottom) * scale,
                            ocrImage.width.toFloat(),
                            ocrImage.height.toFloat(),
                            mask,
                        )
                    }
                    translator.analyze(ocrImage, options(prefs), settled = true)
                }
                if (current != scene || clean.epoch != epoch || paused) return@launch
                result = translator.state.value
                completedScene = current
                showResult(clean)
            }
        }
    }

    private fun showResult(clean: Frame) {
        phase = result.status
        if (!original && !paused) {
            showing = true
            awaitingPresentation = true
            suppressPresentation = false
            cleanReference = baseline
            baseline = null
            labels?.apply {
                source = clean.bitmap
                state = result
                expectedPresentation = cleanReference?.let(::expectedSample)
                visibility = View.VISIBLE
            }
        }
        updateControls()
    }

    private fun downloadModels() {
        if (download?.isActive == true || result.missingModels.isEmpty()) return
        val required = result.missingModels.toSet()
        val configuration = prefs
        download = scope.launch {
            ScreenTranslationSession.mutable.update { it.copy(downloading = required) }
            updateControls()
            try {
                val engine =
                    (application as GlimpseApplication)
                        .graph
                        .engines
                        .translator(configuration.translationEngine)
                for (tag in required) engine.download(tag, configuration.wifiOnly)
                translator.invalidate(clearCache = true)
                acquireCleanScene()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = e.localizedMessage ?: "Language download failed. Tap Download to retry."
            } finally {
                ScreenTranslationSession.mutable.update { it.copy(downloading = emptySet()) }
                updateControls()
            }
        }
    }

    private fun publishSession() {
        if (stopped) return
        ScreenTranslationSession.mutable.update {
            it.copy(
                active = true,
                paused = paused,
                original = original,
                status = phase,
                source = prefs.source,
                target = prefs.target,
                missingModels = result.missingModels,
                regions =
                    if (showing && !awaitingPresentation && !original) result.regions
                    else emptyList(),
                sourceText = result.recognition?.text.orEmpty(),
                cleanFrames = cleanFrames,
                rejectedFrames = rejectedFrames,
                scene = scene,
            )
        }
    }

    private fun updateControls() {
        publishSession()
        fun name(tag: String) =
            if (tag == "auto") "Auto" else Locale.forLanguageTag(tag).displayLanguage
        fun TextView?.setIfChanged(value: String) {
            if (this != null && text.toString() != value) text = value
        }
        status.setIfChanged(if (paused) "Paused" else phase)
        pair.setIfChanged("${name(prefs.source)} → ${name(prefs.target)}")
        pauseButton.setIfChanged(if (paused) "Resume" else "Pause")
        originalButton.setIfChanged(if (original) "Translated" else "Original")
        downloadButton?.apply {
            visibility = if (result.missingModels.isEmpty()) View.GONE else View.VISIBLE
            setIfChanged(if (download?.isActive == true) "Downloading…" else "Download languages")
            isEnabled = download?.isActive != true
        }
    }

    private fun addWindows() {
        updateCaptureInsets()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        labels =
            Labels().also { view ->
                view.visibility = View.INVISIBLE
                windows.addView(
                    view,
                    WindowManager.LayoutParams(
                            -1,
                            -1,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                            PixelFormat.TRANSLUCENT,
                        )
                        .apply {
                            title = "Glimpse translation labels"
                            alpha = TranslationLabelCover.WINDOW_ALPHA
                            setFitInsetsTypes(0)
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        },
                )
            }
        val themedContext = nativeThemeContext(this, prefs)
        val dockView =
            LinearLayout(themedContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(8))
                background =
                    GradientDrawable().apply {
                        setColor(this@ScreenTranslationService.colors.surface)
                        cornerRadius = dp(24).toFloat()
                    }
                elevation = dp(6).toFloat()
            }
        fun button(text: String, action: () -> Unit) =
            Button(themedContext).apply {
                this.text = text
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(48)
                setTextColor(colors.foreground)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { action() }
            }
        pair =
            button("Translation languages") {
                    startActivity(
                        Intent(this, CapturePermissionActivity::class.java)
                            .setAction(CapturePermissionActivity.LIVE_TRANSLATE)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                            ),
                    )
                }
                .also { dockView.addView(it, LinearLayout.LayoutParams(-1, dp(48))) }
        status =
            TextView(themedContext)
                .apply {
                    setTextColor(colors.secondary)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                .also { dockView.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        downloadButton =
            button("Download languages", ::downloadModels).also { dockView.addView(it) }
        val buttons = LinearLayout(this)
        originalButton = button("Original") { command(ORIGINAL) }
        pauseButton = button("Pause") { command(PAUSE) }
        stopButton = button("Stop") { stopSelf() }
        listOf(originalButton!!, pauseButton!!, stopButton!!).forEach {
            buttons.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        dockView.addView(buttons)
        controls = dockView
        applyDockColors()
        val params =
            WindowManager.LayoutParams(
                    minOf(windows.maximumWindowMetrics.bounds.width() - dp(32), dp(400)),
                    -2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    title = "Glimpse translation controls"
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(16)
                }
        dockView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val location = IntArray(2)
            dockView.getLocationOnScreen(location)
            dock =
                Rect(
                    location[0] - dp(8),
                    location[1] - dp(8),
                    location[0] + dockView.width + dp(8),
                    location[1] + dockView.height + dp(8),
                )
        }
        dockView.setOnApplyWindowInsetsListener { _, insets ->
            val safe =
                insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime())
            if (params.y != dp(16) + safe.bottom) {
                params.y = dp(16) + safe.bottom
                windows.updateViewLayout(dockView, params)
            }
            insets
        }
        windows.addView(dockView, params)
        updateControls()
    }

    private fun applyDockColors() {
        (controls?.background as? GradientDrawable)?.setColor(colors.surface)
        status?.setTextColor(colors.secondary)
        val text =
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(colors.disabled, colors.foreground),
            )
        listOf(pair, pauseButton, originalButton, downloadButton, stopButton).forEach { button ->
            button?.apply {
                setTextColor(text)
                backgroundTintList = null
                background =
                    RippleDrawable(
                        ColorStateList.valueOf(colors.ripple),
                        null,
                        GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = 20 * resources.displayMetrics.density
                        },
                    )
            }
        }
    }

    private fun command(action: String) {
        onStartCommand(Intent(this, javaClass).setAction(action), 0, 0)
    }

    private fun updateCaptureInsets() {
        val insets =
            windows.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        screenTop = insets.top
        screenBottom = insets.bottom
    }

    /** Capture owns Image lifetimes; UI receives immutable samples and detached bitmaps only. */
    private fun sample(image: Image, ignored: Rect): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val columns = 96
        val rows = (columns * image.height / image.width).coerceAtLeast(1)
        return IntArray(columns * rows) { index ->
            val x =
                ((index % columns + .5) * image.width / columns)
                    .toInt()
                    .coerceIn(0, image.width - 1)
            val y =
                ((index / columns + .5) * image.height / rows).toInt().coerceIn(0, image.height - 1)
            if (
                ignored.contains(x, y) ||
                    x < 20 ||
                    x >= image.width - 20 ||
                    y < maxOf(24, screenTop) ||
                    y >= image.height - maxOf(24, screenBottom)
            )
                -1
            else {
                val offset = y * plane.rowStride + x * plane.pixelStride
                val r = buffer.get(offset).toInt() and 255
                val g = buffer.get(offset + 1).toInt() and 255
                val b = buffer.get(offset + 2).toInt() and 255
                (r * 54 + g * 183 + b * 19) / 256
            }
        }
    }

    private fun copySource(image: Image): Bitmap {
        val plane = image.planes[0]
        val padded =
            Bitmap.createBitmap(
                plane.rowStride / plane.pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        // Keep native-resolution source pixels for covering the original glyphs. Only the OCR
        // input is downscaled, once the scene has settled.
        val clean = cropped
        if (padded !== clean) padded.recycle()
        return clean
    }

    private fun containsPresentation(image: Image): Boolean {
        val plane = image.planes[0]
        val pixels = plane.buffer
        fun near(x: Int, y: Int, color: Int): Boolean {
            val offset = y * plane.rowStride + x * plane.pixelStride
            return kotlin.math.abs((pixels.get(offset).toInt() and 255) - Color.red(color)) < 72 &&
                kotlin.math.abs((pixels.get(offset + 1).toInt() and 255) - Color.green(color)) <
                    72 &&
                kotlin.math.abs((pixels.get(offset + 2).toInt() and 255) - Color.blue(color)) < 72
        }
        if (image.width < 14 || image.height < 32) return false
        val y = image.height / 2
        val first = (y + 3) * plane.rowStride + 8 * plane.pixelStride
        val second = (y + 11) * plane.rowStride + 8 * plane.pixelStride
        return near(8, y + 3, MARKER_A) &&
            near(8, y + 11, MARKER_B) &&
            (pixels.get(second).toInt() and 255) - (pixels.get(first).toInt() and 255) > 80 &&
            (pixels.get(first + 1).toInt() and 255) - (pixels.get(second + 1).toInt() and 255) > 80
    }

    private inner class Labels : View(this@ScreenTranslationService) {
        private val glow = EdgeGlowRenderer(resources.displayMetrics.density)
        private val location = IntArray(2)
        private var boxes = emptyList<Label>()
        var source: Bitmap? = null
        var state = LiveState()
            set(value) {
                field = value
                if (value.recognition == null) source = null
                layoutLabels()
                invalidate()
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            layoutLabels()
        }

        fun withoutRing(sample: IntArray): IntArray {
            val rows = sample.size / 96
            if (rows == 0) return sample
            return sample.copyOf().apply {
                for (index in indices) {
                    val x = (index % 96 + .5f) * width / 96
                    val y = (index / 96 + .5f) * height / rows
                    if (
                        x < 32 * resources.displayMetrics.density ||
                            x > width - 32 * resources.displayMetrics.density ||
                            y < 32 * resources.displayMetrics.density ||
                            y > height - 32 * resources.displayMetrics.density
                    )
                        this[index] = -1
                }
            }
        }

        fun expectedSample(source: IntArray): IntArray {
            val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(overlay))
            val rows = source.size / 96
            val expected = withoutRing(source)
            for (index in expected.indices) {
                if (expected[index] < 0) continue
                val x = ((index % 96 + .5) * width / 96).toInt().coerceIn(0, width - 1)
                val y = ((index / 96 + .5) * height / rows).toInt().coerceIn(0, height - 1)
                val color = overlay.getPixel(x, y)
                val opacity = Color.alpha(color) / 255f * TranslationLabelCover.WINDOW_ALPHA
                val luminance =
                    (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) /
                        256
                expected[index] = (luminance * opacity + source[index] * (1 - opacity)).toInt()
            }
            overlay.recycle()
            return expected
        }

        fun changedUnderLabels(before: IntArray, after: IntArray): Boolean {
            if (before.size != after.size) return true
            val rows = before.size / 96
            return boxes.any { box ->
                var count = 0
                var changed = 0
                var difference = 0L
                for (index in before.indices) {
                    if (before[index] < 0 || after[index] < 0) continue
                    val x = (index % 96 + .5f) * width / 96
                    val y = (index / 96 + .5f) * height / rows
                    if (x < box.x || x > box.x + box.width || y < box.y || y > box.y + box.height)
                        continue
                    val delta = kotlin.math.abs(before[index] - after[index])
                    count++
                    difference += delta
                    if (delta >= 4) changed++
                }
                count >= 8 &&
                    changed >= 3 &&
                    (changed.toDouble() / count > .12 || difference.toDouble() / count > 3)
            }
        }

        private fun layoutLabels() {
            val recognition = state.recognition
            val clean = source
            if (
                recognition == null ||
                    clean == null ||
                    width == 0 ||
                    height == 0 ||
                    clean.width != width ||
                    clean.height != height
            ) {
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
                state.regions.mapNotNull { region ->
                    val box = region.bounds
                    val x =
                        (placement.left + box.left * placement.width).coerceIn(0f, width.toFloat())
                    val y =
                        (placement.top + box.top * placement.height).coerceIn(0f, height.toFloat())
                    val w =
                        (box.width * placement.width)
                            .coerceAtLeast(48 * density)
                            .coerceAtMost(width - x)
                    val available = (height - y - 4 * density).toInt()
                    if (w < 1 || available < 1) return@mapNotNull null
                    val paint =
                        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = TranslationLabelCover.readableColor(colors.foreground)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            textSize =
                                (box.height * placement.height * .74f).coerceIn(
                                    12 * density,
                                    26 * density,
                                )
                        }
                    val maxLines = (available / paint.fontSpacing).toInt().coerceIn(1, 5)
                    val layout =
                        StaticLayout.Builder.obtain(
                                region.translatedText,
                                0,
                                region.translatedText.length,
                                paint,
                                w.toInt(),
                            )
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setIncludePad(false)
                            .setMaxLines(maxLines)
                            .setEllipsize(TextUtils.TruncateAt.END)
                            .build()
                    val h =
                        maxOf(box.height * placement.height, layout.height.toFloat())
                            .coerceAtMost(available.toFloat())
                    val padding = 6 * density
                    val rect =
                        Rect(
                            kotlin.math.floor(x - padding).toInt().coerceAtLeast(0),
                            kotlin.math.floor(y - padding).toInt().coerceAtLeast(0),
                            kotlin.math.ceil(x + w + padding).toInt().coerceAtMost(width),
                            kotlin.math.ceil(y + h + padding).toInt().coerceAtMost(height),
                        )
                    val cover =
                        TranslationLabelCover.render(clean, rect) { target ->
                            target.drawRoundRect(
                                x - rect.left - padding,
                                y - rect.top - padding,
                                x + w - rect.left + padding,
                                y + h - rect.top + padding,
                                5 * density,
                                5 * density,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = TranslationLabelCover.readableColor(colors.surface)
                                },
                            )
                            target.save()
                            target.translate(x - rect.left, y - rect.top)
                            layout.draw(target)
                            target.restore()
                        }
                    Label(
                        x,
                        y,
                        w,
                        h,
                        rect,
                        cover,
                    )
                }
        }

        override fun onDraw(canvas: Canvas) {
            getLocationInWindow(location)
            glow.updateBounds(width, height, rootWindowInsets, location[0], location[1])
            glow.draw(canvas)
            boxes.forEach { box ->
                canvas.save()
                canvas.clipRect(0, 0, width, height)
                canvas.drawBitmap(box.cover, box.rect.left.toFloat(), box.rect.top.toFloat(), null)
                canvas.restore()
            }
            canvas.drawRect(
                4f,
                height / 2f,
                13f,
                height / 2f + 7,
                Paint().apply { color = MARKER_A },
            )
            canvas.drawRect(
                4f,
                height / 2f + 8,
                13f,
                height / 2f + 15,
                Paint().apply { color = MARKER_B },
            )
        }
    }

    private data class Label(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rect: Rect,
        val cover: Bitmap,
    )

    override fun onDestroy() {
        stopped = true
        scope.cancel()
        frames.close()
        latestClean = null
        if (::translator.isInitialized) translator.reset()
        if (::capture.isInitialized)
            capture.post {
                display?.release()
                display = null
                reader?.close()
                reader = null
                projection?.stop()
                captureThread.quitSafely()
            }
        else projection?.stop()
        if (::windows.isInitialized) {
            labels?.let { runCatching { windows.removeView(it) } }
            controls?.let { runCatching { windows.removeView(it) } }
        }
        ScreenTranslationSession.mutable.value = ScreenTranslationState()
        super.onDestroy()
    }

    companion object {
        const val START = "app.glimpse.screen.START"
        const val STOP = "stop"
        const val PAUSE = "app.glimpse.screen.PAUSE"
        const val ORIGINAL = "app.glimpse.screen.ORIGINAL"
        const val REFRESH = "app.glimpse.screen.REFRESH"
        const val DOWNLOAD = "app.glimpse.screen.DOWNLOAD"
        private const val BACKGROUND = 0xFF18231E.toInt()
        private const val MARKER_A = 0xFF00FF00.toInt()
        private const val MARKER_B = 0xFFFF00FF.toInt()
    }
}
