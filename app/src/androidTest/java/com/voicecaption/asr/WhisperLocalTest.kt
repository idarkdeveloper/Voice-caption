package com.voicecaption.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicecaption.core.ModelSize
import com.voicecaption.core.SpeechSegment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        val segment = loadWav(instrumentation.context.assets.open("hello_world_16k.wav").readBytes())
        val whisper = WhisperLocal(dir, ModelSize.BASE)
        try {
            val text = whisper.transcribe(segment, "en")
            assertTrue("Unexpected transcript: '$text'", text.lowercase().contains("hello"))
        } finally {
            whisper.close()
        }
    }

    /** Parses a 16 kHz mono 16-bit PCM WAV (walks the RIFF chunks to the "data" chunk). */
    private fun loadWav(bytes: ByteArray): SpeechSegment {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12 // past "RIFF", size, "WAVE"
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = buf.getInt(pos + 4)
            if (id == "data") {
                val pcm = ByteBuffer.wrap(bytes, pos + 8, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = FloatArray(pcm.remaining()) { pcm.get().toFloat() / 32768f }
                return SpeechSegment(samples, 0L, samples.size * 1000L / 16_000)
            }
            pos += 8 + len + (len and 1)
        }
        error("No data chunk in WAV")
    }
}
