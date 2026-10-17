package com.voicecaption.asr

import java.io.File
import java.io.FileNotFoundException

/**
 * Maps an app language tag to Whisper's language code: primary subtag, lower-case ("pt-BR" ->
 * "pt"). Blank or "auto" becomes "", which makes Whisper detect the language itself.
 */
internal fun normalizeWhisperLanguage(language: String): String {
    val primary = language.trim().lowercase().split('-', '_').first()
    return if (primary == "auto") "" else primary
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
