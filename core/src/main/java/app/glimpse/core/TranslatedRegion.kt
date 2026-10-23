package app.glimpse.core

/** A translated, eligible occurrence in the current frame, not a string-to-box lookup. */
data class TranslatedRegion(val source: TextLine, val translatedText: String) {
    val bounds: ImageRegion
        get() = source.bounds

    val sourceText: String
        get() = source.text
}
