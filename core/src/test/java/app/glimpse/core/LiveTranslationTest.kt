package app.glimpse.core

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LiveTranslationTest {
    private val configuration = LiveTranslationOptions("ocr", "translator", source = "ja")

    private fun line(text: String, top: Float = .1f) =
        TextLine(text, ImageRegion(.1f, top, .9f, top + .01f), "ja")

    private fun scene(vararg lines: TextLine) = Recognition(lines.toList(), 100, 200)

    private class FakeTranslator : TranslationEngine {
        override val info = EngineInfo("translator", "Fake translator", "", true)
        override val languages = emptyList<Language>()
        val calls = mutableListOf<Triple<String, String, String>>()
        val identifications = mutableListOf<String>()
        var identify: suspend (String) -> String = { "ja" }
        var translate: suspend (String, String, String) -> String = { text, _, target ->
            "$text in $target"
        }

        override suspend fun identify(text: String): String {
            identifications.add(text)
            return identify.invoke(text)
        }

        override suspend fun translate(text: String, source: String, target: String): Translation {
            calls.add(Triple(text, source, target))
            return Translation(translate.invoke(text, source, target), source, target, info.id)
        }

        override suspend fun models() = emptyList<LanguageModel>()

        override suspend fun download(language: String, wifiOnly: Boolean) {}

        override suspend fun delete(language: String) {}

        override fun close() {}
    }

    private fun controller(translator: FakeTranslator): LiveTranslationController {
        val ocr =
            object : OcrEngine {
                override val info = EngineInfo("ocr", "Unused OCR", "", true)
                override val scripts = OcrScript.entries.toSet()

                override suspend fun recognize(image: Bitmap, script: OcrScript): Recognition =
                    error("Recognition is supplied directly in these JVM tests")

                override fun close() {}
            }
        return LiveTranslationController(EngineRegistry(listOf(ocr), listOf(translator)))
    }

    @Test
    fun cameraRequiresTwoObservationsAndNeverReusesGeometryAfterScrolling() = runBlocking {
        val engine = FakeTranslator()
        val controller = controller(engine)
        val original = line("hello")
        controller.processRecognition(scene(original), configuration)
        assertTrue(controller.state.value.regions.isEmpty())
        assertTrue(engine.calls.isEmpty())
        controller.processRecognition(scene(original), configuration)
        assertEquals(
            listOf(TranslatedRegion(original, "hello in en")),
            controller.state.value.regions,
        )

        val moved = original.copy(bounds = ImageRegion(.1f, .5f, .9f, .51f))
        controller.processRecognition(scene(moved), configuration)
        assertEquals(listOf(moved), controller.state.value.recognition!!.lines)
        assertTrue(controller.state.value.regions.isEmpty())
        assertTrue(controller.state.value.translations.isEmpty())
        controller.processRecognition(scene(moved), configuration)
        assertEquals(moved.bounds, controller.state.value.regions.single().bounds)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun settledSceneNeedsOnlyOneRecognitionAndTranslatesEveryBatch() = runBlocking {
        val engine = FakeTranslator()
        val controller = controller(engine)
        val lines = (0 until 73).map { line("line $it", .01f + it * .012f) }
        val recognition = Recognition(lines, 100, 200)

        controller.processRecognition(recognition, configuration, settled = true)

        assertEquals(lines, controller.state.value.regions.map { it.source })
        assertEquals(73, engine.calls.size)
        assertEquals(73, controller.state.value.translations.size)
        // A completed/cached prefix must not starve a newly appended suffix on later passes.
        val appended = line("appended", .92f)
        controller.processRecognition(
            recognition.copy(lines = lines + appended),
            configuration,
            settled = true,
        )
        assertEquals(lines + appended, controller.state.value.regions.map { it.source })
        assertEquals(74, engine.calls.size)
    }

    @Test
    fun cameraPassesBoundNewWorkAndFairlyReachLinesAfterAFailingPrefix() = runBlocking {
        val engine =
            FakeTranslator().apply {
                translate = { text, _, _ ->
                    if (text.startsWith("missing")) throw ModelRequiredException(setOf("ja"))
                    "translated $text"
                }
            }
        val controller = controller(engine)
        val lines =
            (0 until 61).map {
                line(if (it < 24) "missing $it" else "good $it", .01f + it * .015f)
            }
        val recognition = Recognition(lines, 100, 200)
        controller.processRecognition(recognition, configuration)
        assertTrue(engine.calls.isEmpty())

        controller.processRecognition(recognition, configuration)
        assertEquals(24, engine.calls.size)
        assertTrue(controller.state.value.regions.isEmpty())
        controller.processRecognition(recognition, configuration)
        assertEquals(48, engine.calls.size)
        assertEquals(lines.subList(24, 48), controller.state.value.regions.map { it.source })
        controller.processRecognition(recognition, configuration)
        assertEquals(72, engine.calls.size)
        assertEquals(lines.drop(24), controller.state.value.regions.map { it.source })
        assertEquals(setOf("ja"), controller.state.value.missingModels)
    }

    @Test
    fun supersededSlowCameraLineDoesNotMonopolizeTheNextPass() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine =
            FakeTranslator().apply {
                var first = true
                translate = { text, _, _ ->
                    if (first) {
                        first = false
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            "obsolete $text"
                        }
                    } else "current $text"
                }
            }
        val controller = controller(engine)
        val lines = (0 until 30).map { line("line $it", .01f + it * .02f) }
        val recognition = Recognition(lines, 100, 200)
        controller.processRecognition(recognition, configuration)
        val old = launch {
            controller.processRecognition(recognition, configuration)
        }
        entered.await()
        try {
            withTimeout(5_000) { controller.processRecognition(recognition, configuration) }
            assertEquals(lines.subList(1, 25), controller.state.value.regions.map { it.source })
        } finally {
            release.complete(Unit)
        }
        old.join()
        controller.processRecognition(recognition, configuration)
        assertEquals(lines, controller.state.value.regions.map { it.source })
        assertTrue(controller.state.value.regions.none { it.translatedText.startsWith("obsolete") })
    }

    @Test
    fun duplicateTextAtANewPositionDoesNotInheritAnEligibleOccurrencesLabel() = runBlocking {
        val engine = FakeTranslator()
        val controller = controller(engine)
        val original = line("same text")
        val duplicate = line("same text", .6f)
        controller.processRecognition(scene(original), configuration)
        controller.processRecognition(scene(original), configuration)
        controller.processRecognition(scene(original, duplicate), configuration)

        val state = controller.state.value
        assertEquals(2, state.recognition!!.lines.size)
        assertEquals(mapOf("same text" to "same text in en"), state.translations)
        assertEquals(listOf(original), state.stableLines)
        assertEquals(listOf(original), state.regions.map { it.source })

        controller.processRecognition(scene(original, duplicate), configuration)
        assertEquals(listOf(original, duplicate), controller.state.value.regions.map { it.source })
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun missingModelsAndOtherLineFailuresDoNotPreventFollowingRegions() = runBlocking {
        val engine =
            FakeTranslator().apply {
                translate = { text, _, _ ->
                    when (text) {
                        "missing ja" -> throw ModelRequiredException(setOf("ja", "en"))
                        "missing ko" -> throw ModelRequiredException(setOf("ko"))
                        "broken" -> error("One bad line")
                        else -> "translated $text"
                    }
                }
            }
        val controller = controller(engine)
        val lines =
            listOf("missing ja", "good first", "broken", "missing ko", "good last").mapIndexed {
                index,
                text ->
                line(text, .1f + index * .1f)
            }

        controller.processRecognition(Recognition(lines, 100, 200), configuration, settled = true)

        assertEquals(setOf("ja", "en", "ko"), controller.state.value.missingModels)
        assertEquals(
            listOf("good first", "good last"),
            controller.state.value.regions.map { it.sourceText },
        )
        assertEquals(lines.map { it.text }, engine.calls.map { it.first })
        assertTrue(controller.state.value.status.contains("Offline languages"))
    }

    @Test
    fun failedFirstBatchDoesNotStarveLaterLines() = runBlocking {
        val engine =
            FakeTranslator().apply {
                translate = { text, _, _ ->
                    if (text.startsWith("missing")) throw ModelRequiredException(setOf("ja"))
                    "translated $text"
                }
            }
        val controller = controller(engine)
        val lines =
            (0 until 55).map {
                line(if (it < 24) "missing $it" else "good $it", .01f + it * .015f)
            }

        repeat(2) {
            controller.processRecognition(
                Recognition(lines, 100, 200),
                configuration,
                settled = true,
            )
            assertEquals(lines.drop(24), controller.state.value.regions.map { it.source })
        }
        assertEquals(55 + 24, engine.calls.size)
    }

    @Test
    fun targetLanguageLinesAreSuccessfulWithoutRedundantLabelsOrModelRequests() = runBlocking {
        val engine =
            FakeTranslator().apply {
                identify = { if (it == "English") "en-US" else "ja" }
                translate = { text, _, _ ->
                    if (text == "English") throw ModelRequiredException(setOf("en"))
                    "Japanese translated"
                }
            }
        val controller = controller(engine)
        val options = configuration.copy(source = "auto")
        controller.processRecognition(scene(line("English")), options, settled = true)
        assertTrue(controller.state.value.regions.isEmpty())
        assertTrue(controller.state.value.translations.isEmpty())
        assertTrue(controller.state.value.missingModels.isEmpty())
        assertEquals("Already in target language · en", controller.state.value.status)
        assertTrue(engine.calls.isEmpty())

        controller.processRecognition(
            scene(line("English"), line("Japanese", .5f)),
            options,
            settled = true,
        )
        assertEquals(listOf("Japanese"), controller.state.value.regions.map { it.sourceText })
        assertTrue(controller.state.value.missingModels.isEmpty())
        assertEquals(listOf("Japanese"), engine.calls.map { it.first })
    }

    @Test
    fun motionInvalidationClearsGeometryButReusesTranslationsAndDetectedLanguages() = runBlocking {
        val engine = FakeTranslator()
        val controller = controller(engine)
        val options = configuration.copy(source = "auto")
        controller.processRecognition(scene(line("hello")), options, settled = true)
        controller.invalidate()
        assertEquals(LiveState(), controller.state.value)

        controller.processRecognition(scene(line("hello", .6f)), options)
        assertTrue(controller.state.value.regions.isEmpty())
        controller.processRecognition(scene(line("hello", .6f)), options)
        assertEquals(.6f, controller.state.value.regions.single().bounds.top)
        assertEquals(1, engine.calls.size)
        assertEquals(listOf("hello"), engine.identifications)

        controller.reset()
        controller.processRecognition(scene(line("hello")), options, settled = true)
        assertEquals(2, engine.calls.size)
        assertEquals(2, engine.identifications.size)

        controller.invalidate(clearCache = true)
        controller.processRecognition(scene(line("hello")), options, settled = true)
        assertEquals(3, engine.calls.size)
        assertEquals(3, engine.identifications.size)
    }

    @Test
    fun staleNonCooperativeSuccessCannotPublishOrPopulateTheCacheAfterInvalidation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine =
            FakeTranslator().apply {
                var first = true
                translate = { text, _, _ ->
                    if (first) {
                        first = false
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            "obsolete success"
                        }
                    } else "current $text"
                }
            }
        val controller = controller(engine)
        val old =
            launch(start = CoroutineStart.UNDISPATCHED) {
                controller.processRecognition(scene(line("old")), configuration, settled = true)
            }
        entered.await()
        controller.invalidate()
        assertEquals(LiveState(), controller.state.value)
        try {
            withTimeout(5_000) {
                controller.processRecognition(scene(line("new")), configuration, settled = true)
            }
        } finally {
            release.complete(Unit)
        }
        val newer = controller.state.value
        old.join()
        assertEquals(newer, controller.state.value)
        assertEquals("current new", newer.regions.single().translatedText)

        controller.invalidate()
        controller.processRecognition(scene(line("old")), configuration, settled = true)
        assertEquals("current old", controller.state.value.regions.single().translatedText)
        assertEquals(2, engine.calls.count { it.first == "old" })
    }

    @Test
    fun staleErrorAndFinallyCannotChangeAnActiveNewerRequest() = runBlocking {
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val engine =
            FakeTranslator().apply {
                translate = { text, _, _ ->
                    withContext(NonCancellable) {
                        if (text == "old") {
                            oldEntered.complete(Unit)
                            releaseOld.await()
                            throw ModelRequiredException(setOf("obsolete-model"))
                        }
                        newEntered.complete(Unit)
                        releaseNew.await()
                        "new success"
                    }
                }
            }
        val controller = controller(engine)
        val old = launch {
            controller.processRecognition(scene(line("old")), configuration, settled = true)
        }
        oldEntered.await()
        controller.invalidate()
        val newer = launch {
            controller.processRecognition(scene(line("new")), configuration, settled = true)
        }
        try {
            withTimeout(5_000) { newEntered.await() }
            val pending = controller.state.value
            releaseOld.complete(Unit)
            old.join()
            assertEquals(pending, controller.state.value)
            assertTrue(controller.state.value.missingModels.isEmpty())
        } finally {
            releaseOld.complete(Unit)
            releaseNew.complete(Unit)
        }
        newer.join()
        assertEquals("new success", controller.state.value.regions.single().translatedText)
    }

    @Test
    fun newerRecognitionSupersedesDelayedWorkWithoutWaitingForEngineCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine =
            FakeTranslator().apply {
                translate = { text, _, _ ->
                    if (text == "old")
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            throw IllegalStateException("stale failure")
                        }
                    else "translated $text"
                }
            }
        val controller = controller(engine)
        val old = launch {
            controller.processRecognition(scene(line("old")), configuration, settled = true)
        }
        entered.await()
        try {
            withTimeout(5_000) {
                controller.processRecognition(scene(line("latest")), configuration, settled = true)
            }
        } finally {
            release.complete(Unit)
        }
        val latest = controller.state.value
        old.join()
        assertEquals(latest, controller.state.value)
        assertEquals("translated latest", latest.regions.single().translatedText)
    }

    @Test
    fun changedOptionsRejectInFlightFailureAndClearReusableCaches() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine =
            FakeTranslator().apply {
                var first = true
                translate = { _, _, target ->
                    if (first) {
                        first = false
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            throw ModelRequiredException(setOf("obsolete-model"))
                        }
                    } else "translated into $target"
                }
            }
        val controller = controller(engine)
        val old = launch {
            controller.processRecognition(scene(line("text")), configuration, settled = true)
        }
        entered.await()
        val french = configuration.copy(target = "fr")
        try {
            withTimeout(5_000) {
                controller.processRecognition(scene(line("text")), french, settled = true)
            }
        } finally {
            release.complete(Unit)
        }
        old.join()
        assertEquals("translated into fr", controller.state.value.regions.single().translatedText)
        assertTrue(controller.state.value.missingModels.isEmpty())

        controller.processRecognition(scene(line("text")), configuration, settled = true)
        assertEquals("translated into en", controller.state.value.regions.single().translatedText)
        controller.processRecognition(scene(line("text")), french, settled = true)
        assertEquals(4, engine.calls.size)
        // OCR/script changes also reset stability and caches, even with identical language options.
        controller.processRecognition(scene(line("text")), french.copy(script = OcrScript.JAPANESE))
        assertTrue(controller.state.value.regions.isEmpty())
        controller.processRecognition(scene(line("text")), french.copy(script = OcrScript.JAPANESE))
        assertEquals(5, engine.calls.size)
    }

    @Test
    fun cancellingOwnerClearsPartialGeometryAndDoesNotBlockNextAnalysis() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine =
            FakeTranslator().apply {
                translate = { text, _, _ ->
                    if (text == "delayed")
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            "obsolete result"
                        }
                    else "translated $text"
                }
            }
        val controller = controller(engine)
        val old = launch {
            controller.processRecognition(
                scene(line("ready"), line("delayed", .5f)),
                configuration,
                settled = true,
            )
        }
        entered.await()
        assertEquals(listOf("ready"), controller.state.value.regions.map { it.sourceText })
        old.cancel()
        try {
            yield()
            assertEquals(LiveState(), controller.state.value)
            withTimeout(5_000) {
                controller.processRecognition(scene(line("new")), configuration, settled = true)
            }
        } finally {
            release.complete(Unit)
        }
        old.join()
        assertEquals(listOf("new"), controller.state.value.regions.map { it.sourceText })
        assertTrue(old.isCancelled)
    }
}
