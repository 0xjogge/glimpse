package app.glimpse.engines.mlkit

import app.glimpse.core.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class MlKitTranslation : TranslationEngine {
    override val info =
        EngineInfo(
            "mlkit-translation",
            "ML Kit",
            "Offline translation with downloadable language packs. English and Japanese are recommended first.",
            true,
        )
    override val languages =
        TranslateLanguage.getAllLanguages()
            .map { Language(it, Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH)) }
            .sortedBy { it.name }
    private val identifier by lazy { LanguageIdentification.getClient() }
    private val manager = RemoteModelManager.getInstance()
    private val mutex = Mutex()
    private val translators = LinkedHashMap<Pair<String, String>, Translator>()

    override suspend fun identify(text: String): String {
        // Short Japanese strings can otherwise be classified as undetermined or Chinese.
        if (text.any { it in '\u3040'..'\u30ff' }) return "ja"
        return identifier.identifyLanguage(text).await()
    }

    override suspend fun models(): List<LanguageModel> {
        val downloaded =
            manager
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
                .map { it.language }
                .toSet()
        return languages.map { LanguageModel(it, it.tag == "en" || it.tag in downloaded) }
    }

    override suspend fun download(language: String, wifiOnly: Boolean) {
        require(languages.any { it.tag == language })
        if (language == "en") return
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        manager.download(TranslateRemoteModel.Builder(language).build(), conditions).await()
    }

    override suspend fun delete(language: String) = mutex.withLock {
        translators.values.forEach(Translator::close)
        translators.clear()
        if (language != "en")
            manager.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await()
    }

    override suspend fun translate(text: String, source: String, target: String): Translation =
        mutex.withLock {
            require(languages.any { it.tag == source } && languages.any { it.tag == target }) {
                "This language is not supported by the selected engine"
            }
            if (source == target) return@withLock Translation(text, source, target, info.id)
            val missing =
                models()
                    .filter { it.language.tag in setOf(source, target) && !it.installed }
                    .map { it.language.tag }
                    .toSet()
            if (missing.isNotEmpty()) throw ModelRequiredException(missing)
            val translator =
                translators.getOrPut(source to target) {
                    com.google.mlkit.nl.translate.Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(target)
                            .build(),
                    )
                }
            // Keep a small warm set without retaining every language pair indefinitely.
            if (translators.size > 3) {
                val oldest = translators.keys.first()
                translators.remove(oldest)?.close()
            }
            Translation(translator.translate(text).await(), source, target, info.id)
        }

    override fun close() {
        translators.values.forEach(Translator::close)
        identifier.close()
    }
}
