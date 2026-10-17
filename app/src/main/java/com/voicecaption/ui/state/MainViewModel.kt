package com.voicecaption.ui.state

import androidx.lifecycle.ViewModel
import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.ModelSize
import com.voicecaption.settings.SupportedLanguages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for the Compose screens. Pure Kotlin apart from [ViewModel]; every dependency is
 * injected so it runs in JVM tests.
 *
 * Failures from [store] writes and [readiness] queries are reported to [onError] and never
 * thrown: a failed read counts as "not installed / not ready", a failed write is dropped (the UI
 * keeps showing the persisted value). Cancellation is never swallowed.
 *
 * [scope] is owned by this view model and cancelled in [onCleared]. [downloads] should live
 * longer (an app-scoped controller), so downloads survive the screen.
 *
 * Starting and stopping capture is done by the Activity (the projection consent needs an Activity
 * result): it shows Start when [MainUiState.canStart], launches `Permissions.projectionIntent`,
 * then calls `CaptureService.start`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val store: SettingsStore,
    private val readiness: ReadinessSource,
    private val downloads: DownloadController,
    private val captureRunning: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
) : ViewModel() {
    private val permissions = MutableStateFlow(PermissionState())
    private val refreshTick = MutableStateFlow(0)

    private val snapshots = combine(store.settings, refreshTick) { s, _ -> s }
        .mapLatest { s ->
            ReadinessSnapshot(
                modelSize = s.activeModel,
                sourceLang = s.sourceLang,
                targetLang = s.targetLang,
                installedModels = ModelSize.entries
                    .filter { safely(false, onError) { readiness.isModelInstalled(it) } }.toSet(),
                translationReady = safely(false, onError) { readiness.isTranslationReady(s.sourceLang, s.targetLang) },
            )
        }

    /** Everything the screens render; see [MainUiState]. */
    val uiState: StateFlow<MainUiState> = combine(
        store.settings,
        permissions,
        snapshots,
        downloads.state,
        captureRunning,
    ) { settings, perms, snapshot, download, running ->
        reduceUiState(settings, perms, snapshot, download, running)
    }.stateIn(scope, SharingStarted.Eagerly, reduceUiState(store.settings.value, PermissionState(), null, DownloadState.Idle, false))

    init {
        // A download or failure belongs to one request; changing the pair or size drops it.
        scope.launch {
            store.settings.map { Triple(it.activeModel, it.sourceLang, it.targetLang) }
                .distinctUntilChanged()
                .drop(1)
                .collect { downloads.cancel() }
        }
        // Re-check what is installed whenever a download run ends.
        scope.launch {
            downloads.state.collect { if (it is DownloadState.Done || it is DownloadState.Failed) refreshTick.value++ }
        }
    }

    /** Pushes freshly read permission flags (call from `onResume`) and re-checks installed files. */
    fun refreshPermissions(state: PermissionState) {
        permissions.value = state
        refreshTick.value++
    }

    /** Sets the spoken language; codes that are not a concrete supported language are ignored. */
    fun onSourceLanguage(code: String) = update { it.copy(sourceLang = SupportedLanguages.coerce(code, it.sourceLang)) }

    /** Sets the caption language; unsupported codes are ignored. */
    fun onTargetLanguage(code: String) = update { it.copy(targetLang = SupportedLanguages.coerce(code, it.targetLang)) }

    /** Sets the caption font size in sp (clamped by the repository). */
    fun onFontSize(sp: Int) = update { it.copy(fontSizeSp = sp) }

    /** Sets the caption background opacity (clamped by the repository). */
    fun onOpacity(value: Float) = update { it.copy(opacity = value) }

    /** Shows the original text above the translation. */
    fun onBilingual(enabled: Boolean) = update { it.copy(bilingual = enabled) }

    /** Selects the Whisper model size; readiness is recomputed and may now need a download. */
    fun onModelSize(size: ModelSize) = update { it.copy(modelSize = size, autoModel = false) }

    /** Lets the app pick the speech model from the spoken language. */
    fun onAutoModel() = update { it.copy(autoModel = true) }

    /** Downloads the speech model and language pack the current settings still need. */
    fun startDownloads() {
        val s = store.settings.value
        downloads.start(s.activeModel, s.sourceLang, s.targetLang)
    }

    /** Retries the last failed download. */
    fun retryDownloads() = downloads.retry()

    /** Cancels a running download. */
    fun cancelDownloads() = downloads.cancel()

    /** Deletes the installed model for [size]. Refused while captioning is running. */
    fun deleteModel(size: ModelSize) {
        if (captureRunning.value) return
        scope.launch {
            safely(false, onError) { readiness.deleteModel(size) }
            refreshTick.value++
        }
    }

    private fun update(transform: (CaptionSettings) -> CaptionSettings) {
        scope.launch { safely(Unit, onError) { store.update(transform) } }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
