package app.glimpse.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.glimpse.core.*
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("glimpse")

data class AppPreferences(
    val ocrEngine: String = "mlkit-ocr",
    val translationEngine: String = "mlkit-translation",
    val script: OcrScript = OcrScript.AUTO,
    val source: String = "auto",
    val target: String = "en",
    val search: String = "google",
    val browserTranslator: BrowserTranslator = BrowserTranslator.GOOGLE,
    val theme: String = "system",
    val dynamicColor: Boolean = true,
    val wifiOnly: Boolean = true,
    val customProviders: List<TemplateSearchProvider> = emptyList(),
) {
    val providers
        get() = SearchProviders.builtIn + customProviders
}

class Preferences(context: Context) {
    private val store = context.dataStore
    val values =
        store.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { p ->
                AppPreferences(
                    ocrEngine = p[stringPreferencesKey("ocrEngine")] ?: "mlkit-ocr",
                    translationEngine =
                        p[stringPreferencesKey("translationEngine")] ?: "mlkit-translation",
                    script =
                        OcrScript.entries.firstOrNull {
                            it.name == p[stringPreferencesKey("script")]
                        } ?: OcrScript.AUTO,
                    source = p[stringPreferencesKey("source")] ?: "auto",
                    target = p[stringPreferencesKey("target")] ?: "en",
                    search = p[stringPreferencesKey("search")] ?: "google",
                    browserTranslator =
                        BrowserTranslator.entries.firstOrNull {
                            it.name == p[stringPreferencesKey("browserTranslator")]
                        } ?: BrowserTranslator.GOOGLE,
                    theme = p[stringPreferencesKey("theme")] ?: "system",
                    dynamicColor = p[booleanPreferencesKey("dynamicColor")] ?: true,
                    wifiOnly = p[booleanPreferencesKey("wifiOnly")] ?: true,
                    customProviders =
                        decodeProviders(p[stringPreferencesKey("customProviders")] ?: "[]"),
                )
            }

    suspend fun set(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun set(key: String, value: Boolean) {
        store.edit { it[booleanPreferencesKey(key)] = value }
    }

    suspend fun addProvider(provider: TemplateSearchProvider) {
        store.edit { p ->
            val providers = decodeProviders(p[stringPreferencesKey("customProviders")] ?: "[]")
            require(providers.size < 20) { "Remove an engine before adding another" }
            p[stringPreferencesKey("customProviders")] = encodeProviders(providers + provider)
        }
    }

    suspend fun removeProvider(id: String) {
        store.edit { p ->
            p[stringPreferencesKey("customProviders")] =
                encodeProviders(
                    decodeProviders(p[stringPreferencesKey("customProviders")] ?: "[]").filter {
                        it.id != id
                    },
                )
            if (p[stringPreferencesKey("search")] == id)
                p[stringPreferencesKey("search")] = "google"
        }
    }

    private fun encodeProviders(providers: List<TemplateSearchProvider>): String =
        JSONArray()
            .apply {
                providers.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("template", it.template),
                    )
                }
            }
            .toString()

    private fun decodeProviders(json: String): List<TemplateSearchProvider> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                TemplateSearchProvider(
                    item.getString("id"),
                    item.getString("name"),
                    item.getString("template"),
                )
            }
                .getOrNull()
        }
    }
        .getOrDefault(emptyList())
}
