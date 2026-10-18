package com.voicecaption.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecaption.asr.ModelCatalog
import com.voicecaption.core.ModelSize
import com.voicecaption.overlay.OverlayRenderer
import com.voicecaption.service.CaptureService
import com.voicecaption.service.LanguagePackService
import com.voicecaption.ui.permissions.Permissions
import com.voicecaption.ui.screens.HomeActions
import com.voicecaption.ui.screens.HomeScreen
import com.voicecaption.ui.screens.OnboardingActions
import com.voicecaption.ui.screens.OnboardingScreen
import com.voicecaption.ui.screens.SettingsActions
import com.voicecaption.ui.screens.SettingsScreen
import com.voicecaption.ui.state.MainViewModel
import com.voicecaption.ui.state.OnboardingStep
import com.voicecaption.ui.state.onboardingStep

private enum class Screen { HOME, SETTINGS }

private const val BYTES_PER_MB = 1_000_000L

/**
 * The only composable that touches the view model, the permission launchers and the screen-capture
 * launcher. It picks onboarding or the current screen and passes state and lambdas down.
 */
@Composable
fun AppRoot(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var introDone by rememberSaveable { mutableStateOf(false) }
    var notificationsSkipped by rememberSaveable { mutableStateOf(false) }
    var notificationsDenied by rememberSaveable { mutableStateOf(false) }
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    var overlayOpened by rememberSaveable { mutableStateOf(false) }
    var notStarted by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsDenied = !granted
        vm.refreshPermissions(Permissions.current(context))
    }
    val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneDenied = !granted
        vm.refreshPermissions(Permissions.current(context))
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(context, result.resultCode, data)
        } else {
            notStarted = true
        }
    }

    LaunchedEffect(state.running) { if (state.running) notStarted = false }

    val megabytes = remember {
        ModelSize.entries.associateWith { (ModelCatalog.DEFAULT.totalBytes(it) / BYTES_PER_MB).toInt() }
    }
    val swapLanguages = {
        val settings = state.settings
        vm.onSourceLanguage(settings.targetLang)
        vm.onTargetLanguage(settings.sourceLang)
    }

    val onboarding = if (state.running) null else onboardingStep(state, introDone, notificationsSkipped)
    if (onboarding != null) {
        OnboardingScreen(
            step = onboarding,
            granted = when (onboarding) {
                OnboardingStep.OVERLAY -> state.overlayGranted
                OnboardingStep.NOTIFICATIONS -> state.notificationsGranted
                OnboardingStep.MICROPHONE -> state.recordAudioGranted
                OnboardingStep.INTRO -> false
            },
            denied = when (onboarding) {
                OnboardingStep.OVERLAY -> overlayOpened && !state.overlayGranted
                OnboardingStep.NOTIFICATIONS -> notificationsDenied
                OnboardingStep.MICROPHONE -> microphoneDenied
                OnboardingStep.INTRO -> false
            },
            actions = OnboardingActions(
                onContinueFromIntro = { introDone = true },
                onOpenOverlaySettings = {
                    overlayOpened = true
                    startFirstAvailable(
                        context,
                        Permissions.overlaySettingsIntent(context),
                        Permissions.appDetailsIntent(context),
                        Intent(Settings.ACTION_SETTINGS),
                    )
                },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onSkipNotifications = { notificationsSkipped = true },
                onRequestMicrophone = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenAppSettings = {
                    startFirstAvailable(
                        context,
                        Permissions.appDetailsIntent(context),
                        Intent(Settings.ACTION_SETTINGS),
                    )
                },
            ),
        )
        return
    }

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            notStarted = notStarted,
            speechModelMegabytes = megabytes.getValue(state.settings.activeModel),
            actions = HomeActions(
                onOpenSettings = { screen = Screen.SETTINGS },
                onSourceLanguage = vm::onSourceLanguage,
                onTargetLanguage = vm::onTargetLanguage,
                onSwapLanguages = swapLanguages,
                onStartConfirmed = {
                    notStarted = false
                    projectionLauncher.launch(Permissions.projectionIntent(context))
                },
                onStop = { CaptureService.stop(context) },
                onDownload = vm::startDownloads,
                onRetryDownload = vm::retryDownloads,
                onCancelDownload = vm::cancelDownloads,
                onRecordScreen = vm::onRecordScreen,
            ),
        )
        Screen.SETTINGS -> SettingsScreen(
            state = state,
            modelMegabytes = megabytes,
            actions = SettingsActions(
                onBack = { screen = Screen.HOME },
                onFontSize = vm::onFontSize,
                onOpacity = vm::onOpacity,
                onBilingual = vm::onBilingual,
                onResetPositions = { OverlayRenderer.resetPositions() },
                onTextColor = vm::onTextColor,
                onPlateColor = vm::onPlateColor,
                onModelSize = vm::onModelSize,
                onAutoModel = vm::onAutoModel,
                onSourceLanguage = vm::onSourceLanguage,
                onTargetLanguage = vm::onTargetLanguage,
                onSwapLanguages = swapLanguages,
                onDeleteModel = vm::deleteModel,
                onShowDiagnostics = vm::onShowDiagnostics,
                onDownloadAllLanguages = {
                    vm.downloadAllLanguages()
                    LanguagePackService.start(context)
                },
                onRetryDownload = vm::retryLanguagePacks,
                onCancelDownload = vm::cancelLanguagePacks,
            ),
        )
    }
}

/** Starts the first of [intents] that some activity handles; never throws if none does. */
private fun startFirstAvailable(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Device lacks this settings screen; try the next, more general one.
        }
    }
}
