package app.glimpse.core

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Templates are data; providers requiring native/API integration can implement this interface. */
interface SearchProvider {
    val id: String
    val name: String
    val imageUploadUrl: String?

    fun textUrl(query: String): String
}

data class TemplateSearchProvider(
    override val id: String,
    override val name: String,
    val template: String,
    override val imageUploadUrl: String? = null,
) : SearchProvider {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(template.contains("{query}")) { "The URL must contain {query}" }
        requireSecureUrl(template.replace("{query}", "test"))
        imageUploadUrl?.let(::requireSecureUrl)
    }

    override fun textUrl(query: String): String {
        require(query.isNotBlank()) { "Select some text first" }
        return template.replace("{query}", encodeQuery(query))
    }
}

fun requireSecureUrl(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "Use an HTTPS URL"
    }
}

fun encodeQuery(text: String): String =
    URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20")

object SearchProviders {
    val builtIn: List<TemplateSearchProvider> =
        listOf(
            TemplateSearchProvider(
                "google",
                "Google",
                "https://www.google.com/search?q={query}",
                "https://www.google.com/imghp",
            ),
            TemplateSearchProvider(
                "bing",
                "Bing",
                "https://www.bing.com/search?q={query}",
                "https://www.bing.com/visualsearch",
            ),
            TemplateSearchProvider("kagi", "Kagi", "https://kagi.com/search?q={query}"),
        )
}

enum class BrowserTranslator(val title: String) {
    GOOGLE("Google Translate"),
    KAGI("Kagi Translate");

    fun url(text: String, source: String, target: String): String =
        when (this) {
            GOOGLE ->
                "https://translate.google.com/?sl=${encodeQuery(source)}&tl=${encodeQuery(target)}&text=${encodeQuery(text)}&op=translate"
            KAGI ->
                "https://translate.kagi.com/?source=${encodeQuery(source)}&target=${encodeQuery(target)}&text=${encodeQuery(text)}"
        }
}
