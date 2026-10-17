package com.voicecaption.ui.state

import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.ModelSize
import kotlinx.coroutines.flow.StateFlow

/** Runtime permission flags, read from the system by `Permissions.current` and pushed in on resume. */
data class PermissionState(
    val overlay: Boolean = false,
    val notifications: Boolean = false,
    val recordAudio: Boolean = false,
)

/** Settings access for the view model; the app's implementation wraps the DataStore repository. */
interface SettingsStore {
    /** Latest validated settings. */
    val settings: StateFlow<CaptionSettings>

    /** Applies [transform] to the stored settings (the repository validates the result). */
    suspend fun update(transform: (CaptionSettings) -> CaptionSettings)
}

/** On-device readiness queries and model deletion for the view model. */
interface ReadinessSource {
    /** True when the Whisper model for [size] is installed. */
    suspend fun isModelInstalled(size: ModelSize): Boolean

    /** True when the [source] to [target] translation can run offline (always true for the same language). */
    suspend fun isTranslationReady(source: String, target: String): Boolean

    /** Deletes the installed model for [size]; true if something was removed. */
    suspend fun deleteModel(size: ModelSize): Boolean
}

/** What is installed, computed for exactly one (model size, source, target) combination. */
data class ReadinessSnapshot(
    val modelSize: ModelSize,
    val sourceLang: String,
    val targetLang: String,
    val installedModels: Set<ModelSize>,
    val translationReady: Boolean,
)

/** Everything the screens render. */
data class MainUiState(
    val settings: CaptionSettings = CaptionSettings(),
    val overlayGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val recordAudioGranted: Boolean = false,
    /** The selected model size is installed. */
    val modelInstalled: Boolean = false,
    /** Every installed model size (for the storage rows). */
    val installedModels: Set<ModelSize> = emptySet(),
    /** The selected language pair can translate offline (same-language needs no pack). */
    val translationReady: Boolean = false,
    val download: DownloadState = DownloadState.Idle,
    /** Captioning is running right now. */
    val running: Boolean = false,
    val nextStep: SetupStep = SetupStep.OVERLAY_PERMISSION,
    /** "Start captions" may be enabled. */
    val canStart: Boolean = false,
)

/**
 * Pure combinator for [MainUiState]. A [readiness] computed for different settings (or not yet
 * computed) counts as "not installed / not ready", so a stale answer can never enable Start.
 */
fun reduceUiState(
    settings: CaptionSettings,
    permissions: PermissionState,
    readiness: ReadinessSnapshot?,
    download: DownloadState,
    running: Boolean,
): MainUiState {
    val fresh = readiness?.takeIf {
        it.modelSize == settings.modelSize && it.sourceLang == settings.sourceLang && it.targetLang == settings.targetLang
    }
    val inputs = ReadinessInputs(
        overlayGranted = permissions.overlay,
        notificationsGranted = permissions.notifications,
        recordAudioGranted = permissions.recordAudio,
        modelInstalled = fresh != null && settings.modelSize in fresh.installedModels,
        translationReady = fresh?.translationReady == true,
    )
    return MainUiState(
        settings = settings,
        overlayGranted = inputs.overlayGranted,
        notificationsGranted = inputs.notificationsGranted,
        recordAudioGranted = inputs.recordAudioGranted,
        modelInstalled = inputs.modelInstalled,
        installedModels = readiness?.installedModels.orEmpty(),
        translationReady = inputs.translationReady,
        download = download,
        running = running,
        nextStep = ReadinessChecker.nextStep(inputs),
        canStart = ReadinessChecker.canStart(inputs, download),
    )
}
