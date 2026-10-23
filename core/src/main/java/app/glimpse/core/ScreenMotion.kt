package app.glimpse.core

import kotlin.math.abs

/**
 * Pixel motion, including beneath translucent labels. Small isolated clock/cursor changes expire.
 */
object ScreenMotion {
    fun changed(previous: IntArray, current: IntArray): Boolean {
        if (previous.size != current.size || previous.isEmpty()) return true
        var total = 0L
        var changed = 0
        var observed = 0
        for (index in current.indices) {
            if (previous[index] < 0 || current[index] < 0) continue
            val difference = abs(previous[index] - current[index])
            total += difference
            if (difference >= 4) changed++
            observed++
        }
        if (observed == 0) return true
        // The .75-opacity presentation still exposes a quarter of source movement. Do not mask
        // translated rectangles: doing so would make a scrolling translated subpanel invisible.
        return total.toDouble() / observed > 1.4 || changed.toDouble() / observed > .018
    }
}
