package com.voicecaption.core

import kotlin.math.pow

/** Android-free colour helpers for the caption colour settings. Colours are ARGB ints. */
object ColorMath {
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val BLACK: Int = 0xFF000000.toInt()

    /** WCAG AA for normal text. Below this the colour pair is flagged in Settings. */
    const val MIN_READABLE_CONTRAST = 4.5

    /** Forces full opacity: caption opacity is a separate setting and only applies to the plate. */
    fun opaque(color: Int): Int = color or BLACK

    /** Parses "#RRGGBB" or "RRGGBB" (any case) into an opaque colour; null if it is not exactly that. */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#")
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return opaque(t.toInt(16))
    }

    /** "#RRGGBB" in upper case, ignoring alpha. */
    fun formatHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio between two colours, 1 (identical) to 21 (black on white). */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Black or white, whichever reads better on [background] (for a tick mark on a swatch). */
    fun readableOn(background: Int): Int =
        if (contrastRatio(WHITE, background) >= contrastRatio(BLACK, background)) WHITE else BLACK
}
