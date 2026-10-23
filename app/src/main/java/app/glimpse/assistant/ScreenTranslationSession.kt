package app.glimpse.assistant

import app.glimpse.core.TranslatedRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable controls only: projection grants and captured bitmaps remain service-owned. */
data class ScreenTranslationState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val original: Boolean = false,
    val status: String = "Stopped",
    val source: String = "auto",
    val target: String = "en",
    val missingModels: Set<String> = emptySet(),
    val downloading: Set<String> = emptySet(),
    val regions: List<TranslatedRegion> = emptyList(),
    val sourceText: String = "",
    val cleanFrames: Long = 0,
    val rejectedFrames: Long = 0,
    val scene: Long = 0,
)

object ScreenTranslationSession {
    internal val mutable = MutableStateFlow(ScreenTranslationState())
    val state = mutable.asStateFlow()
}
