package app.glimpse.core

import org.junit.Assert.*
import org.junit.Test

class LiveTextTest {
    private val line = TextLine("こんにちは", ImageRegion(.1f, .1f, .9f, .2f), "ja")

    @Test
    fun transientTextDoesNotTriggerTranslationAndExpiredTextMustStabilizeAgain() {
        val stabilizer = LiveTextStabilizer()
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
        assertEquals(listOf(line), stabilizer.update(listOf(line)))
        assertTrue(stabilizer.update(emptyList()).isEmpty())
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
        stabilizer.reset()
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
    }

    @Test
    fun duplicatesInOneFrameDoNotCountAsRepeatedFrames() {
        assertTrue(LiveTextStabilizer().update(listOf(line, line)).isEmpty())
    }

    @Test
    fun nearbyDuplicateCannotReuseAnAlreadyMatchedOccurrence() {
        val stabilizer = LiveTextStabilizer()
        val duplicate = line.copy(bounds = ImageRegion(.11f, .11f, .91f, .21f))
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
        assertEquals(listOf(line), stabilizer.update(listOf(line)))
        assertEquals(listOf(line), stabilizer.update(listOf(line, duplicate)))
        assertEquals(listOf(line, duplicate), stabilizer.update(listOf(line, duplicate)))
    }

    @Test
    fun reorderedNewDuplicateCannotStealAnExistingOccurrencesExactGeometry() {
        val stabilizer = LiveTextStabilizer()
        val duplicate = line.copy(bounds = ImageRegion(.11f, .11f, .91f, .21f))
        stabilizer.update(listOf(line))
        stabilizer.update(listOf(line))
        assertEquals(listOf(line), stabilizer.update(listOf(duplicate, line)))
        assertEquals(listOf(duplicate, line), stabilizer.update(listOf(duplicate, line)))
    }

    @Test
    fun identicalOccurrencesEachNeedTheirOwnPreviousMatch() {
        val stabilizer = LiveTextStabilizer()
        stabilizer.update(listOf(line))
        assertEquals(listOf(line), stabilizer.update(listOf(line, line)))
        assertEquals(listOf(line, line), stabilizer.update(listOf(line, line)))
    }

    @Test
    fun smallGeometryJitterAndTrimmedTextKeepStabilityButTransientMovesDoNot() {
        val stabilizer = LiveTextStabilizer()
        val jittered =
            line.copy(
                text = "  ${line.text}  ",
                bounds = ImageRegion(.11f, .11f, .91f, .21f),
            )
        val moved = line.copy(bounds = ImageRegion(.1f, .4f, .9f, .5f))
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
        assertEquals(listOf(jittered), stabilizer.update(listOf(jittered)))
        assertTrue(stabilizer.update(listOf(moved)).isEmpty())
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
        assertEquals(listOf(line), stabilizer.update(listOf(line)))
    }

    @Test
    fun settledSingleFrameSeedsStabilityWithoutStabilizingNewOccurrences() {
        val stabilizer = LiveTextStabilizer()
        val duplicate = line.copy(bounds = ImageRegion(.11f, .11f, .91f, .21f))
        assertEquals(listOf(line), stabilizer.update(listOf(line), settled = true))
        assertEquals(listOf(line), stabilizer.update(listOf(line, duplicate)))
        assertEquals(listOf(line, duplicate), stabilizer.update(listOf(line, duplicate)))
        assertTrue(stabilizer.update(emptyList(), settled = true).isEmpty())
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
        stabilizer.update(listOf(line), settled = true)
        stabilizer.reset()
        assertTrue(stabilizer.update(listOf(line)).isEmpty())
    }

    @Test
    fun settledFrameAlsoSeedsConfiguredRepetitionThreshold() {
        val stabilizer = LiveTextStabilizer(repetitions = 3)
        assertEquals(listOf(line), stabilizer.update(listOf(line), settled = true))
        assertEquals(listOf(line), stabilizer.update(listOf(line)))
    }

    @Test
    fun scrollingTheSameTextClearsItsOldOverlayUntilTheNewPositionStabilizes() {
        val stabilizer = LiveTextStabilizer()
        stabilizer.update(listOf(line))
        assertEquals(listOf(line), stabilizer.update(listOf(line)))
        val scrolled = line.copy(bounds = ImageRegion(.1f, .5f, .9f, .6f))
        assertTrue(stabilizer.update(listOf(scrolled)).isEmpty())
        assertEquals(listOf(scrolled), stabilizer.update(listOf(scrolled)))
    }

    @Test
    fun cachedTextDoesNotLeakAcrossLanguagesOrEnginesAndEvictionIsBounded() {
        val cache = TranslationCache(2)
        cache.put("first", "ja", "en", "こんにちは", "Hello")
        assertNull(cache.get("first", "ko", "en", "こんにちは"))
        assertNull(cache.get("first", "ja", "fr", "こんにちは"))
        assertNull(cache.get("second", "ja", "en", "こんにちは"))
        cache.put("first", "ja", "en", "お茶", "Tea")
        assertEquals("Hello", cache.get("first", "ja", "en", "こんにちは"))
        cache.put("first", "ja", "en", "日本", "Japan")
        assertNull(cache.get("first", "ja", "en", "お茶"))
        cache.clear()
        assertNull(cache.get("first", "ja", "en", "こんにちは"))
    }

    @Test
    fun cacheKeysDoNotCollideWhenFieldsContainTheOldDelimiter() {
        val cache = TranslationCache()
        cache.put("engine\u0000ja", "en", "fr", "text", "first")
        cache.put("engine", "ja", "en", "fr\u0000text", "second")
        assertEquals("first", cache.get("engine\u0000ja", "en", "fr", "text"))
        assertEquals("second", cache.get("engine", "ja", "en", "fr\u0000text"))
    }
}
