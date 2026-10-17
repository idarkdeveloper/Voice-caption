package com.voicecaption.asr

import com.voicecaption.core.ModelSize

/** One downloadable model file: [name] is the on-disk name inside the model directory. */
data class ModelFile(val name: String, val url: String, val sha256: String, val sizeBytes: Long)

/**
 * The files that make up each [ModelSize]. Injectable so tests can use tiny fake entries;
 * [DEFAULT] is the real catalog.
 */
class ModelCatalog(private val entries: Map<ModelSize, List<ModelFile>>) {
    fun files(size: ModelSize): List<ModelFile> = entries[size].orEmpty()

    /** Total download size, for the UI ("Download 143 MB"). */
    fun totalBytes(size: ModelSize): Long = files(size).sumOf { it.sizeBytes }

    companion object {
        // Source: sherpa-onnx pre-converted multilingual Whisper models (int8), published by the
        // sherpa-onnx author on Hugging Face (not the English-only ".en" variants):
        //   https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base
        //   https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small
        // URLs are pinned to these repo commits (not `main`). SHA-256 values for the two .onnx
        // files match the Hugging Face LFS metadata and were re-verified by downloading and
        // running sha256sum; tokens.txt (not LFS) was hashed the same way.
        private const val BASE_REV = "bb53ee204431c90d314c1cc08d28d23e5b7927cc"
        private const val SMALL_REV = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
        private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/$BASE_REV"
        private const val SMALL_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/$SMALL_REV"

        // Both repos ship the identical multilingual tokens file.
        private const val TOKENS_SHA256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
        private const val TOKENS_BYTES = 816_730L

        // On-disk names are neutral (encoder/decoder/tokens) so the recognizer config is size-agnostic.
        val DEFAULT = ModelCatalog(
            mapOf(
                ModelSize.BASE to listOf(
                    ModelFile(
                        "encoder.int8.onnx", "$BASE_URL/base-encoder.int8.onnx",
                        "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11", 29_120_534L,
                    ),
                    ModelFile(
                        "decoder.int8.onnx", "$BASE_URL/base-decoder.int8.onnx",
                        "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d", 130_672_026L,
                    ),
                    ModelFile("tokens.txt", "$BASE_URL/base-tokens.txt", TOKENS_SHA256, TOKENS_BYTES),
                ),
                ModelSize.SMALL to listOf(
                    ModelFile(
                        "encoder.int8.onnx", "$SMALL_URL/small-encoder.int8.onnx",
                        "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9", 112_442_483L,
                    ),
                    ModelFile(
                        "decoder.int8.onnx", "$SMALL_URL/small-decoder.int8.onnx",
                        "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee", 262_226_114L,
                    ),
                    ModelFile("tokens.txt", "$SMALL_URL/small-tokens.txt", TOKENS_SHA256, TOKENS_BYTES),
                ),
            ),
        )
    }
}
