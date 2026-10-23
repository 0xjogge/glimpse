package app.glimpse.assistant

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets

/** A stationary, non-interactive ring. Add as a full-size child of an existing overlay window. */
class EdgeGlowView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    val renderer = EdgeGlowRenderer(resources.displayMetrics.density)
    private val location = IntArray(2)

    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            renderer.busy = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setColors(first: Int, second: Int) {
        renderer.setColors(first, second)
        invalidate()
    }

    fun setContentAlpha(value: Float) {
        alpha = value.coerceIn(0f, 1f)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationInWindow(location)
        renderer.updateBounds(width, height, rootWindowInsets, location[0], location[1])
        renderer.draw(canvas)
    }
}

/** Canvas API for an existing label view; the RenderEffect affects only the recorded halo. */
class EdgeGlowRenderer(private val density: Float) {
    private val path = Path()
    private val bounds = RectF()
    private val radii = FloatArray(8)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = RenderNode("Glimpse edge halo")
    private var first = Color.rgb(102, 226, 206)
    private var second = Color.rgb(185, 149, 255)
    private var width = 0
    private var height = 0
    private var dirty = true

    var busy: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            dirty = true
        }

    init {
        core.strokeWidth = 1.6f * density
        halo.strokeWidth = 4f * density
        glow.setRenderEffect(
            RenderEffect.createBlurEffect(2.5f * density, 2.5f * density, Shader.TileMode.DECAL),
        )
    }

    fun setColors(first: Int, second: Int) {
        if (this.first == first && this.second == second) return
        this.first = first
        this.second = second
        dirty = true
    }

    /** Insets corner centers are window-relative; offsets locate this view in that window. */
    fun updateBounds(
        width: Int,
        height: Int,
        insets: WindowInsets?,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ) {
        val inset = 11f * density // Includes the halo's ~3-sigma blur footprint and half stroke.
        if (width <= inset * 2 || height <= inset * 2) {
            this.width = 0
            this.height = 0
            path.reset()
            dirty = true
            return
        }
        val next = FloatArray(8)
        val positions =
            intArrayOf(
                RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT,
            )
        positions.forEachIndexed { index, position ->
            val corner = insets?.getRoundedCorner(position)
            val left = index == 0 || index == 3
            val top = index < 2
            val fallback = (28f * density - inset).coerceAtLeast(0f)
            next[index * 2] =
                (corner?.let {
                        val center = it.center.x - offsetX
                        if (left) center - inset else width - inset - center
                    } ?: fallback)
                    .coerceIn(0f, (width - inset * 2) / 2)
            next[index * 2 + 1] =
                (corner?.let {
                        val center = it.center.y - offsetY
                        if (top) center - inset else height - inset - center
                    } ?: fallback)
                    .coerceIn(0f, (height - inset * 2) / 2)
        }
        if (this.width == width && this.height == height && radii.contentEquals(next)) return
        this.width = width
        this.height = height
        next.copyInto(radii)
        bounds.set(inset, inset, width - inset, height - inset)
        path.reset()
        path.addRoundRect(bounds, radii, Path.Direction.CW)
        glow.setPosition(0, 0, width, height)
        dirty = true
    }

    fun draw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (dirty) {
            val gradient =
                LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    intArrayOf(first, second, first),
                    null,
                    Shader.TileMode.CLAMP,
                )
            core.shader = gradient
            core.alpha = 225
            halo.shader = gradient
            halo.alpha = if (busy) 210 else 155
            val recording = glow.beginRecording(width, height)
            recording.drawPath(path, halo)
            glow.endRecording()
            dirty = false
        }
        if (canvas.isHardwareAccelerated) canvas.drawRenderNode(glow)
        else {
            halo.maskFilter = BlurMaskFilter(2.5f * density, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(path, halo)
            halo.maskFilter = null
        }
        canvas.drawPath(path, core)
    }
}
