package com.voicecaption.translate

/**
 * The 59 languages of ML Kit on-device Translation (`TranslateLanguage` in
 * com.google.mlkit:translate; its codes are ISO 639-1 lower-case). Kept here as plain data so the
 * normalization is JVM-testable; the instrumented test cross-checks it against ML Kit.
 */
internal val TRANSLATE_LANGUAGES: Set<String> = (
    "af ar be bg bn ca cs cy da de el en eo es et fa fi fr ga gl gu he hi hr ht hu id is it ja ka " +
        "kn ko lt lv mk mr ms mt nl no pl pt ro ru sk sl sq sv sw ta te th tl tr uk ur vi zh"
    ).split(' ').toSet()

/** Old Android/Java language codes -> ML Kit's codes. */
private val LEGACY_CODES = mapOf("iw" to "he", "in" to "id")

/**
 * Maps an app language tag to an ML Kit language code: lower-cased primary subtag ("pt-BR" ->
 * "pt"), legacy Android codes mapped (iw->he, in->id). Returns null for blank, "auto", or any
 * language ML Kit cannot translate.
 */
internal fun normalizeTranslateLanguage(tag: String): String? {
    val primary = tag.trim().lowercase().split('-', '_').first()
    val code = LEGACY_CODES[primary] ?: primary
    return code.takeIf { it in TRANSLATE_LANGUAGES }
}

/** A normalized (source, target) pair; also the key for caching one ML Kit client per pair. */
internal data class LanguagePair(val source: String, val target: String) {
    val isSameLanguage: Boolean get() = source == target
}

/** Normalizes both tags; throws [IllegalArgumentException] naming the unsupported one. */
internal fun languagePair(sourceLang: String, targetLang: String): LanguagePair {
    fun code(tag: String, role: String) = normalizeTranslateLanguage(tag)
        ?: throw IllegalArgumentException("Unsupported $role language for on-device translation: '$tag'")
    return LanguagePair(code(sourceLang, "source"), code(targetLang, "target"))
}
