package com.voicecaption.caption

import com.voicecaption.core.Caption

/** Turns captions into the text lines the overlay draws. */
object CaptionFormatter {
    /**
     * Non-bilingual: one line per caption, the translation if present, else the source text.
     * Bilingual: the source line followed by the translated line; a caption with no translation
     * yet yields just the source line. If the translation equals the source (the recognizer already
     * output the target language), bilingual mode shows it once.
     */
    fun lines(captions: List<Caption>, bilingual: Boolean): List<String> =
        captions.flatMap { c ->
            val translated = c.translatedText
            when {
                translated == null -> listOf(c.sourceText)
                showsBoth(c, bilingual) -> listOf(c.sourceText, translated)
                else -> listOf(translated)
            }
        }

    /** True when [caption] is drawn as a source line plus a translation line. */
    fun showsBoth(caption: Caption, bilingual: Boolean): Boolean {
        val translated = caption.translatedText ?: return false
        return bilingual && !caption.sourceText.trim().equals(translated.trim(), ignoreCase = true)
    }
}
