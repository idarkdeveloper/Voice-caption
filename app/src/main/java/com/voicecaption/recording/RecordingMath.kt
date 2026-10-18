package com.voicecaption.recording

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android-free numbers and names for [ScreenRecorder]. */
object RecordingMath {
    const val FPS = 30
    const val MAX_LONG_SIDE = 1920

    /**
     * Video size for a screen of [width] x [height] px: scaled down so the longer side is at most
     * [maxLongSide], never up, and both sides rounded down to even numbers (H.264 needs that).
     */
    fun videoSize(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val longSide = maxOf(w, h)
        val scale = if (longSide > maxLongSide) maxLongSide.toDouble() / longSide else 1.0
        fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(w * scale) to even(h * scale)
    }

    /** H.264 bit rate for a size and frame rate: about 0.1 bit per pixel, between 2 and 12 Mbit/s. */
    fun videoBitrate(width: Int, height: Int, fps: Int = FPS): Int =
        (width.toLong() * height * fps / 10).coerceIn(2_000_000L, 12_000_000L).toInt()

    /** File name for a recording started at [millis], e.g. `TransCap-20261018-103700.mp4`. */
    fun fileName(millis: Long, locale: Locale = Locale.US): String =
        "TransCap-" + SimpleDateFormat("yyyyMMdd-HHmmss", locale).format(Date(millis)) + ".mp4"
}
