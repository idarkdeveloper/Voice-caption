package com.voicecaption.overlay

import kotlin.math.roundToInt

/** Android-free arithmetic behind [OverlayRenderer]. */
object OverlayMath {
    /** Lowest plate opacity; below this white text on busy video is hard to read. */
    const val MIN_OPACITY = 0.5f

    /** Max plate width as a fraction of the screen width. */
    const val MAX_WIDTH_FRACTION = 0.9f

    /** Plate background alpha (0..255) for a user opacity, floored at [MIN_OPACITY] and clamped to 1.0. */
    fun plateAlpha(opacity: Float): Int {
        val o = if (opacity.isNaN()) MIN_OPACITY else opacity.coerceIn(MIN_OPACITY, 1f)
        return (o * 255f).roundToInt()
    }

    /** Clamp a requested vertical offset to [min]..[max]; an inverted range yields [min]. */
    fun clampOffsetY(requested: Int, min: Int, max: Int): Int =
        if (max < min) min else requested.coerceIn(min, max)

    /** Maximum plate width in pixels: 90% of the screen width. */
    fun maxPlateWidthPx(screenWidthPx: Int): Int =
        (screenWidthPx.coerceAtLeast(0) * MAX_WIDTH_FRACTION).toInt()
}
