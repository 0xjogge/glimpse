package app.glimpse.core

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    @Test
    fun allBuiltInsKeepJapaneseAndQueryPunctuationIntact() {
        val text = "コーヒー & tea + coffee? #朝"
        SearchProviders.builtIn.forEach { provider ->
            val query = URI(provider.textUrl(text)).rawQuery.substringAfter("q=")
            assertEquals(text, URLDecoder.decode(query, "UTF-8"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun javascriptTemplatesAreRejected() {
        TemplateSearchProvider("bad", "Bad", "javascript:alert('{query}')")
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingPlaceholderIsRejected() {
        TemplateSearchProvider("bad", "Bad", "https://example.com/search")
    }

    @Test
    fun customAiProviderNeedsNoUiChanges() {
        val provider: SearchProvider =
            TemplateSearchProvider("ai", "AI", "https://example.com/ask?q={query}")
        assertEquals("https://example.com/ask?q=hello%20world", provider.textUrl("hello world"))
        assertNull(provider.imageUploadUrl)
    }

    @Test
    fun imageUploadCapabilityIsSeparateFromTextSearch() {
        assertEquals(
            setOf("google", "bing"),
            SearchProviders.builtIn.filter { it.imageUploadUrl != null }.map { it.id }.toSet(),
        )
        assertTrue(BrowserTranslator.GOOGLE.url("お茶 & coffee", "ja", "en").contains("%26"))
    }
}
