package com.voicecaption.app

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.voicecaption.asr.HttpUrlConnectionDownloader
import com.voicecaption.asr.ModelManager
import com.voicecaption.core.CaptionSettings
import com.voicecaption.core.ModelSize
import com.voicecaption.service.CaptureStatus
import com.voicecaption.service.ServiceDependencies
import com.voicecaption.settings.SettingsRepository
import com.voicecaption.translate.MlKitTranslator
import com.voicecaption.ui.state.DownloadController
import com.voicecaption.ui.state.MainViewModel
import com.voicecaption.ui.state.ReadinessSource
import com.voicecaption.ui.state.SettingsStore
import com.voicecaption.ui.state.asInstaller
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Manual dependency container; one instance per process, created by [TransCapApp].
 *
 * Everything is built lazily and once. The translator is shared with the UI and is never closed
 * here: [MlKitTranslator.close] is teardown-only (the capture service closes its own instance).
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** Application-lifetime scope for settings sharing and downloads. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background task failed", e) },
    )

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = appScope,
            produceFile = { File(appContext.filesDir, "datastore/settings.preferences_pb") },
        )
    }

    /** Persistent caption settings. */
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(dataStore) }

    /**
     * Latest settings as an in-memory snapshot (also the service's synchronous hook). It starts as
     * defaults until the DataStore's first read lands. Known limitation: after a transient
     * DataStore IOException the repository flow emits defaults and then COMPLETES, so this falls
     * back to defaults and later edits are persisted but not reflected here until the process
     * restarts.
     */
    val settingsState: StateFlow<CaptionSettings> by lazy {
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, CaptionSettings())
    }

    /** Whisper models, rooted at the same directory the capture service reads. */
    val modelManager: ModelManager by lazy {
        ModelManager(ServiceDependencies.modelsDir(appContext), HttpUrlConnectionDownloader())
    }

    /** Shared ML Kit translator for readiness checks and pack downloads. */
    val translator: MlKitTranslator by lazy { MlKitTranslator(appContext) }

    /** App-scoped download orchestration, so downloads survive the Activity. */
    val downloadController: DownloadController by lazy {
        DownloadController(modelManager.asInstaller(), translator, appScope)
    }

    /** [SettingsStore] over [settingsState] and [settingsRepository]. */
    val settingsStore: SettingsStore by lazy {
        object : SettingsStore {
            override val settings: StateFlow<CaptionSettings> = settingsState
            override suspend fun update(transform: (CaptionSettings) -> CaptionSettings) =
                settingsRepository.update(transform)
        }
    }

    private val readinessSource = object : ReadinessSource {
        override suspend fun isModelInstalled(size: ModelSize) =
            guarded("isInstalled") { withContext(Dispatchers.IO) { modelManager.isInstalled(size) } }

        private suspend fun guarded(what: String, block: suspend () -> Boolean): Boolean = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Readiness $what failed", e)
            false
        }

        override suspend fun isTranslationReady(source: String, target: String) =
            source == target || guarded("isReady") { translator.isReady(source, target) }

        override suspend fun deleteModel(size: ModelSize) =
            guarded("delete") { withContext(Dispatchers.IO) { modelManager.delete(size) } }
    }

    /** Factory for [MainViewModel]: `viewModels { container.mainViewModelFactory() }`. */
    fun mainViewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return MainViewModel(
                store = settingsStore,
                readiness = readinessSource,
                downloads = downloadController,
                captureRunning = CaptureStatus.running,
                scope = CoroutineScope(
                    SupervisorJob() + Dispatchers.Default +
                        CoroutineExceptionHandler { _, e -> Log.e(TAG, "View model task failed", e) },
                ),
                onError = { Log.w(TAG, "View model operation failed", it) },
            ) as T
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
