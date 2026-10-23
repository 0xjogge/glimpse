package app.glimpse

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.glimpse.core.*
import app.glimpse.data.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Page {
    HOME,
    WORKSPACE,
    CAMERA,
    SETTINGS,
    MODELS,
}

data class WorkspaceState(
    val page: Page = Page.HOME,
    val image: CapturedImage? = null,
    val recognition: Recognition? = null,
    val selection: ImageRegion? = null,
    val text: String = "",
    val translation: Translation? = null,
    val translatedLines: Map<String, String> = emptyMap(),
    val overlay: Boolean = false,
    val reading: Boolean = false,
    val translating: Boolean = false,
    val message: String? = null,
    val missingModels: Set<String> = emptySet(),
    val models: List<LanguageModel> = emptyList(),
    val downloading: Set<String> = emptySet(),
)

data class LiveState(
    val recognition: Recognition? = null,
    val translations: Map<String, String> = emptyMap(),
    val status: String = "Point at English or Japanese text",
)

class GlimpseViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as GlimpseApplication).graph
    val preferences =
        graph.preferences.values.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    private val mutableState = MutableStateFlow(WorkspaceState())
    val state = mutableState.asStateFlow()
    private val mutableLive = MutableStateFlow(LiveState())
    val live = mutableLive.asStateFlow()
    private var recognitionJob: Job? = null
    private var translationJob: Job? = null
    private val downloadJobs = mutableMapOf<String, Job>()
    private val cache = TranslationCache()
    private val stabilizer = LiveTextStabilizer()
    private val liveGeneration = AtomicLong()

    init {
        viewModelScope.launch {
            ImageSession.image.filterNotNull().collect { image ->
                recognitionJob?.cancel()
                translationJob?.cancel()
                mutableState.update {
                    it.copy(
                        page = Page.WORKSPACE,
                        image = image,
                        recognition = null,
                        selection = null,
                        text = "",
                        translation = null,
                        translatedLines = emptyMap(),
                        overlay = false,
                        missingModels = emptySet(),
                    )
                }
                recognize()
            }
        }
        viewModelScope.launch {
            preferences
                .map { it.ocrEngine to it.script }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (state.value.image != null) recognize() }
        }
        viewModelScope.launch {
            preferences
                .map { Triple(it.source, it.target, it.translationEngine) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    translationJob?.cancel()
                    cache.clear()
                    mutableState.update {
                        it.copy(
                            translation = null,
                            translatedLines = emptyMap(),
                            translating = false,
                            overlay = false,
                            missingModels = emptySet(),
                        )
                    }
                }
        }
    }

    fun page(page: Page) {
        mutableState.update { it.copy(page = page) }
        if (page == Page.MODELS) refreshModels()
    }

    fun home() {
        recognitionJob?.cancel()
        translationJob?.cancel()
        ImageSession.clear()
        mutableState.update {
            it.copy(
                page = Page.HOME,
                image = null,
                recognition = null,
                selection = null,
                text = "",
                translation = null,
                translatedLines = emptyMap(),
                overlay = false,
                reading = false,
                translating = false,
                missingModels = emptySet(),
            )
        }
    }

    fun load(uri: Uri) = work {
        mutableState.update { it.copy(reading = true) }
        try {
            ImageSession.offer(Images.decode(getApplication(), uri), "Imported image")
        } finally {
            mutableState.update { it.copy(reading = false) }
        }
    }

    fun sample() = work {
        ImageSession.offer(withContext(Dispatchers.Default) { Images.sample() }, "Example image")
    }

    fun receiveText(text: String) {
        home()
        mutableState.update { it.copy(page = Page.WORKSPACE, text = text.take(50_000)) }
    }

    fun recognize() {
        recognitionJob?.cancel()
        val image = state.value.image ?: return
        val prefs = preferences.value
        recognitionJob = work {
            mutableState.update { it.copy(reading = true, recognition = null) }
            try {
                val result =
                    withContext(Dispatchers.Default) {
                        graph.engines.ocr(prefs.ocrEngine).recognize(image.bitmap, prefs.script)
                    }
                mutableState.update {
                    it.copy(recognition = result, text = result.textIn(it.selection))
                }
            } finally {
                mutableState.update { it.copy(reading = false) }
            }
        }
    }

    fun select(region: ImageRegion?) {
        translationJob?.cancel()
        mutableState.update {
            it.copy(
                selection = region,
                text = it.recognition?.textIn(region).orEmpty(),
                translation = null,
                translatedLines = emptyMap(),
                overlay = false,
                translating = false,
                missingModels = emptySet(),
            )
        }
    }

    fun editText(text: String) {
        translationJob?.cancel()
        mutableState.update {
            it.copy(
                text = text,
                translation = null,
                translatedLines = emptyMap(),
                overlay = false,
                translating = false,
            )
        }
    }

    fun toggleOverlay() {
        mutableState.update { it.copy(overlay = !it.overlay) }
    }

    private suspend fun source(
        engine: TranslationEngine,
        text: String,
        prefs: AppPreferences,
    ): String {
        if (prefs.source != "auto") return prefs.source
        val identified = engine.identify(text)
        return if (identified == "und") {
            if (text.any { it in '\u4e00'..'\u9fff' }) "ja" else "en"
        } else identified
    }

    private suspend fun translated(
        engine: TranslationEngine,
        text: String,
        source: String,
        target: String,
    ): String {
        cache.get(engine.info.id, source, target, text)?.let {
            return it
        }
        return engine.translate(text, source, target).text.also {
            cache.put(engine.info.id, source, target, text, it)
        }
    }

    fun translate() {
        translationJob?.cancel()
        val snapshot = state.value
        if (snapshot.text.isBlank()) {
            message("Select or enter text to translate")
            return
        }
        val prefs = preferences.value
        translationJob = viewModelScope.launch {
            mutableState.update { it.copy(translating = true, missingModels = emptySet()) }
            try {
                val engine = graph.engines.translator(prefs.translationEngine)
                val source = source(engine, snapshot.text, prefs)
                val result = translated(engine, snapshot.text, source, prefs.target)
                mutableState.update {
                    it.copy(translation = Translation(result, source, prefs.target, engine.info.id))
                }
                val recognition = snapshot.recognition
                val lines =
                    if (
                        recognition != null &&
                            recognition.textIn(snapshot.selection) == snapshot.text
                    )
                        recognition.lines.filter {
                            snapshot.selection == null ||
                                snapshot.selection.includesCenter(it.bounds)
                        }
                    else emptyList()
                val overlays = mutableMapOf<String, String>()
                for (line in lines) {
                    ensureActive()
                    overlays[line.text] = translated(engine, line.text, source, prefs.target)
                    mutableState.update {
                        it.copy(translatedLines = overlays.toMap(), overlay = true)
                    }
                }
            } catch (e: ModelRequiredException) {
                mutableState.update { it.copy(missingModels = e.languages) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.localizedMessage ?: "Translation failed. Try the browser translator.")
            } finally {
                mutableState.update { it.copy(translating = false) }
            }
        }
    }

    fun refreshModels() = work {
        val models = graph.engines.translator(preferences.value.translationEngine).models()
        mutableState.update { it.copy(models = models) }
    }

    fun download(tag: String) {
        if (tag in downloadJobs) return
        val prefs = preferences.value
        val engine = graph.engines.translator(prefs.translationEngine)
        mutableState.update { it.copy(downloading = it.downloading + tag) }
        downloadJobs[tag] = work {
            try {
                engine.download(tag, prefs.wifiOnly)
                val models = engine.models()
                if (preferences.value.translationEngine != prefs.translationEngine) return@work
                mutableState.update {
                    it.copy(models = models, missingModels = it.missingModels - tag)
                }
                message("${languageName(tag)} is ready offline")
                if (
                    state.value.missingModels.isEmpty() &&
                        state.value.text.isNotBlank() &&
                        state.value.page == Page.WORKSPACE
                )
                    translate()
            } finally {
                downloadJobs.remove(tag)
                mutableState.update { it.copy(downloading = it.downloading - tag) }
            }
        }
    }

    fun cancelDownload(tag: String) {
        downloadJobs[tag]?.cancel()
    }

    fun deleteModel(tag: String) = work {
        translationJob?.cancelAndJoin()
        graph.engines.translator(preferences.value.translationEngine).delete(tag)
        cache.clear()
        mutableState.update {
            it.copy(translation = null, translatedLines = emptyMap(), overlay = false)
        }
        refreshModels()
    }

    fun set(key: String, value: String) = work { graph.preferences.set(key, value) }

    fun set(key: String, value: Boolean) = work { graph.preferences.set(key, value) }

    fun addProvider(name: String, template: String) = work {
        graph.preferences.addProvider(
            TemplateSearchProvider("custom-${UUID.randomUUID()}", name.trim(), template.trim()),
        )
    }

    fun removeProvider(id: String) = work { graph.preferences.removeProvider(id) }

    fun languageName(tag: String): String =
        if (tag == "auto") "Detect language"
        else java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.ENGLISH)

    fun message(text: String?) {
        mutableState.update { it.copy(message = text) }
    }

    fun resetLive() {
        liveGeneration.incrementAndGet()
        stabilizer.reset()
        mutableLive.value = LiveState()
    }

    /** Invoked serially by the camera analyzer. Live processing never uses a cloud provider. */
    suspend fun analyzeFrame(bitmap: Bitmap) {
        val prefs = preferences.value
        val generation = liveGeneration.get()
        try {
            val recognition = graph.engines.ocr(prefs.ocrEngine).recognize(bitmap, prefs.script)
            if (generation != liveGeneration.get()) return
            val stable = stabilizer.update(recognition.lines)
            val output = mutableMapOf<String, String>()
            val stableText = stable.map { it.text }.toSet()
            val retained = mutableLive.value.translations.filterKeys { it in stableText }
            mutableLive.value =
                LiveState(
                    recognition,
                    retained,
                    if (stable.isEmpty()) "Hold steady to translate" else "Translating on device",
                )
            val engine = graph.engines.translator(prefs.translationEngine)
            for (line in stable.take(16)) {
                currentCoroutineContext().ensureActive()
                val source = source(engine, line.text, prefs)
                output[line.text] = translated(engine, line.text, source, prefs.target)
                if (generation != liveGeneration.get()) return
            }
            if (generation != liveGeneration.get()) return
            mutableLive.value =
                LiveState(
                    recognition,
                    output,
                    if (recognition.lines.isEmpty()) "Point at English or Japanese text"
                    else "On-device translation · ${languageName(prefs.target)}",
                )
        } catch (e: ModelRequiredException) {
            if (generation != liveGeneration.get()) return
            mutableLive.update {
                it.copy(
                    status =
                        "Download ${e.languages.joinToString { tag -> languageName(tag) }} in Models to translate offline",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != liveGeneration.get()) return
            mutableLive.update {
                it.copy(status = e.localizedMessage ?: "Could not read this frame")
            }
        }
    }

    private fun work(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(e.localizedMessage ?: "Something went wrong. Please try again.")
        }
    }
}
