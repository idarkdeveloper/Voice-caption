package com.voicecaption.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.voicecaption.core.ModelSize
import com.voicecaption.ui.theme.Amber
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
                .padding(padding),
        ) {
            Header(onOpenSettings = actions.onOpenSettings)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.Margin, vertical = Spacing.Small),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                LanguagePairRow(
                    sourceLang = state.settings.sourceLang,
                    targetLang = state.settings.targetLang,
                    onSourceSelected = actions.onSourceLanguage,
                    onTargetSelected = actions.onTargetLanguage,
                    onSwap = actions.onSwapLanguages,
                    downloaded = state.downloadedLanguages,
                )

                val (icon, tint) = statusIcon(status, notStarted)
                StatusChip(
                    icon = icon,
                    tint = tint,
                    text = stringResource(statusText(status, notStarted)),
                    detail = stringResource(R.string.home_speech_model, speechModelName(state)),
                )

                (state.languagePacks as? DownloadState.Downloading)?.let { packs ->
                    Column(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                    ) {
                        Text(
                            packs.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { packs.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }

                if (state.running) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(Spacing.Margin), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.home_running_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.home_running_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

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

            // The primary action sits at the bottom, in thumb reach.
            Column(
                modifier = Modifier.padding(start = Spacing.Margin, end = Spacing.Margin, bottom = Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                if (!state.running && state.canStart) {
                    Hint(stringResource(R.string.home_consent_body), center = true)
                }
                PrimaryAction(
                    running = state.running,
                    enabled = state.running || state.canStart,
                    onStart = { showConsent = true },
                    onStop = actions.onStop,
                )
                when {
                    state.running -> Hint(stringResource(R.string.settings_move_hint), center = true)
                    !state.canStart -> Hint(stringResource(startBlockedReason(state)), center = true)
                    else -> Hint(stringResource(R.string.onboarding_intro_drm), center = true)
                }
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

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.Margin, end = Spacing.Small, top = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.size(32.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append("Trans") }
                    withStyle(SpanStyle(color = Amber)) { append("Cap") }
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open))
            }
        }
    }
}

/** A pill with a state icon, the state in words, and a secondary detail. State never relies on colour alone. */
@Composable
private fun StatusChip(icon: ImageVector, tint: Color, text: String, detail: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 10.dp, end = 14.dp, top = Spacing.Small, bottom = Spacing.Small)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Text("· $detail", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun speechModelName(state: MainUiState): String {
    val name = stringResource(
        when (state.settings.activeModel) {
            ModelSize.BASE -> R.string.model_name_base
            ModelSize.SMALL -> R.string.model_name_small
            ModelSize.SENSEVOICE -> R.string.model_name_sensevoice
        },
    )
    return if (state.settings.autoModel) stringResource(R.string.model_name_auto, name) else name
}

/** "Start captions" / "Stop captions". Stop stays enabled for as long as `running` is true. */
@Composable
private fun PrimaryAction(running: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = if (running) {
        ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
    } else {
        ButtonDefaults.buttonColors(
            disabledContainerColor = scheme.surfaceVariant,
            disabledContentColor = scheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = if (running) onStop else onStart,
        enabled = enabled,
        colors = colors,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.PrimaryButton),
    ) {
        Icon(
            painterResource(if (running) R.drawable.ic_stop else R.drawable.ic_caption),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Spacing.Small))
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
