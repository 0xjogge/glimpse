package app.glimpse.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Android limits a touch-through application-overlay window to .8 opacity. A plain background
 * therefore leaves the original glyphs visible. Repaint each label against its clean source:
 * overlay = (desired - (1 - windowAlpha) * source) / windowAlpha.
 *
 * Restrict desired colors to the attainable range so no channel clips. This produces a uniform
 * background and high-contrast translated glyphs after composition, while keeping native scrolling.
 * Source pixels and presentation geometry must belong to the same settled scene.
 */
internal object TranslationLabelCover {
    const val WINDOW_ALPHA = .8f

    fun readableColor(color: Int): Int =
        Color.rgb(
            Color.red(color).coerceIn(52, 203),
            Color.green(color).coerceIn(52, 203),
            Color.blue(color).coerceIn(52, 203),
        )

    fun render(source: Bitmap, bounds: Rect, drawDesired: (Canvas) -> Unit): Bitmap {
        require(bounds.width() > 0 && bounds.height() > 0)
        require(
            bounds.left >= 0 &&
                bounds.top >= 0 &&
                bounds.right <= source.width &&
                bounds.bottom <= source.height,
        )
        val result = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        drawDesired(Canvas(result))
        val desired = IntArray(bounds.width() * bounds.height())
        val original = IntArray(desired.size)
        result.getPixels(desired, 0, bounds.width(), 0, 0, bounds.width(), bounds.height())
        source.getPixels(
            original,
            0,
            bounds.width(),
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height(),
        )
        for (index in desired.indices) {
            val pixel = desired[index]
            val alpha = Color.alpha(pixel)
            if (alpha == 0) continue
            val background = original[index]
            fun channel(target: Int, source: Int) =
                ((target - (1 - WINDOW_ALPHA) * source) / WINDOW_ALPHA)
                    .roundToInt()
                    .coerceIn(0, 255)
            desired[index] =
                Color.argb(
                    alpha,
                    channel(Color.red(pixel), Color.red(background)),
                    channel(Color.green(pixel), Color.green(background)),
                    channel(Color.blue(pixel), Color.blue(background)),
                )
        }
        result.setPixels(desired, 0, bounds.width(), 0, 0, bounds.width(), bounds.height())
        return result
    }
}
