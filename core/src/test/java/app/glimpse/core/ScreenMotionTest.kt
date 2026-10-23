package app.glimpse.core

import org.junit.Assert.*
import org.junit.Test

class ScreenMotionTest {
    @Test
    fun detectsScrollingEvenThroughTranslucentLabels() {
        val before = IntArray(1000) { 160 }
        val after = before.copyOf().apply { repeat(40) { this[it] = 168 } }
        assertTrue(ScreenMotion.changed(before, after))
    }

    @Test
    fun clockAndIgnoredDockDoNotContinuouslyRetranslateAStillPage() {
        val before = IntArray(1000) { if (it < 100) -1 else 160 }
        val after =
            before.copyOf().apply {
                repeat(100) { this[it] = 240 }
                this[500] = 0
            }
        assertFalse(ScreenMotion.changed(before, after))
    }

    @Test
    fun comparesAgainstSceneBaselineSoSlowMovementAccumulates() {
        val baseline = IntArray(1000) { 120 }
        assertFalse(ScreenMotion.changed(baseline, IntArray(1000) { 121 }))
        assertTrue(ScreenMotion.changed(baseline, IntArray(1000) { 122 }))
    }
}
