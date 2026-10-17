package com.voicecaption.audio

import com.voicecaption.core.PcmFrame

/**
 * Re-slices arbitrary-size 16-bit PCM reads into exact [frameSize]-sample float frames
 * (`short / 32768f`, so [-1, 1)). Timestamps are `frameIndex * frameSize * 1000 / sampleRate`
 * ms (32 ms steps at the defaults), continuous across [push] calls. Leftover samples that do not
 * fill a frame are carried to the next call.
 *
 * Not thread-safe; use from the single read loop.
 */
class FrameChunker(
    private val frameSize: Int = 512,
    private val sampleRate: Int = 16000,
) {
    private val pending = FloatArray(frameSize)
    private var pendingCount = 0
    private var frameIndex = 0L

    /** Consumes the first [length] samples of [pcm] and returns the frames completed by them. */
    fun push(pcm: ShortArray, length: Int): List<PcmFrame> {
        require(length in 0..pcm.size) { "length $length out of range for array of ${pcm.size}" }
        var out: MutableList<PcmFrame>? = null
        for (i in 0 until length) {
            pending[pendingCount++] = pcm[i] / SCALE
            if (pendingCount == frameSize) {
                val list = out ?: ArrayList<PcmFrame>(2).also { out = it }
                list += PcmFrame(pending.copyOf(), frameIndex * frameSize * 1000L / sampleRate)
                frameIndex++
                pendingCount = 0
            }
        }
        return out ?: emptyList()
    }

    /** Drops carried samples and restarts timestamps at 0. */
    fun reset() {
        pendingCount = 0
        frameIndex = 0
    }

    private companion object {
        const val SCALE = 32768f
    }
}
