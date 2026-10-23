package app.glimpse.core

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class LiveTranslationOptions(
    val ocr: String,
    val translator: String,
    val script: OcrScript = OcrScript.AUTO,
    val source: String = "auto",
    val target: String = "en",
)

data class LiveState(
    val recognition: Recognition? = null,
    val translations: Map<String, String> = emptyMap(),
    val status: String = "Point at English or Japanese text",
)

/** One serial latest-frame consumer, shared by camera and screen capture. No network adapters. */
class LiveTranslationController(private val engines: EngineRegistry) {
    private val mutable = MutableStateFlow(LiveState())
    val state = mutable.asStateFlow()
    private val generation = AtomicLong()
    private val mutex = Mutex()
    private val stabilizer = LiveTextStabilizer()
    private val cache = TranslationCache()
    private var options: LiveTranslationOptions? = null

    fun reset() {
        generation.incrementAndGet()
        stabilizer.reset()
        cache.clear()
        mutable.value = LiveState()
    }

    suspend fun analyze(bitmap: Bitmap, configuration: LiveTranslationOptions) {
        if (!mutex.tryLock()) return
        var current = generation.get()
        try {
            if (options != configuration) {
                reset()
                options = configuration
            }
            current = generation.get()
            val recognition = engines.ocr(configuration.ocr).recognize(bitmap, configuration.script)
            if (generation.get() != current) return
            val stable = stabilizer.update(recognition.lines)
            // Never carry boxes from an old frame to a new one. Only stable current geometry
            // can reuse a translation, so scrolling immediately removes stale labels.
            val retained =
                stable
                    .mapNotNull { line ->
                        mutable.value.translations[line.text]?.let { line.text to it }
                    }
                    .toMap()
            mutable.value =
                LiveState(
                    recognition,
                    retained,
                    if (stable.isEmpty()) "Hold steady to translate" else "Translating on device",
                )
            val engine = engines.translator(configuration.translator)
            val output = retained.toMutableMap()
            for (line in stable.take(24)) {
                currentCoroutineContext().ensureActive()
                val source =
                    if (configuration.source == "auto")
                        engine.identify(line.text).let {
                            if (it == "und") {
                                if (line.text.any { char -> char in '\u3040'..'\u9fff' }) "ja"
                                else "en"
                            } else it
                        }
                    else configuration.source
                val translated =
                    cache.get(engine.info.id, source, configuration.target, line.text)
                        ?: engine.translate(line.text, source, configuration.target).text.also {
                            cache.put(engine.info.id, source, configuration.target, line.text, it)
                        }
                if (generation.get() != current) return
                output[line.text] = translated
                mutable.value =
                    LiveState(
                        recognition,
                        output.toMap(),
                        "On-device translation · ${configuration.target}",
                    )
            }
        } catch (e: ModelRequiredException) {
            if (generation.get() == current)
                mutable.value =
                    mutable.value.copy(
                        status = "Download ${e.languages.joinToString()} in Offline languages",
                    )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation.get() == current)
                mutable.value =
                    mutable.value.copy(status = e.localizedMessage ?: "Could not read frame")
        } finally {
            mutex.unlock()
        }
    }
}
