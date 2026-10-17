package com.voicecaption.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.voicecaption.R
import com.voicecaption.ui.state.DownloadState
import com.voicecaption.ui.state.HomeStatus
import com.voicecaption.ui.state.MainUiState
import com.voicecaption.ui.state.SetupStep
import com.voicecaption.ui.state.effectiveSetupStep
import com.voicecaption.ui.state.homeStatus
import com.voicecaption.ui.theme.Spacing

/** What the Home controls do; wired by AppRoot. */
class HomeActions(
    val onOpenSettings: () -> Unit,
    val onSourceLanguage: (String) -> Unit,
    val onTargetLanguage: (String) -> Unit,
    val onSwapLanguages: () -> Unit,
    /** The user confirmed the pre-explanation; launch the system screen-capture consent. */
    val onStartConfirmed: () -> Unit,
    val onStop: () -> Unit,
    val onDownload: () -> Unit,
    val onRetryDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
)

/**
 * Home: header, language pair, the one primary action, status line and (when needed) the download
 * card. [notStarted] shows "Not started" after the user dismissed the system consent dialog;
 * [speechModelMegabytes] is the download size of the selected speech model.
 */
@Composable
fun HomeScreen(
    state: MainUiState,
    notStarted: Boolean,
    speechModelMegabytes: Int,
    actions: HomeActions,
) {
    var showConsent by rememberSaveable { mutableStateOf(false) }
    val step = effectiveSetupStep(state)
    val status = homeStatus(state)

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Margin),
            verticalArrangement = Arrangement.spacedBy(Spacing.Large),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = actions.onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open))
                }
            }

            LanguagePairRow(
                sourceLang = state.settings.sourceLang,
                targetLang = state.settings.targetLang,
                onSourceSelected = actions.onSourceLanguage,
                onTargetSelected = actions.onTargetLanguage,
                onSwap = actions.onSwapLanguages,
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                PrimaryAction(
                    running = state.running,
                    enabled = state.running || state.canStart,
                    onStart = { showConsent = true },
                    onStop = actions.onStop,
                )
                if (!state.running && !state.canStart) {
                    Text(
                        stringResource(startBlockedReason(state)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val (icon, tint) = statusIcon(status, notStarted)
            StatusLine(
                icon = icon,
                text = stringResource(statusText(status, notStarted)),
                tint = tint,
                announce = true,
            )

            if (step == SetupStep.DOWNLOADS && !state.running) {
                DownloadCard(
                    download = state.download,
                    needs = DownloadNeeds(
                        modelMegabytes = speechModelMegabytes.takeIf { !state.modelInstalled },
                        needsPack = !state.translationReady,
                    ),
                    onDownload = actions.onDownload,
                    onRetry = actions.onRetryDownload,
                    onCancel = actions.onCancelDownload,
                )
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text(stringResource(R.string.home_consent_title)) },
            text = { Text(stringResource(R.string.home_consent_body)) },
            confirmButton = {
                TextButton(onClick = { showConsent = false; actions.onStartConfirmed() }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** "Start captions" / "Stop captions". Stop stays enabled for as long as `running` is true. */
@Composable
private fun PrimaryAction(running: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = if (running) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = if (running) onStop else onStart,
        enabled = enabled,
        colors = colors,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.PrimaryButton),
    ) {
        Text(
            stringResource(if (running) R.string.home_stop else R.string.home_start),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@StringRes
private fun startBlockedReason(state: MainUiState): Int = when (state.download) {
    is DownloadState.Downloading -> R.string.home_reason_downloading
    is DownloadState.Failed -> R.string.home_reason_download_failed
    else -> if (effectiveSetupStep(state) == SetupStep.DOWNLOADS) R.string.home_reason_download else R.string.home_reason_setup
}

@StringRes
private fun statusText(status: HomeStatus, notStarted: Boolean): Int = when {
    notStarted && status != HomeStatus.LISTENING -> R.string.home_status_not_started
    else -> when (status) {
        HomeStatus.NEEDS_SETUP -> R.string.home_status_needs_setup
        HomeStatus.DOWNLOAD_NEEDED -> R.string.home_status_download_needed
        HomeStatus.DOWNLOADING -> R.string.home_status_downloading
        HomeStatus.DOWNLOAD_FAILED -> R.string.home_status_download_failed
        HomeStatus.READY -> R.string.home_status_ready
        HomeStatus.LISTENING -> R.string.home_status_listening
    }
}

@Composable
private fun statusIcon(status: HomeStatus, notStarted: Boolean): Pair<ImageVector, Color> {
    val scheme = MaterialTheme.colorScheme
    return when {
        notStarted && status != HomeStatus.LISTENING -> Icons.Filled.Info to scheme.onSurfaceVariant
        else -> when (status) {
            HomeStatus.NEEDS_SETUP, HomeStatus.DOWNLOAD_NEEDED -> Icons.Filled.Info to scheme.onSurfaceVariant
            HomeStatus.DOWNLOADING -> Icons.Filled.Refresh to scheme.onSurfaceVariant
            HomeStatus.DOWNLOAD_FAILED -> Icons.Filled.Warning to scheme.error
            HomeStatus.READY -> Icons.Filled.CheckCircle to scheme.primary
            HomeStatus.LISTENING -> Icons.Filled.PlayArrow to scheme.primary
        }
    }
}
