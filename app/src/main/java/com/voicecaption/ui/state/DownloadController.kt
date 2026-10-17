package com.voicecaption.ui.state

import com.voicecaption.asr.ModelManager
import com.voicecaption.core.ModelSize
import com.voicecaption.translate.Translator
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Progress of the speech-model and language-pack downloads. */
sealed interface DownloadState {
    /** Nothing running. */
    data object Idle : DownloadState

    /** Downloading; [progress] is overall 0..1 (never decreases within a run), [label] says what. */
    data class Downloading(val progress: Float, val label: String) : DownloadState

    /** The run failed; [message] is human-readable (no stack trace). */
    data class Failed(val message: String) : DownloadState

    /** Everything requested is on the device. */
    data object Done : DownloadState
}

/** The slice of [ModelManager] the controller needs, so it can be tested without a file system. */
interface ModelInstaller {
    /** True when the model for [size] is fully installed. */
    fun isInstalled(size: ModelSize): Boolean

    /** Downloads [size], reporting 0..1; failures are returned, cancellation rethrown. */
    suspend fun install(size: ModelSize, onProgress: (Float) -> Unit): Result<File>
}

/** Adapts this [ModelManager] to a [ModelInstaller]. */
fun ModelManager.asInstaller(): ModelInstaller = object : ModelInstaller {
    override fun isInstalled(size: ModelSize) = this@asInstaller.isInstalled(size)
    override suspend fun install(size: ModelSize, onProgress: (Float) -> Unit) =
        this@asInstaller.install(size, onProgress)
}

/**
 * Runs the Whisper model install and the ML Kit language-pack download as one observable job.
 *
 * Only what is missing is downloaded (an installed model and a ready pair, including any
 * same-language pair, are skipped). At most one run is active: [start] while downloading is
 * ignored, and a new run waits for a cancelled predecessor to finish unwinding so installs never
 * overlap. Any failure ends in [DownloadState.Failed]; cancellation ends in [DownloadState.Idle]
 * and is never reported as a failure.
 *
 * Progress: with both parts, the model is 0..0.9 and the pack 0.9..1.0; with one part it takes
 * the whole range. ML Kit gives no fraction, so during the pack the bar sits at the start of its
 * band until it finishes. [scope] should outlive the UI so a download survives rotation.
 */
class DownloadController(
    private val installer: ModelInstaller,
    private val translator: Translator,
    private val scope: CoroutineScope,
) {
    private data class Request(val size: ModelSize, val source: String, val target: String)

    private val lock = Any()
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    private var job: Job? = null
    private var generation = 0
    private var request: Request? = null

    /** Current download state. */
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    /** Downloads what [size] and the [source] to [target] pair still need. Ignored while downloading. */
    fun start(size: ModelSize, source: String, target: String) = launch(Request(size, source, target))

    /** Re-runs the last request after a failure; ignored in any other state. */
    fun retry() {
        val last = synchronized(lock) { if (_state.value is DownloadState.Failed) request else null }
        if (last != null) launch(last)
    }

    /** Cancels any running download and returns to [DownloadState.Idle]. */
    fun cancel() {
        synchronized(lock) {
            generation++
            job?.cancel()
            _state.value = DownloadState.Idle
        }
    }

    private fun launch(req: Request) {
        synchronized(lock) {
            if (_state.value is DownloadState.Downloading) return
            request = req
            _state.value = DownloadState.Downloading(0f, LABEL_PREPARING)
            val previous = job
            val token = ++generation
            job = scope.launch {
                previous?.join()
                val outcome = try {
                    execute(req) { progress, label -> publish(token, DownloadState.Downloading(progress, label)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                publish(token, outcome.fold({ DownloadState.Done }, { DownloadState.Failed(failureMessage(it)) }))
            }
        }
    }

    /** Applies [next] only if [token] is still the current run (a cancelled run never writes). */
    private fun publish(token: Int, next: DownloadState) {
        synchronized(lock) { if (generation == token) _state.value = next }
    }

    private suspend fun execute(req: Request, report: (Float, String) -> Unit): Result<Unit> {
        val needModel = !installer.isInstalled(req.size)
        val needPack = !translator.isReady(req.source, req.target)
        val modelWeight = if (needModel && needPack) MODEL_SHARE else if (needModel) 1f else 0f
        if (needModel) {
            report(0f, LABEL_MODEL)
            installer.install(req.size) { p -> report(p.coerceIn(0f, 1f) * modelWeight, LABEL_MODEL) }
                .onFailure { return Result.failure(it) }
        }
        if (needPack) {
            report(modelWeight, LABEL_PACK)
            translator.prepare(req.source, req.target) { }.onFailure { return Result.failure(it) }
        }
        return Result.success(Unit)
    }

    private companion object {
        const val MODEL_SHARE = 0.9f
        const val LABEL_PREPARING = "Preparing download"
        const val LABEL_MODEL = "Downloading speech model"
        const val LABEL_PACK = "Downloading language pack"
    }
}

/** A short, user-facing description of why a download failed. */
internal fun failureMessage(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } ->
            "No internet connection"
        chain.any { it.message?.contains("Checksum mismatch", ignoreCase = true) == true } ->
            "The download was corrupted. Please try again"
        else -> "Download failed. Please try again"
    }
}
