package com.voicecaption.asr

import java.io.File
import java.io.FileNotFoundException

/**
 * The 99 language codes of the multilingual Whisper models, from `LANGUAGES` in
 * https://github.com/openai/whisper/blob/main/whisper/tokenizer.py (the later "yue" of large-v3
 * is excluded: base/small do not know it). Unknown codes must never reach sherpa-onnx's native
 * layer, which can abort the process instead of throwing.
 */
internal val WHISPER_LANGUAGES: Set<String> = (
    "en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th " +
        "ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl " +
        "mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg " +
        "as tt haw ln ha ba jw su"
    ).split(' ').toSet()

/** Old Android/Java language codes -> Whisper's codes (Whisper uses "jw" for Javanese). */
private val LEGACY_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jv" to "jw")

/**
 * Maps an app language tag to Whisper's language code: lower-cased primary subtag ("pt-BR" ->
 * "pt"), legacy Android codes mapped (iw->he, in->id, ji->yi, jv->jw). Returns "" (Whisper
 * auto-detects) for blank, "auto", or any code outside [WHISPER_LANGUAGES].
 */
internal fun normalizeWhisperLanguage(language: String): String {
    val primary = language.trim().lowercase().split('-', '_').first()
    val code = LEGACY_CODES[primary] ?: primary
    return if (code in WHISPER_LANGUAGES) code else ""
}

/** 2-4 inference threads: half the cores, so the capture/VAD/UI threads keep some headroom. */
internal fun whisperThreads(availableProcessors: Int): Int = (availableProcessors / 2).coerceIn(2, 4)

/** Paths of the files [ModelManager] installs into a model directory. */
internal class WhisperFiles private constructor(val encoder: String, val decoder: String, val tokens: String) {
    companion object {
        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.int8.onnx"
        const val TOKENS = "tokens.txt"

        /** Throws [FileNotFoundException] naming the missing file, rather than a native-load crash. */
        fun resolve(dir: File): WhisperFiles {
            fun file(name: String) = File(dir, name).also {
                if (!it.isFile) throw FileNotFoundException("Whisper model file missing: ${it.path}")
            }
            return WhisperFiles(file(ENCODER).path, file(DECODER).path, file(TOKENS).path)
        }
    }
}
