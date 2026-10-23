package app.glimpse.core

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield

data class LiveTranslationOptions(
    val ocr: String,
    val translator: String,
    val script: OcrScript = OcrScript.AUTO,
    val source: String = "auto",
    val target: String = "en",
)

data class LiveState(
    val recognition: Recognition? = null,
    /** Compatibility lookup, never an occurrence identity. Render [regions] instead. */
    val translations: Map<String, String> = emptyMap(),
    val status: String = "Point at English or Japanese text",
    val stableLines: List<TextLine> = emptyList(),
    val regions: List<TranslatedRegion> = emptyList(),
    val missingModels: Set<String> = emptySet(),
)

/**
 * Latest-scene, on-device translation shared by camera and screen capture.
 *
 * Each analysis supersedes the previous analysis, including its errors and cache writes. Neither
 * invalidation nor a new analysis waits for a cancelled engine to cooperate. Only current eligible
 * occurrences appear in [LiveState.regions]; recognition contains all OCR lines for
 * copying/debugging.
 *
 * Camera callers use the default two-observation stabilization. A motion-aware screen owner calls
 * [invalidate] as soon as content moves, then [analyze] with `settled = true` for one clean frame.
 * A camera pass attempts at most 24 uncached occurrences, with a fair rotating cursor. A settled
 * analysis drains all batches with cooperative yields, so one settled frame needs only one OCR.
 */
class LiveTranslationController(private val engines: EngineRegistry) {
    private class Request(val options: LiveTranslationOptions) {
        lateinit var job: Job
    }

    private data class SourceKey(val engine: String, val text: String)

