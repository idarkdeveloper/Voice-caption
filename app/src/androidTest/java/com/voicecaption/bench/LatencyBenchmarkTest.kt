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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Latency and accuracy benchmark. Needs a real device and network (model and language-pack
 * downloads on first run). Asserts sanity only (every transcript non-empty); it never asserts
 * latency or error-rate thresholds because devices differ. Results go to logcat (tag
 * [TAG], full JSON between BENCH_JSON_BEGIN/END) and to `bench-results.json` in the target app's
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
        val models = JSONArray()
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
            models.put(summary.toJson(results))
            whisper.close()
            openModels.remove(whisper)
        }

        val json = JSONObject()
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("androidRelease", android.os.Build.VERSION.RELEASE)
            .put("sdkInt", android.os.Build.VERSION.SDK_INT)
            .put("runsPerClip", RUNS)
            .put("models", models)
            .toString(2)
        logJson(json) // primary output: survives the test run uninstalling the app
        val out = File(context.filesDir, RESULTS_FILE) // secondary: only if the app stays installed
        out.writeText(json)
        Log.i(TAG, "Results also written to ${out.absolutePath}")
        assertTrue("Empty transcripts: $emptyTranscripts", emptyTranscripts.isEmpty())
    }

    /** Prints [json] between BENCH_JSON_BEGIN / BENCH_JSON_END markers, in chunks short enough for logcat. */
    private fun logJson(json: String) {
        Log.i(TAG, "BENCH_JSON_BEGIN")
        json.chunked(LOG_CHUNK).forEach { Log.i(TAG, it) }
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

        fun toJson() = JSONObject()
            .put("file", file).put("lang", lang).put("transcript", transcript)
            .put("metric", error.metric.name).put("errorRate", error.value)
            .put("transcribeMs", transcribeMs)
            .put("translation", if (translateMs == null) "skipped" else "ok")
            .put("translateMs", translateMs ?: JSONObject.NULL)
            .put("totalMs", totalMs)
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

        fun toJson(results: List<ClipResult>) = JSONObject()
            .put("model", model).put("clipCount", clipCount)
            .put("medianMs", medianMs).put("p95Ms", p95Ms).put("meanErrorRate", meanError)
            .put("clips", JSONArray(results.map { it.toJson() }))

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
        const val TAG = "CaptionlyBench"
        const val RESULTS_FILE = "bench-results.json"
        const val RUNS = 3
        const val LOG_CHUNK = 3500
        const val SAMPLE_RATE = 16_000
    }
}
