package com.voicecaption.pipeline

import com.voicecaption.asr.SpeechRecognizer
import com.voicecaption.caption.CaptionStabilizer
import com.voicecaption.core.Caption
import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.PcmFrame
import com.voicecaption.core.SpeechSegment
import com.voicecaption.translate.Translator
import com.voicecaption.translate.TranslatorNotReadyException
import com.voicecaption.vad.VadConfig
import com.voicecaption.vad.VadSegmenter
import com.voicecaption.vad.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionPipelineTest {
    private val frameMs = 32L

    /** Speech frames carry 1f in sample 0, silence frames 0f. */
    private val fakeVad = VoiceActivityDetector { it[0] > 0f }

    private class FakeRecognizer(
        override val outputLanguage: String? = null,
        private val reply: suspend (SpeechSegment, String) -> String,
    ) : SpeechRecognizer {
        val segmentStarts = mutableListOf<Long>()
        val languages = mutableListOf<String>()
        override suspend fun transcribe(segment: SpeechSegment, language: String): String {
            segmentStarts += segment.startMs
            languages += language
            return reply(segment, language)
        }
        override fun close() = Unit
    }

    private class FakeTranslator(
        private val reply: suspend (String) -> String = { "T($it)" },
    ) : Translator {
        var calls = 0
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
            calls++
            return reply(text)
        }
        override suspend fun isReady(sourceLang: String, targetLang: String) = true
        override suspend fun prepare(
            sourceLang: String,
            targetLang: String,
            onProgress: (Boolean) -> Unit,
        ): Result<Unit> = Result.success(Unit)
    }

    private inner class Rig(
        scope: CoroutineScope,
        val recognizer: FakeRecognizer,
        val translator: FakeTranslator,
        val clock: () -> Long,
        var settings: CaptionSettings = CaptionSettings(sourceLang = "es", targetLang = "en"),
        vadConfig: VadConfig = VadConfig(),
    ) {
        val pipeline = CaptionPipeline(
            segmenter = VadSegmenter(fakeVad, vadConfig),
            recognizer = recognizer,
            translator = translator,
            stabilizer = CaptionStabilizer(),
            settings = { settings },
            scope = scope,
            clock = clock,
        )
        private var frameIndex = 0L

        private fun frame(speech: Boolean) {
            val samples = FloatArray(512).also { it[0] = if (speech) 1f else 0f }
            pipeline.onFrame(PcmFrame(samples, frameIndex++ * frameMs))
        }

        fun feedSilence(frames: Int = 13) = repeat(frames) { frame(false) }

        /** 10 speech frames (320 ms) and, if [close], 13 silent frames that close the segment. */
        fun feedSpeech(close: Boolean = true) {
            repeat(10) { frame(true) }
            if (close) repeat(13) { frame(false) }
        }
    }

    private fun TestScope.rig(
        recognizer: FakeRecognizer,
        translator: FakeTranslator = FakeTranslator(),
        settings: CaptionSettings = CaptionSettings(sourceLang = "es", targetLang = "en"),
        vadConfig: VadConfig = VadConfig(),
    ) = Rig(backgroundScope, recognizer, translator, { currentTime }, settings, vadConfig)

    private fun TestScope.collectCaptions(rig: Rig): MutableList<List<Caption>> {
        val seen = mutableListOf<List<Caption>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.pipeline.captions.collect { seen += it }
        }
        return seen
    }

    private fun TestScope.collectErrors(rig: Rig): MutableList<PipelineError> {
        val seen = mutableListOf<PipelineError>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.pipeline.errors.collect { seen += it }
        }
        return seen
    }

    @Test
    fun speechBecomesTranslatedCaption() = runTest {
        val r = rig(FakeRecognizer { _, _ -> "hola" }, FakeTranslator { "hello" })
        val seen = collectCaptions(r)
        r.feedSpeech()
        runCurrent()

        val nonFinal = seen.first { it.isNotEmpty() }.single()
        assertEquals("hola", nonFinal.sourceText)
        assertNull(nonFinal.translatedText)
        assertEquals(false, nonFinal.isFinal)

        val last = r.pipeline.captions.value.single()
        assertEquals("hola", last.sourceText)
        assertEquals("hello", last.translatedText)
        assertTrue(last.isFinal)
        assertEquals(listOf("es"), r.recognizer.languages)
    }

    @Test
    fun sameSourceAndTargetSkipsTranslation() = runTest {
        val r = rig(
            FakeRecognizer { _, _ -> "hello" },
            settings = CaptionSettings(sourceLang = "en", targetLang = "en"),
        )
        r.feedSpeech()
        runCurrent()

        assertEquals(0, r.translator.calls)
        val c = r.pipeline.captions.value.single()
        assertEquals("hello", c.sourceText)
        assertEquals("hello", c.translatedText)
        assertTrue(c.isFinal)
    }

    @Test
    fun regionalVariantOfSameLanguageSkipsTranslation() = runTest {
        val r = rig(
            FakeRecognizer { _, _ -> "color" },
            settings = CaptionSettings(sourceLang = "EN_us", targetLang = "en-GB"),
        )
        r.feedSpeech()
        runCurrent()
        assertEquals(0, r.translator.calls)
        assertTrue(r.pipeline.captions.value.single().isFinal)
    }

    @Test
    fun settingsAreReadPerSegment() = runTest {
        val r = rig(FakeRecognizer { _, _ -> "hola" }, FakeTranslator { "hello" })
        r.feedSpeech()
        runCurrent()
        assertEquals(1, r.translator.calls)

        r.settings = CaptionSettings(sourceLang = "en", targetLang = "en")
        r.feedSpeech()
        runCurrent()
        assertEquals(1, r.translator.calls)
        assertEquals(listOf("es", "en"), r.recognizer.languages)
    }

    @Test
    fun blankAndBracketTagTranscriptsShowNothing() = runTest {
        val outputs = ArrayDeque(listOf("", " ", "[Music]", "(applause)", "♪", "[BLANK_AUDIO]", " [Music] (laughs) ", "..."))
        val r = rig(FakeRecognizer { _, _ -> outputs.removeFirst() })
        val errors = collectErrors(r)
        repeat(8) {
            r.feedSpeech()
            runCurrent()
        }
        assertEquals(8, r.recognizer.segmentStarts.size)
        assertTrue(r.pipeline.captions.value.isEmpty())
        assertEquals(0, r.translator.calls)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun speechContainingATagButAlsoWordsIsKept() = runTest {
        val r = rig(FakeRecognizer { _, _ -> "[Music] hola (risas) amigo" }, FakeTranslator { "hi" })
        r.feedSpeech()
        runCurrent()
        assertEquals("[Music] hola (risas) amigo", r.pipeline.captions.value.single().sourceText)
    }

    @Test
    fun translatorNotReadyEmitsErrorAndKeepsSourceCaption() = runTest {
        val r = rig(
            FakeRecognizer { _, _ -> "hola" },
            FakeTranslator { throw TranslatorNotReadyException("no pack") },
        )
        val errors = collectErrors(r)
        r.feedSpeech()
        runCurrent()

        assertEquals(listOf<PipelineError>(PipelineError.TranslatorNotReady), errors)
        val c = r.pipeline.captions.value.single()
        assertEquals("hola", c.sourceText)
        assertNull(c.translatedText)
        assertEquals(false, c.isFinal)
    }

    @Test
    fun translatorNotReadyIsReportedOncePerOutageAndRearmsAfterSuccess() = runTest {
        var mode = "fail"
        val r = rig(
            FakeRecognizer { _, _ -> "hola" },
            FakeTranslator { if (mode == "fail") throw TranslatorNotReadyException("no pack") else "hello" },
        )
        val errors = collectErrors(r)
        repeat(2) { r.feedSpeech(); runCurrent() }
        assertEquals(1, errors.size)

        mode = "ok"
        r.feedSpeech(); runCurrent()
        mode = "fail"
        r.feedSpeech(); runCurrent()
        assertEquals(listOf<PipelineError>(PipelineError.TranslatorNotReady, PipelineError.TranslatorNotReady), errors)
    }

    @Test
    fun otherTranslatorFailureIsReportedAndDoesNotKillTheWorker() = runTest {
        var n = 0
        val boom = IllegalArgumentException("unsupported")
        val r = rig(
            FakeRecognizer { _, _ -> "hola" },
            FakeTranslator { if (n++ == 0) throw boom else "hello" },
        )
        val errors = collectErrors(r)
        r.feedSpeech(); runCurrent()
        r.feedSpeech(); runCurrent()

        assertEquals(listOf<PipelineError>(PipelineError.Translator(boom)), errors)
        assertEquals("hello", r.pipeline.captions.value.last().translatedText)
    }

    @Test
    fun recognizerFailureDoesNotKillThePipeline() = runTest {
        val boom = IllegalStateException("whisper died")
        var n = 0
        val r = rig(FakeRecognizer { _, _ -> if (n++ == 0) throw boom else "hola" }, FakeTranslator { "hello" })
        val errors = collectErrors(r)
        r.feedSpeech(); runCurrent()
        assertTrue(r.pipeline.captions.value.isEmpty())
        r.feedSpeech(); runCurrent()

        assertEquals(listOf<PipelineError>(PipelineError.Recognizer(boom)), errors)
        assertEquals("hello", r.pipeline.captions.value.single().translatedText)
    }

    @Test
    fun recognizerErrorsCarryTheNumberOfSegmentsRecognizedBefore() = runTest {
        val boom = IllegalStateException("whisper died")
        var n = 0
        val r = rig(FakeRecognizer { _, _ -> if (n++ % 2 == 0) throw boom else "hola" }, FakeTranslator { "hello" })
        val errors = collectErrors(r)
        repeat(4) { r.feedSpeech(); runCurrent() }

        assertEquals(
            listOf<PipelineError>(PipelineError.Recognizer(boom, 0), PipelineError.Recognizer(boom, 1)),
            errors,
        )
    }

    @Test
    fun slowRecognizerKeepsOrderAndBoundedBacklog() = runTest {
        val r = rig(FakeRecognizer { s, _ -> delay(1000); "t${s.startMs}" })
        val produced = 10
        // All segments are produced before the worker gets a chance to run.
        repeat(produced) { r.feedSpeech() }
        advanceTimeBy(60_000)

        val starts = r.recognizer.segmentStarts
        assertEquals(starts.sorted(), starts)
        assertEquals(starts.distinct(), starts)
        // Channel capacity 4 with DROP_OLDEST: the backlog never exceeds 4 (+1 if the worker had taken one).
        assertTrue("processed ${starts.size}", starts.size in 1..5)
        assertTrue(starts.size < produced)
        // Newest speech is never the one dropped.
        assertEquals(9 * 23 * frameMs, starts.last())
    }

    @Test
    fun expiryTickerClearsCaptionsAfterDisplayTime() = runTest {
        val r = rig(FakeRecognizer { _, _ -> "hola" }, FakeTranslator { "hello" })
        r.feedSpeech(); runCurrent()
        assertEquals(1, r.pipeline.captions.value.size)

        advanceTimeBy(3_000)
        assertEquals(1, r.pipeline.captions.value.size)
        advanceTimeBy(2_000)
        assertTrue(r.pipeline.captions.value.isEmpty())
    }

    @Test
    fun translationArrivingAfterSourceCaptionExpiredDoesNotResurrectIt() = runTest {
        val r = rig(FakeRecognizer { _, _ -> "hola" }, FakeTranslator { delay(6_000); "hello" })
        r.feedSpeech(); runCurrent()
        advanceTimeBy(5_000)
        assertTrue(r.pipeline.captions.value.isEmpty())
        advanceTimeBy(2_000)
        assertEquals(1, r.translator.calls)
        assertTrue(r.pipeline.captions.value.isEmpty())
    }

    @Test
    fun stopFlushesTheTailAndThenStopsTheTicker() = runTest {
        val r = rig(FakeRecognizer { _, _ -> "hola" }, FakeTranslator { "hello" })
        r.feedSpeech(close = false) // no hangover yet, so the segmenter holds it
        runCurrent()
        assertTrue(r.recognizer.segmentStarts.isEmpty())

        r.pipeline.stop()
        runCurrent()
        assertEquals(1, r.recognizer.segmentStarts.size)
        assertEquals("hello", r.pipeline.captions.value.single().translatedText)

        // Ticker was cancelled once the worker drained, so the last words stay put.
        advanceTimeBy(20_000)
        assertEquals(1, r.pipeline.captions.value.size)
    }

    @Test
    fun stopDrainsAlreadyQueuedSegmentsAndIsIdempotent() = runTest {
        val r = rig(FakeRecognizer { s, _ -> delay(100); "t${s.startMs}" })
        repeat(2) { r.feedSpeech() }
        r.feedSpeech(close = false)
        r.pipeline.stop()
        r.pipeline.stop()
        advanceTimeBy(10_000)
        assertEquals(3, r.recognizer.segmentStarts.size)
    }

    @Test
    fun onFrameAfterStopIsIgnored() = runTest {
        val r = rig(FakeRecognizer { _, _ -> "hola" })
        r.pipeline.stop()
        r.feedSpeech()
        runCurrent()
        assertTrue(r.recognizer.segmentStarts.isEmpty())
        assertTrue(r.pipeline.captions.value.isEmpty())
    }

    @Test
    fun recognizerThatAlreadyOutputsTargetLanguageSkipsTranslation() = runTest {
        val translator = FakeTranslator()
        val r = rig(FakeRecognizer(outputLanguage = "en") { _, _ -> "hello there" }, translator, CaptionSettings(sourceLang = "ja", targetLang = "en"))
        val seen = collectCaptions(r)
        r.feedSpeech()
        runCurrent()
        assertEquals(0, translator.calls)
        assertEquals("hello there", seen.last().single().translatedText)
        assertEquals("hello there", r.pipeline.stats.value.lastRaw)
    }

    @Test
    fun finalSegmentReplacesItsPartialInPlace() = runTest {
        val r = rig(
            FakeRecognizer { seg, _ -> if (seg.partial) "partial text" else "final text" },
            vadConfig = VadConfig(partialEveryMs = 500),
        )
        repeat(6) { r.feedSpeech(close = false) } // 60 speech frames, still speaking
        runCurrent()
        val provisional = r.pipeline.captions.value
        assertEquals(1, provisional.size)
        assertEquals("partial text", provisional[0].sourceText)

        r.feedSilence() // the sentence ends: the final segment closes
        runCurrent()
        val shown = r.pipeline.captions.value
        assertEquals(1, shown.size)
        assertEquals(provisional[0].id, shown[0].id)
        assertEquals("T(final text)", shown[0].translatedText)
    }

    @Test
    fun soundEffectNotesAreRemovedFromRecognizedText() = runTest {
        val tr = FakeTranslator()
        val r = rig(FakeRecognizer(outputLanguage = "en") { _, _ -> "(The sound of" }, tr, CaptionSettings(sourceLang = "ja", targetLang = "en"))
        r.feedSpeech()
        runCurrent()
        assertTrue(r.pipeline.captions.value.isEmpty()) // an unfinished note is dropped entirely

        val r2 = rig(FakeRecognizer(outputLanguage = "en") { _, _ -> "I'm sorry (The sound of" }, tr, CaptionSettings(sourceLang = "ja", targetLang = "en"))
        r2.feedSpeech()
        runCurrent()
        assertEquals("I'm sorry", r2.pipeline.captions.value.single().translatedText)
    }
}
