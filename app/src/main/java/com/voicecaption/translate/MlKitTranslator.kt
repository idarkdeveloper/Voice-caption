package com.voicecaption.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator as MlKitClient
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * On-device translation with ML Kit. Each language pack (~30 MB) is downloaded once by [prepare];
 * [translate] then runs fully offline. Translating via English: ML Kit chains source->en->target,
 * so a non-English pair needs both packs, which [isReady] and [prepare] account for.
 *
 * ML Kit initializes itself from the app context, so [context] is currently unused but kept in the
 * signature for the app's wiring. Call [close] to release the cached clients.
 */
class MlKitTranslator(@Suppress("UNUSED_PARAMETER") context: Context) : Translator {
    private val modelManager = RemoteModelManager.getInstance()
    private val clients = ConcurrentHashMap<LanguagePair, MlKitClient>()

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val pair = languagePair(sourceLang, targetLang)
        if (pair.isSameLanguage) return text
        if (!isDownloaded(pair)) {
            throw TranslatorNotReadyException(
                "Language pack for ${pair.source} -> ${pair.target} is not downloaded",
            )
        }
        return client(pair).translate(text).await()
    }

    override suspend fun isReady(sourceLang: String, targetLang: String): Boolean {
        val pair = runCatching { languagePair(sourceLang, targetLang) }.getOrNull() ?: return false
        return pair.isSameLanguage || isDownloaded(pair)
    }

    /**
     * Downloads with `requireWifi = false`: the user explicitly triggers the download, so cellular
     * is allowed; the packs are small (~30 MB each) and are downloaded only once.
     */
    override suspend fun prepare(
        sourceLang: String,
        targetLang: String,
        onProgress: (Boolean) -> Unit,
    ): Result<Unit> {
        return try {
            val pair = languagePair(sourceLang, targetLang)
            if (!pair.isSameLanguage && !isDownloaded(pair)) {
                onProgress(true)
                try {
                    client(pair).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                } finally {
                    onProgress(false)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadedLanguages(): Set<String> = try {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptySet()
    }

    /** Releases all cached ML Kit clients. The instance stays usable; clients are recreated lazily. */
    fun close() {
        val all = clients.values.toList()
        clients.clear()
        all.forEach { it.close() }
    }

    private fun client(pair: LanguagePair): MlKitClient = clients.getOrPut(pair) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(pair.source)
                .setTargetLanguage(pair.target)
                .build(),
        )
    }

    /** English is the pivot: ML Kit needs the pack of every non-English side of the pair. */
    private suspend fun isDownloaded(pair: LanguagePair): Boolean =
        listOf(pair.source, pair.target).filter { it != "en" }.all { code ->
            modelManager.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()
        }
}
