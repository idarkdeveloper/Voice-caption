package com.voicecaption.asr

import com.voicecaption.core.ModelSize
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** Fetches [url] into [dest], reporting 0..1 progress for that single file. */
fun interface Downloader {
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit)
}

/**
 * Installs Whisper models under [modelsDir]/<size>/ (e.g. `base/`).
 *
 * Install is all-or-nothing: every file is downloaded into a staging directory `<size>.part/`,
 * SHA-256-verified, and only then is the staging directory atomically renamed to `<size>/`. On any
 * failure the staging directory is deleted, so a partial or corrupt model is never visible.
 * [CancellationException] is cleaned up after and rethrown, never wrapped in a [Result].
 */
class ModelManager(
    private val modelsDir: File,
    private val downloader: Downloader,
    private val catalog: ModelCatalog = ModelCatalog.DEFAULT,
) {
    private fun dirFor(size: ModelSize) = File(modelsDir, size.name.lowercase())

    /**
     * True when every catalog file exists with the expected size. Hashes are verified at install
     * time only; this is a cheap query-time check.
     */
    fun isInstalled(size: ModelSize): Boolean {
        val files = catalog.files(size)
        if (files.isEmpty()) return false
        val dir = dirFor(size)
        return files.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }
    }

    /** Deletes the installed model for [size]; true if a model directory existed and was removed. */
    fun delete(size: ModelSize): Boolean {
        val dir = dirFor(size)
        return dir.exists() && dir.deleteRecursively()
    }

    /** Downloads and verifies [size]; [onProgress] reports 0..1 across all files, ending at 1.0. */
    suspend fun install(size: ModelSize, onProgress: (Float) -> Unit): Result<File> {
        val target = dirFor(size)
        val staging = File(modelsDir, "${target.name}.part")
        try {
            val files = catalog.files(size)
            if (files.isEmpty()) return Result.failure(IOException("No model files catalogued for $size"))
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("Cannot create ${staging.path}")

            val weights = files.map { it.sizeBytes.coerceAtLeast(1L).toDouble() }
            val total = weights.sum()
            var done = 0.0
            for ((i, file) in files.withIndex()) {
                val dest = File(staging, file.name)
                val base = done
                downloader.download(file.url, dest) { p ->
                    onProgress(((base + weights[i] * p.coerceIn(0f, 1f)) / total).toFloat())
                }
                val actual = sha256(dest)
                if (!actual.equals(file.sha256, ignoreCase = true)) {
                    throw IOException("Checksum mismatch for ${file.name}: expected ${file.sha256}, got $actual")
                }
                done += weights[i]
            }

            target.deleteRecursively()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            onProgress(1f)
            return Result.success(target)
        } catch (e: CancellationException) {
            staging.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            return Result.failure(e)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
