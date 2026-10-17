package com.voicecaption.testutil

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared instrumented-test helper: decodes 16 kHz mono 16-bit PCM WAV files. */
object WavReader {
    /**
     * Parses a 16 kHz mono 16-bit PCM WAV (walks the RIFF chunks to the "data" chunk) into
     * samples in [-1, 1].
     */
    fun toSamples(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12 // past "RIFF", size, "WAVE"
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = buf.getInt(pos + 4)
            if (id == "data") {
                val pcm = ByteBuffer.wrap(bytes, pos + 8, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(pcm.remaining()) { pcm.get().toFloat() / 32768f }
            }
            pos += 8 + len + (len and 1)
        }
        error("No data chunk in WAV")
    }
}
