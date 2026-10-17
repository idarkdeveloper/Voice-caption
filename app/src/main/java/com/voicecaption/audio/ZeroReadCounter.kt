package com.voicecaption.audio

/**
 * Detects a dead [android.media.AudioRecord] that keeps returning zero-length reads: [record]
 * returns true once [limit] zero reads happened in a row; any non-empty read resets the streak.
 */
class ZeroReadCounter(private val limit: Int = DEFAULT_LIMIT) {
    private var zeros = 0

    /** Feeds the result [n] of one read; true means the stream should be treated as ended. */
    fun record(n: Int): Boolean {
        if (n != 0) {
            zeros = 0
            return false
        }
        zeros++
        return zeros >= limit
    }

    companion object {
        /** Consecutive empty reads treated as end of stream. */
        const val DEFAULT_LIMIT = 50
    }
}
