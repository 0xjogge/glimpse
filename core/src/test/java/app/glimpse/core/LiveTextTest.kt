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
    fun cachedTextDoesNotLeakAcrossLanguagesOrEnginesAndEvictionIsBounded() {
        val cache = TranslationCache(2)
        cache.put("first", "ja", "en", "こんにちは", "Hello")
        assertNull(cache.get("first", "ja", "fr", "こんにちは"))
        assertNull(cache.get("second", "ja", "en", "こんにちは"))
        cache.put("first", "ja", "en", "お茶", "Tea")
        assertEquals("Hello", cache.get("first", "ja", "en", "こんにちは"))
        cache.put("first", "ja", "en", "日本", "Japan")
        assertNull(cache.get("first", "ja", "en", "お茶"))
        cache.clear()
        assertNull(cache.get("first", "ja", "en", "こんにちは"))
    }
}