    private val mutable = MutableStateFlow(LiveState())
    val state = mutable.asStateFlow()
    private val lock = Any()
    private val stabilizer = LiveTextStabilizer()
    private val cache = TranslationCache()
    private val sources =
        object : LinkedHashMap<SourceKey, String>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SourceKey, String>?) =
                size > 128
        }
    private var options: LiveTranslationOptions? = null
    private var active: Request? = null
    private var nextOccurrence = 0

    /**
     * Immediately forget geometry/stability and reject all in-flight results and errors. By
     * default, reusable string translations and detected languages survive motion. Use `clearCache
     * = true` after model changes; changing any analysis options also clears both caches
     * automatically.
     */
    fun invalidate(clearCache: Boolean = false) {
        val previous =
            synchronized(lock) {
                val previous = active
                active = null
                stabilizer.reset()
                nextOccurrence = 0
                if (clearCache) {
                    cache.clear()
                    sources.clear()
                    options = null
                }
                mutable.value = LiveState()
                previous
            }
        previous?.job?.cancel()
    }

    /** Full language/model/session reset, including reusable translations. */
    fun reset() = invalidate(clearCache = true)

    suspend fun analyze(
        bitmap: Bitmap,
        configuration: LiveTranslationOptions,
        settled: Boolean = false,
    ) =
        analyzeLatest(configuration, settled) {
            engines.ocr(configuration.ocr).recognize(bitmap, configuration.script)
        }

    /**
     * Equivalent to [analyze] for owners that already have OCR. Bounds and dimensions must describe
     * the current upright frame. `settled = true` admits all its lines immediately; the default
     * still requires two observations and bounds new work to 24 occurrences per call. Settled calls
     * return after all eligible lines have been attempted.
     */
    suspend fun processRecognition(
        recognition: Recognition,
        configuration: LiveTranslationOptions,
        settled: Boolean = false,
    ) = analyzeLatest(configuration, settled) { recognition }

    private suspend fun analyzeLatest(
        configuration: LiveTranslationOptions,
        settled: Boolean,
        recognize: suspend () -> Recognition,
    ) = supervisorScope {
        ensureActive()
        val request = Request(configuration)
        val worker =
            async(start = CoroutineStart.LAZY) {
                try {
                    val recognition = recognize()
                    checkCurrent(request)
                    translate(request, recognition, settled)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    publish(request) {
                        it.copy(
                            status = errorMessage(e),
                            missingModels = (e as? ModelRequiredException)?.languages.orEmpty(),
                        )
                    }
                }
            }
        request.job = worker
        val previous =
            synchronized(lock) {
                if (options != configuration) {
                    stabilizer.reset()
                    nextOccurrence = 0
                    cache.clear()
                    sources.clear()
                    options = configuration
                }
                val previous = active
                active = request
                // Do not display geometry from an earlier frame while reading a new frame.
                mutable.value = LiveState(status = "Reading text")
                previous
            }
        previous?.job?.cancel()
        worker.start()
        try {
            worker.await()
        } catch (e: CancellationException) {
            // Supersession cancels only this worker; cancellation of the owner still propagates.
            ensureActive()
        } finally {
            synchronized(lock) {
                if (active === request) {
                    active = null
                    if (!isActive) {
                        stabilizer.reset()
                        nextOccurrence = 0
                        mutable.value = LiveState()
                    }
                }
            }
        }
    }

    private suspend fun translate(
        request: Request,
        recognition: Recognition,
        settled: Boolean,
    ) {
        val stable =
            synchronized(lock) {
                requireCurrent(request)
                stabilizer.update(recognition.lines, settled).filter { it.text.isNotBlank() }
            }
        val translated = mutableMapOf<Int, String>()
        val missing = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        var completed = 0
        var alreadyTarget = 0
        val configuration = request.options
        val engine = engines.translator(configuration.translator)
        val resolved = BooleanArray(stable.size)
        val start =
            synchronized(lock) {
                requireCurrent(request)
                // Reuse strings only for eligible current occurrences. Preloading the entire scene
                // keeps cached labels visible even when this pass starts at a later pending line.
                for ((index, line) in stable.withIndex()) {
                    val source =
                        if (configuration.source == "auto") {
                            sources[SourceKey(engine.info.id, line.text)]
                        } else configuration.source
                    if (source != null) {
                        if (sameLanguage(source, configuration.target)) {
                            alreadyTarget++
                            resolved[index] = true
                        } else {
                            cache
                                .get(engine.info.id, source, configuration.target, line.text)
                                ?.let { text ->
                                    resolved[index] = true
                                    if (text.isNotBlank() && text.trim() != line.text.trim()) {
                                        translated[index] = text
                                    }
                                }
                        }
                    }
                }
                completed = resolved.count { it }
                if (stable.isEmpty()) 0 else nextOccurrence % stable.size
            }

        fun output() {
            val regions = stable.mapIndexedNotNull { index, line ->
                translated[index]?.let { TranslatedRegion(line, it) }
            }
            publish(request) {
                LiveState(
                    recognition = recognition,
                    translations = regions.associate { it.source.text to it.translatedText },
                    status =
                        when {
                            missing.isNotEmpty() ->
                                "Download ${missing.joinToString()} in Offline languages"
                            errors.isNotEmpty() ->
                                "Some text could not be translated: ${errors.first()}"
                            stable.isEmpty() ->
                                if (recognition.lines.isEmpty()) "No text found"
                                else "Hold steady to translate"
                            completed < stable.size ->
                                "Translating on device · $completed/${stable.size}"
                            alreadyTarget == stable.size ->
                                "Already in target language · ${request.options.target}"
                            else -> "On-device translation · ${request.options.target}"
                        },
                    stableLines = stable,
                    regions = regions,
                    missingModels = missing.toSet(),
                )
            }
        }

        output()
        if (stable.isEmpty()) return
        var attempted = 0
        val order = stable.indices.map { (start + it) % stable.size }
        // Advance before awaiting an engine: even a repeatedly superseded slow/failing line cannot
        // monopolize the next camera pass. Settled scenes visit every batch without another OCR.
        for (batch in order.chunked(24)) {
            for (index in batch) {
                if (resolved[index]) continue
                val line = stable[index]
                checkCurrent(request)
                synchronized(lock) {
                    requireCurrent(request)
                    nextOccurrence = (index + 1) % stable.size
                }
                try {
                    val source = source(request, engine, line)
                    if (sameLanguage(source, configuration.target)) {
                        alreadyTarget++
                    } else {
                        val cached =
                            synchronized(lock) {
                                requireCurrent(request)
                                cache.get(engine.info.id, source, configuration.target, line.text)
                            }
                        val text =
                            cached
                                ?: engine
                                    .translate(
                                        line.text,
                                        source,
                                        configuration.target,
                                    )
                                    .text
                        checkCurrent(request)
                        synchronized(lock) {
                            requireCurrent(request)
                            if (cached == null) {
                                cache.put(
                                    engine.info.id,
                                    source,
                                    configuration.target,
                                    line.text,
                                    text,
                                )
                            }
                        }
                        if (text.isNotBlank() && text.trim() != line.text.trim()) {
                            translated[index] = text
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelRequiredException) {
                    missing.addAll(e.languages)
                } catch (e: Exception) {
                    errors.add(errorMessage(e))
                }
                checkCurrent(request)
                completed++
                output()
                attempted++
                if (!settled && attempted == 24) return
            }
            yield()
        }
    }

    private suspend fun source(
        request: Request,
        engine: TranslationEngine,
        line: TextLine,
    ): String {
        if (request.options.source != "auto") return request.options.source
        val key = SourceKey(engine.info.id, line.text)
        synchronized(lock) {
            requireCurrent(request)
            sources[key]?.let {
                return it
            }
        }
        val identified = engine.identify(line.text)
        checkCurrent(request)
        val source =
            if (identified.isBlank() || identified == "und") {
                when {
                    line.language.isNotBlank() && line.language != "und" -> line.language
                    line.text.any { it in '\u3040'..'\u9fff' } -> "ja"
                    else -> "en"
                }
            } else identified
        synchronized(lock) {
            requireCurrent(request)
            sources[key] = source
        }
        return source
    }

    private fun sameLanguage(source: String, target: String) =
        source.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true)

    private fun errorMessage(error: Exception) =
        if (error is ModelRequiredException)
            "Download ${error.languages.joinToString()} in Offline languages"
        else error.localizedMessage ?: "Could not read frame"

    private suspend fun checkCurrent(request: Request) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { requireCurrent(request) }
    }

    // Request object identity is the generation token. All state/cache/stabilizer mutations are
    // guarded by the same lock, making invalidation atomic with respect to a late engine callback.
    private fun requireCurrent(request: Request) {
        if (active !== request || !request.job.isActive || options != request.options) {
            throw CancellationException("Live scene superseded")
        }
    }

    private fun publish(request: Request, update: (LiveState) -> LiveState) {
        synchronized(lock) {
            if (active === request && request.job.isActive && options == request.options) {
                mutable.value = update(mutable.value)
            }
        }
    }
}
