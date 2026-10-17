package com.voicecaption.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicecaption.core.ModelSize
import com.voicecaption.core.SpeechSegment
import com.voicecaption.testutil.WavReader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a device/emulator and network (downloads the ~160 MB BASE model on first run). */
@RunWith(AndroidJUnit4::class)
class WhisperLocalTest {
    @Test
    fun transcribesHelloWorld() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val modelsDir = File(instrumentation.targetContext.filesDir, "models")
        val manager = ModelManager(modelsDir, HttpUrlConnectionDownloader())
        val dir = File(modelsDir, "base")
        if (!manager.isInstalled(ModelSize.BASE)) {
            manager.install(ModelSize.BASE) {}.getOrThrow()
        }

        val samples = WavReader.toSamples(instrumentation.context.assets.open("hello_world_16k.wav").readBytes())
        val segment = SpeechSegment(samples, 0L, samples.size * 1000L / 16_000)
        val whisper = WhisperLocal(dir, ModelSize.BASE)
        try {
            val text = whisper.transcribe(segment, "en")
            assertTrue("Unexpected transcript: '$text'", text.lowercase().contains("hello"))
        } finally {
            whisper.close()
        }
    }
}
