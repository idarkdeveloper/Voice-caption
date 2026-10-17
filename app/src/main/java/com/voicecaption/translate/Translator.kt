package com.voicecaption.translate

/** Translates caption text between languages. Pure Kotlin so pipeline tests can fake it. */
interface Translator {
    /**
     * Translates [text] from [sourceLang] to [targetLang] (language tags such as "en" or "pt-BR").
     * Never downloads anything: throws [TranslatorNotReadyException] if the pair's language packs
     * are not on the device, so the caller can fail promptly. Same-language pairs return [text].
     * Throws [IllegalArgumentException] for a language that cannot be translated.
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String

    /** True when [translate] can run now for this pair. False for unsupported languages. */
    suspend fun isReady(sourceLang: String, targetLang: String): Boolean

    /**
     * Downloads whatever [translate] needs for this pair (requires network). [onProgress] is
     * called with `true` when a download starts and `false` when it ends (success or failure);
     * it is not called if nothing needs downloading. Failures, including unsupported languages,
     * are returned as [Result.failure]; cancellation is rethrown.
     */
    suspend fun prepare(sourceLang: String, targetLang: String, onProgress: (Boolean) -> Unit): Result<Unit>
}

/** [Translator.translate] was called before the pair's language packs were downloaded. */
class TranslatorNotReadyException(message: String, cause: Throwable? = null) : Exception(message, cause)
