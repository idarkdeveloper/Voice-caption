package com.voicecaption.settings

import com.voicecaption.asr.normalizeWhisperLanguage
import com.voicecaption.translate.TRANSLATE_LANGUAGES
import com.voicecaption.translate.normalizeTranslateLanguage
import java.util.Locale

/**
 * Languages the user can pick, for both the spoken (source) and caption (target) side. A language
 * is listed only if Whisper can transcribe it AND ML Kit can translate it, so any choice works as
 * either end of the pipeline. There is deliberately no "auto" entry: the pipeline needs a concrete
 * source language.
 *
 * Both sides use ISO 639-1 codes, so the intersection is taken on canonical codes, i.e. codes that
 * each side's own normalization maps to themselves. This keeps "zh" (Whisper: Chinese; ML Kit:
 * Chinese), "he" (legacy "iw" is accepted on input only) and "no" (Whisper's "no" covers
 * Norwegian; "nn"/"nb" are not ML Kit codes) while dropping Whisper-only ("yue", "jw", "haw", ...)
 * and ML Kit-only ("eo", "ga", ...) languages.
 */
object SupportedLanguages {
    private val englishComparator = compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { englishName(it) }
        .thenBy { it }

    /** Supported language codes, sorted by English display name, without duplicates. */
    val codes: List<String> = TRANSLATE_LANGUAGES
        .filter { normalizeWhisperLanguage(it) == it && normalizeTranslateLanguage(it) == it }
        .sortedWith(englishComparator)

    private val codeSet: Set<String> = codes.toSet()

    private fun englishName(code: String) = displayName(code, Locale.ENGLISH)

    /** The language's name in [locale] (e.g. "Spanish"), capitalized for list display. */
    fun displayName(code: String, locale: Locale = Locale.getDefault()): String =
        Locale(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }

    /** True when [code] is exactly one of [codes] (no normalization). */
    fun isSupported(code: String): Boolean = code in codeSet

    /**
     * Normalizes [code] (primary subtag, legacy codes such as "iw" -> "he") and returns it when
     * supported, else [fallback]. "auto", blank and unknown codes give [fallback].
     */
    fun coerce(code: String, fallback: String): String {
        val normalized = normalizeTranslateLanguage(code)
        return if (normalized != null && normalized in codeSet) normalized else fallback
    }
}
