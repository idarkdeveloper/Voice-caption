package com.voicecaption.bench

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicecaption.asr.HttpUrlConnectionDownloader
import com.voicecaption.asr.ModelManager
import com.voicecaption.asr.WhisperLocal
import com.voicecaption.core.ModelSize
import com.voicecaption.core.SpeechSegment
import com.voicecaption.testutil.WavReader
import com.voicecaption.translate.MlKitTranslator
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Latency and accuracy benchmark. Needs a real device and network (model and language-pack
 * downloads on first run). Asserts sanity only (every transcript non-empty); it never asserts
 * latency or error-rate thresholds because devices differ. Results go to logcat (tag
 * [TAG], JSON Lines between BENCH_JSON_BEGIN/END, one record per entry) and to `bench-results.jsonl` in the target app's
 * filesDir (only retrievable if the app stays installed after the run).
 *
 * Run: `./gradlew connectedDebugAndroidTest --tests '*LatencyBenchmarkTest*'`
 * Add `-Pandroid.testInstrumentationRunnerArguments.bench.small=true` to include SMALL.
 */
@RunWith(AndroidJUnit4::class)
class LatencyBenchmarkTest {
    private lateinit var context: Context
    private lateinit var translator: MlKitTranslator
    private val openModels = mutableListOf<WhisperLocal>()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        translator = MlKitTranslator(context)
    }

    @After fun tearDown() {
        openModels.forEach { it.close() }
        openModels.clear()
        translator.close()
    }

    @Test fun endToEndLatencyPerModel() = runBlocking {
        val clips = loadClips()
        assertTrue("No clips in bench/manifest.json", clips.isNotEmpty())
        val sizes = buildList {
            add(ModelSize.BASE)
            if (InstrumentationRegistry.getArguments().getString("bench.small") == "true") add(ModelSize.SMALL)
        }
        val modelsDir = File(context.filesDir, "models")
        val manager = ModelManager(modelsDir, HttpUrlConnectionDownloader())
        val packReady = mutableMapOf<String, Boolean>()
        val clipRecords = mutableListOf<ClipRecord>()
        val summaries = mutableListOf<SummaryRecord>()
        val emptyTranscripts = mutableListOf<String>()

        for (size in sizes) {
            if (!manager.isInstalled(size)) manager.install(size) {}.getOrThrow()
            val whisper = WhisperLocal(File(modelsDir, size.name.lowercase()), size).also { openModels += it }
            val results = clips.map { clip ->
                val result = benchmarkClip(whisper, clip, packReady)
                if (result.transcript.isBlank()) emptyTranscripts += "${size.name}/${clip.file}"
                Log.i(TAG, "${size.name} ${result.describe()}")
                result
            }
            val summary = ModelSummary.of(size.name, results)
            Log.i(TAG, summary.describe())
            clipRecords += results.map { it.toRecord(size.name) }
            summaries += summary.toRecord()
            whisper.close()
            openModels.remove(whisper)
        }

        val meta = BenchMeta(
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT, RUNS,
        )
        val lines = ResultsJsonLines.build(meta, clipRecords, summaries)
        logJsonLines(lines) // primary output: survives the test run uninstalling the app
        val out = File(context.filesDir, RESULTS_FILE) // secondary: only if the app stays installed
        out.writeText(lines.joinToString("\n", postfix = "\n"))
        Log.i(TAG, "Results also written to ${out.absolutePath}")
        assertTrue("Empty transcripts: $emptyTranscripts", emptyTranscripts.isEmpty())
    }

    /** One Log.i per record (each is a complete JSON object), between BEGIN/END marker entries. */
    private fun logJsonLines(lines: List<String>) {
        Log.i(TAG, "BENCH_JSON_BEGIN")
        lines.forEach { Log.i(TAG, it) }
        Log.i(TAG, "BENCH_JSON_END")
    }

    /** Warms up once, then times [RUNS] transcribe (+ translate for non-English) runs. */
    private suspend fun benchmarkClip(
        whisper: WhisperLocal,
        clip: Clip,
        packReady: MutableMap<String, Boolean>,
    ): ClipResult {
        val segment = SpeechSegment(clip.samples, 0L, clip.samples.size * 1000L / SAMPLE_RATE)
        whisper.transcribe(segment, clip.lang) // warm-up: loads the model, not timed
        val translate = clip.lang != "en" &&
            packReady.getOrPut(clip.lang) { translator.prepare(clip.lang, "en") {}.isSuccess }
        var transcript = ""
        var translationOk = translate
        if (translationOk) {
            // Warm-up: ML Kit client/model load must not count in the timed runs.
            try {
                translator.translate(whisper.transcribe(segment, clip.lang).ifBlank { clip.reference }, clip.lang, "en")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "translation skipped for ${clip.file}: ${e.message}")
                translationOk = false
            }
        }
        val transcribeMs = mutableListOf<Long>()
        val translateMs = mutableListOf<Long>()
        repeat(RUNS) {
            var text = ""
            transcribeMs += measureTimeMillis { text = whisper.transcribe(segment, clip.lang) }
            if (it == 0) transcript = text
            if (translationOk && text.isNotBlank()) {
                try {
                    translateMs += measureTimeMillis { translator.translate(text, clip.lang, "en") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "translation skipped for ${clip.file}: ${e.message}")
                    translationOk = false
                    translateMs.clear()
                }
            }
        }
        val totals = transcribeMs.mapIndexed { i, ms -> ms + translateMs.getOrElse(i) { 0L } }
        return ClipResult(
            file = clip.file,
            lang = clip.lang,
            transcript = transcript,
            error = ErrorRate.compute(clip.reference, transcript),
            transcribeMs = LatencyStats.median(transcribeMs),
            translateMs = if (translateMs.isEmpty()) null else LatencyStats.median(translateMs),
            totalMs = LatencyStats.median(totals),
            totalRuns = totals,
        )
    }

    private fun loadClips(): List<Clip> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val manifest = JSONArray(assets.open("bench/manifest.json").bufferedReader().use { it.readText() })
        return (0 until manifest.length()).map {
            val e = manifest.getJSONObject(it)
            val file = e.getString("file")
            Clip(file, e.getString("lang"), e.getString("reference"), WavReader.toSamples(assets.open("bench/$file").readBytes()))
        }
    }

    private class Clip(val file: String, val lang: String, val reference: String, val samples: FloatArray)

    private class ClipResult(
        val file: String,
        val lang: String,
        val transcript: String,
        val error: ErrorRate.Result,
        val transcribeMs: Double,
        val translateMs: Double?,
        val totalMs: Double,
        val totalRuns: List<Long>,
    ) {
        fun describe() = "$file lang=$lang ${error.metric}=${"%.3f".format(error.value)} " +
            "transcribeMs=$transcribeMs translation=${translateMs?.let { "${it}ms" } ?: "skipped"} totalMs=$totalMs"

        fun toRecord(model: String) = ClipRecord(
            model, file, lang, transcript, error.metric.name, error.value, transcribeMs, translateMs, totalMs,
        )
    }

    private class ModelSummary(
        val model: String,
        val clipCount: Int,
        val medianMs: Double,
        val p95Ms: Long,
        val meanError: Double,
    ) {
        fun describe() = "SUMMARY model=$model clips=$clipCount medianMs=$medianMs p95Ms=$p95Ms " +
            "meanErrorRate=${"%.3f".format(meanError)}"

        fun toRecord() = SummaryRecord(model, clipCount, medianMs, p95Ms, meanError)

        companion object {
            /** Aggregates over every individual timed run of every clip. */
            fun of(model: String, results: List<ClipResult>): ModelSummary {
                val runs = results.flatMap { it.totalRuns }
                return ModelSummary(
                    model, results.size, LatencyStats.median(runs), LatencyStats.p95(runs),
                    LatencyStats.mean(results.map { it.error.value }),
                )
            }
        }
    }

    private companion object {
        const val TAG = "TransCapBench"
        const val RESULTS_FILE = "bench-results.jsonl"
        const val RUNS = 3
        const val LOG_CHUNK = 3500
        const val SAMPLE_RATE = 16_000
    }
}
