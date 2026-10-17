package com.voicecaption.vad

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * [VoiceActivityDetector] backed by the Silero VAD model through the sherpa-onnx `Vad` API (no
 * extra onnxruntime dependency). The model is read straight from the APK assets
 * (`silero_vad.onnx`, see assets/README.md).
 *
 * Feed consecutive 512-sample, 16 kHz frames in [-1, 1] ([FRAME_SAMPLES]); the detector is
 * stateful, so frames must arrive in order. [isSpeech] returns sherpa's current speech state after
 * the frame.
 *
 * Tuning: sherpa applies its own smoothing (min speech / min silence), and
 * [VadSegmenter] already adds a 400 ms hangover and a minimum speech length. To keep the two from
 * stacking, sherpa's durations are set to about one window (32 ms) so this detector is close to
 * the raw per-frame Silero decision; [VadSegmenter] owns the real smoothing.
 *
 * Not thread-safe: call from the single audio-processing thread. [close] is idempotent.
 */
class SileroVad(context: Context) : VoiceActivityDetector, AutoCloseable {
    private var vad: Vad? = Vad(
        assetManager = context.applicationContext.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = THRESHOLD,
                minSilenceDuration = MIN_DURATION_SEC,
                minSpeechDuration = MIN_DURATION_SEC,
                windowSize = FRAME_SAMPLES,
                maxSpeechDuration = MAX_SPEECH_SEC,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )

    /** Feeds one [FRAME_SAMPLES]-sample frame and returns whether speech is currently detected. */
    override fun isSpeech(frame: FloatArray): Boolean {
        val v = checkNotNull(vad) { "SileroVad is closed" }
        require(frame.size == FRAME_SAMPLES) { "Expected $FRAME_SAMPLES samples, got ${frame.size}" }
        v.acceptWaveform(frame)
        val speech = v.isSpeechDetected()
        // We only need the live state; drop the completed segments sherpa queues so they don't pile up.
        while (!v.empty()) v.pop()
        return speech
    }

    /** Releases the native model. Safe to call more than once; [isSpeech] throws afterwards. */
    override fun close() {
        vad?.release()
        vad = null
    }

    companion object {
        /** Samples per frame the model expects (32 ms at 16 kHz). */
        const val FRAME_SAMPLES = 512
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val THRESHOLD = 0.5f
        private const val MIN_DURATION_SEC = FRAME_SAMPLES / SAMPLE_RATE.toFloat()
        private const val MAX_SPEECH_SEC = 30f
    }
}
