package com.voicecaption.caption

import com.voicecaption.core.Caption

/** Turns captions into the text lines the overlay draws. */
object CaptionFormatter {
    /**
     * Non-bilingual: one line per caption, the translation if present, else the source text.
     * Bilingual: the source line followed by the translated line; a caption with no translation
     * yet yields just the source line.
     */
    fun lines(captions: List<Caption>, bilingual: Boolean): List<String> =
        captions.flatMap { c ->
            val translated = c.translatedText
            when {
                translated == null -> listOf(c.sourceText)
                bilingual -> listOf(c.sourceText, translated)
                else -> listOf(translated)
            }
        }
}
