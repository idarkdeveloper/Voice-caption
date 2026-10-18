package com.voicecaption.caption

import com.voicecaption.core.Caption

/** What the overlay draws: the original-language plate and the translation plate. */
data class PlateLines(val source: List<String>, val translation: List<String>) {
    val isEmpty: Boolean get() = source.isEmpty() && translation.isEmpty()
}

/** Turns captions into the text lines the overlay draws. */
object CaptionFormatter {
    /**
     * Non-bilingual: only the translation plate, one line per caption (the translation if present,
     * else the source text). Bilingual: the source plate holds the original text of every caption
     * and the translation plate its translation; a caption not translated yet appears in the
     * source plate only. If the translation equals the source (the recognizer already output the
     * target language) it is drawn once, in the translation plate.
     */
    fun plates(captions: List<Caption>, bilingual: Boolean): PlateLines {
        val source = mutableListOf<String>()
        val translation = mutableListOf<String>()
        for (c in captions) {
            val translated = c.translatedText
            when {
                !bilingual -> translation += translated ?: c.sourceText
                translated == null -> source += c.sourceText
                showsBoth(c, bilingual = true) -> {
                    source += c.sourceText
                    translation += translated
                }
                else -> translation += translated
            }
        }
        return PlateLines(source, translation)
    }

    /** True when [caption] is drawn as an original line plus a different translation line. */
    fun showsBoth(caption: Caption, bilingual: Boolean): Boolean {
        val translated = caption.translatedText ?: return false
        return bilingual && !caption.sourceText.trim().equals(translated.trim(), ignoreCase = true)
    }
}
