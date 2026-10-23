package app.glimpse.core

import android.graphics.Bitmap
import java.io.Closeable

data class EngineInfo(val id: String, val name: String, val description: String, val local: Boolean)

enum class OcrScript {
    AUTO,
    LATIN,
    JAPANESE,
}

data class TextSpan(val text: String, val bounds: ImageRegion)

data class TextLine(
    val text: String,
    val bounds: ImageRegion,
    val language: String = "und",
    val spans: List<TextSpan> = emptyList(),
)

data class Recognition(val lines: List<TextLine>, val imageWidth: Int, val imageHeight: Int) {
    val text
        get() = lines.joinToString("\n") { it.text }

    fun textIn(selection: ImageRegion?): String {
        if (selection == null || selection == ImageRegion.Full) return text
        return lines
            .mapNotNull { line ->
                if (line.spans.isEmpty()) {
                    line.text.takeIf { selection.includesCenter(line.bounds) }
                } else {
                    val spans = line.spans.filter { selection.includesCenter(it.bounds) }
                    when {
                        spans.isEmpty() -> null
                        spans.size == line.spans.size -> line.text
                        else ->
                            spans.joinToString(if (line.language == "ja") "" else " ") { it.text }
                    }
                }
            }
            .joinToString("\n")
    }
}

interface OcrEngine : Closeable {
    val info: EngineInfo
    val scripts: Set<OcrScript>

    suspend fun recognize(image: Bitmap, script: OcrScript = OcrScript.AUTO): Recognition
}

data class Language(val tag: String, val name: String)

data class Translation(
    val text: String,
    val source: String,
    val target: String,
    val engineId: String,
)

data class LanguageModel(val language: Language, val installed: Boolean)

class ModelRequiredException(val languages: Set<String>) :
    IllegalStateException("Download the offline language models first")

interface TranslationEngine : Closeable {
    val info: EngineInfo
    val languages: List<Language>

    suspend fun identify(text: String): String

    suspend fun models(): List<LanguageModel>

    suspend fun download(language: String, wifiOnly: Boolean = true)

    suspend fun delete(language: String)

    suspend fun translate(text: String, source: String, target: String): Translation
}

/** Registry injection is the only place the application chooses concrete backends. */
class EngineRegistry(val ocr: List<OcrEngine>, val translation: List<TranslationEngine>) :
    Closeable {
    init {
        require(ocr.isNotEmpty() && translation.isNotEmpty())
        require(ocr.map { it.info.id }.distinct().size == ocr.size)
        require(translation.map { it.info.id }.distinct().size == translation.size)
    }

    fun ocr(id: String) = ocr.firstOrNull { it.info.id == id } ?: ocr.first()

    fun translator(id: String) = translation.firstOrNull { it.info.id == id } ?: translation.first()

    override fun close() {
        ocr.forEach(Closeable::close)
        translation.forEach(Closeable::close)
    }
}
