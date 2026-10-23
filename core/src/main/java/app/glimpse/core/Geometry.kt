package app.glimpse.core

import kotlin.math.max
import kotlin.math.min

/** All engine results use upright image-relative coordinates, independent of the UI. */
data class ImageRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(right > left && bottom > top)
    }

    val width
        get() = right - left

    val height
        get() = bottom - top

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun includesCenter(other: ImageRegion) =
        contains((other.left + other.right) / 2, (other.top + other.bottom) / 2)

    companion object {
        val Full = ImageRegion(0f, 0f, 1f, 1f)

        fun between(x1: Float, y1: Float, x2: Float, y2: Float): ImageRegion? {
            val left = min(x1, x2).coerceIn(0f, 1f)
            val top = min(y1, y2).coerceIn(0f, 1f)
            val right = max(x1, x2).coerceIn(0f, 1f)
            val bottom = max(y1, y2).coerceIn(0f, 1f)
            return if (right - left > .002f && bottom - top > .002f)
                ImageRegion(left, top, right, bottom)
            else null
        }
    }
}

data class ImagePlacement(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun imageX(x: Float) = ((x - left) / width).coerceIn(0f, 1f)

    fun imageY(y: Float) = ((y - top) / height).coerceIn(0f, 1f)

    fun contains(x: Float, y: Float) = x in left..(left + width) && y in top..(top + height)

    companion object {
        fun fit(
            imageWidth: Int,
            imageHeight: Int,
            viewWidth: Float,
            viewHeight: Float,
        ): ImagePlacement {
            require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
            val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
            val width = imageWidth * scale
            val height = imageHeight * scale
            return ImagePlacement((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
        }
    }
}
