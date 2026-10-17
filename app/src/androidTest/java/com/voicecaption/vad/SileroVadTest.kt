package com.voicecaption.vad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicecaption.testutil.WavReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a device/emulator (loads the sherpa-onnx native library). */
@RunWith(AndroidJUnit4::class)
class SileroVadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun speechFramesAreMostlyDetected() {
        val samples = WavReader.toSamples(InstrumentationRegistry.getInstrumentation().context.assets.open("hello_world_16k.wav").readBytes())
        SileroVad(context).use { vad ->
            val flags = frames(samples).map { vad.isSpeech(it) }
            val energetic = frames(samples).map { f -> f.maxOf { kotlin.math.abs(it) } > 0.05f }
            val inSpeech = flags.filterIndexed { i, _ -> energetic[i] }
            assertTrue("no energetic frames", inSpeech.isNotEmpty())
            val ratio = inSpeech.count { it }.toFloat() / inSpeech.size
            assertTrue("only $ratio of spoken frames detected", ratio > 0.6f)
        }
    }

    @Test
    fun silenceIsNeverSpeech() {
        SileroVad(context).use { vad ->
            assertTrue(frames(FloatArray(32_000)).none { vad.isSpeech(it) })
        }
    }

    @Test
    fun closeIsIdempotentAndIsSpeechThrowsAfterClose() {
        val vad = SileroVad(context)
        vad.close()
        vad.close()
        try {
            vad.isSpeech(FloatArray(SileroVad.FRAME_SAMPLES))
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("SileroVad is closed", e.message)
        }
    }

    private fun frames(samples: FloatArray): List<FloatArray> =
        (0 until samples.size / SileroVad.FRAME_SAMPLES).map {
            samples.copyOfRange(it * SileroVad.FRAME_SAMPLES, (it + 1) * SileroVad.FRAME_SAMPLES)
        }
}
