package app.glimpse.core

import org.junit.Assert.*
import org.junit.Test

class SelectionTest {
    @Test
    fun selectionMapsThroughPortraitLetterboxing() {
        val p = ImagePlacement.fit(1000, 2000, 800f, 800f)
        assertEquals(200f, p.left, .001f)
        assertEquals(400f, p.width, .001f)
        assertEquals(.5f, p.imageX(400f), .001f)
        assertFalse(p.contains(100f, 400f))
        assertEquals(0f, p.imageX(-20f), .001f)
    }

    @Test
    fun reversedDragIsNormalizedAndClamped() {
        assertEquals(ImageRegion(0f, .2f, .9f, 1f), ImageRegion.between(.9f, 2f, -.2f, .2f))
        assertNull(ImageRegion.between(.2f, .2f, .2f, .8f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCoordinatesAreRejected() {
        ImageRegion(Float.NaN, 0f, 1f, 1f)
    }

    @Test
    fun partialLineSelectionCopiesOnlySelectedWords() {
        val document =
            Recognition(
                listOf(
                    TextLine(
                        "Hello world",
                        ImageRegion(.1f, .1f, .9f, .2f),
                        "en",
                        listOf(
                            TextSpan("Hello", ImageRegion(.1f, .1f, .4f, .2f)),
                            TextSpan("world", ImageRegion(.5f, .1f, .9f, .2f)),
                        ),
                    ),
                ),
                1000,
                1000,
            )
        assertEquals("world", document.textIn(ImageRegion(.45f, 0f, 1f, .4f)))
        assertEquals("Hello world", document.textIn(null))
        assertEquals("", document.textIn(ImageRegion(0f, .5f, 1f, 1f)))
    }

    @Test
    fun japaneseSelectionDoesNotInsertWesternSpaces() {
        val document =
            Recognition(
                listOf(
                    TextLine(
                        "日本へようこそ",
                        ImageRegion(0f, 0f, 1f, .2f),
                        "ja",
                        listOf(
                            TextSpan("日本", ImageRegion(0f, 0f, .25f, .2f)),
                            TextSpan("へ", ImageRegion(.3f, 0f, .4f, .2f)),
                            TextSpan("ようこそ", ImageRegion(.5f, 0f, 1f, .2f)),
                        ),
                    ),
                ),
                1000,
                1000,
            )
        assertEquals("日本へ", document.textIn(ImageRegion(0f, 0f, .45f, .3f)))
    }
}
