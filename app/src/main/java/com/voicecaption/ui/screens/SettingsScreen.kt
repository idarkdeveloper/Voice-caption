package com.voicecaption.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.voicecaption.R
import com.voicecaption.core.ColorMath
import com.voicecaption.core.ModelSize
import com.voicecaption.ui.state.DownloadState
import com.voicecaption.ui.state.MainUiState
import com.voicecaption.ui.theme.Spacing
import kotlin.math.roundToInt

private const val MIN_FONT_SP = 14
private const val MAX_FONT_SP = 32
private const val MIN_OPACITY_PERCENT = 50
private const val MAX_OPACITY_PERCENT = 100
private const val OPACITY_STEP_PERCENT = 5

/** What the Settings controls do; wired by AppRoot. */
class SettingsActions(
    val onBack: () -> Unit,
    val onFontSize: (Int) -> Unit,
    val onOpacity: (Float) -> Unit,
    val onBilingual: (Boolean) -> Unit,
    val onResetPositions: () -> Unit,
    val onTextColor: (Int) -> Unit,
    val onPlateColor: (Int) -> Unit,
    val onModelSize: (ModelSize) -> Unit,
    val onAutoModel: () -> Unit,
    val onSourceLanguage: (String) -> Unit,
    val onTargetLanguage: (String) -> Unit,
    val onSwapLanguages: () -> Unit,
    val onDeleteModel: (ModelSize) -> Unit,
    val onShowDiagnostics: (Boolean) -> Unit,
    val onDownloadAllLanguages: () -> Unit,
    val onRetryDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
)

/** Settings: preview, sliders, switch, speech model, languages and storage. */
@Composable
fun SettingsScreen(state: MainUiState, modelMegabytes: Map<ModelSize, Int>, actions: SettingsActions) {
    val settings = state.settings
    // Drafts follow the thumb at once; they reset to the stored value whenever that changes.
    var fontDraft by remember(settings.fontSizeSp) { mutableIntStateOf(settings.fontSizeSp) }
    var opacityPercent by remember(settings.opacity) {
        mutableFloatStateOf(percentOf(settings.opacity).toFloat())
    }

    Scaffold(
        topBar = {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = Spacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Margin),
            verticalArrangement = Arrangement.spacedBy(Spacing.Large),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                Text(
                    stringResource(R.string.settings_preview),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                CaptionPreview(
                    fontSizeSp = fontDraft,
                    opacity = opacityPercent / 100f,
                    bilingual = settings.bilingual,
                    textColor = settings.textColor,
                    plateColor = settings.plateColor,
                )
                Text(
                    stringResource(R.string.settings_move_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = actions.onResetPositions,
                    modifier = Modifier.heightIn(min = Spacing.Target),
                ) { Text(stringResource(R.string.settings_reset_positions)) }
            }

            SettingSlider(
                label = stringResource(R.string.settings_size_label),
                valueText = stringResource(R.string.settings_size_value, fontDraft),
                value = settings.fontSizeSp.toFloat(),
                valueRange = MIN_FONT_SP.toFloat()..MAX_FONT_SP.toFloat(),
                steps = MAX_FONT_SP - MIN_FONT_SP - 1,
                onChange = { fontDraft = it.roundToInt() },
                onCommit = { actions.onFontSize(it.roundToInt()) },
                valueContent = {
                    SizeStepper(
                        value = fontDraft,
                        min = MIN_FONT_SP,
                        max = MAX_FONT_SP,
                        valueText = stringResource(R.string.settings_size_value, fontDraft),
                        onValue = { size ->
                            fontDraft = size
                            actions.onFontSize(size)
                        },
                    )
                },
            )

            SettingSlider(
                label = stringResource(R.string.settings_opacity_label),
                valueText = stringResource(R.string.settings_opacity_value, opacityPercent.roundToInt()),
                value = percentOf(settings.opacity).toFloat(),
                valueRange = MIN_OPACITY_PERCENT.toFloat()..MAX_OPACITY_PERCENT.toFloat(),
                steps = (MAX_OPACITY_PERCENT - MIN_OPACITY_PERCENT) / OPACITY_STEP_PERCENT - 1,
                onChange = { opacityPercent = it },
                onCommit = { actions.onOpacity(it.roundToInt() / 100f) },
            )

            val switchLabel = stringResource(R.string.settings_bilingual)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.Target)
                    .toggleable(
                        value = settings.bilingual,
                        role = Role.Switch,
                        onValueChange = actions.onBilingual,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(switchLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = settings.bilingual, onCheckedChange = null)
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                Text(
                    stringResource(R.string.settings_colors),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                ColorChoice(
                    title = stringResource(R.string.settings_text_color),
                    options = TextColorOptions,
                    selected = settings.textColor,
                    onSelect = actions.onTextColor,
                )
                ColorChoice(
                    title = stringResource(R.string.settings_plate_color),
                    options = PlateColorOptions,
                    selected = settings.plateColor,
                    onSelect = actions.onPlateColor,
                )
                if (ColorMath.contrastRatio(settings.textColor, settings.plateColor) < ColorMath.MIN_READABLE_CONTRAST) {
                    Text(
                        stringResource(R.string.settings_contrast_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ModelChoice(settings.autoModel, settings.modelSize, actions.onModelSize, actions.onAutoModel)

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                Text(
                    stringResource(R.string.settings_languages),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                LanguagePairRow(
                    sourceLang = settings.sourceLang,
                    targetLang = settings.targetLang,
                    onSourceSelected = actions.onSourceLanguage,
                    onTargetSelected = actions.onTargetLanguage,
                    onSwap = actions.onSwapLanguages,
                )
            }

            OfflineLanguagesSection(
                download = state.download,
                onDownloadAll = actions.onDownloadAllLanguages,
                onRetry = actions.onRetryDownload,
                onCancel = actions.onCancelDownload,
            )

            if (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.Target)
                        .toggleable(
                            value = settings.showDiagnostics,
                            role = Role.Switch,
                            onValueChange = actions.onShowDiagnostics,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_diagnostics),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = settings.showDiagnostics, onCheckedChange = null)
                }
            }

            StorageSection(
                installed = state.installedModels,
                megabytes = modelMegabytes,
                running = state.running,
                onDelete = actions.onDeleteModel,
            )
        }
    }
}

private fun percentOf(opacity: Float): Int =
    ((opacity * 100f / OPACITY_STEP_PERCENT).roundToInt() * OPACITY_STEP_PERCENT)
        .coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT)

@Composable
private fun ModelChoice(auto: Boolean, selected: ModelSize, onSelect: (ModelSize) -> Unit, onAuto: () -> Unit) {
    // null = "Auto"; otherwise an explicit model.
    val options: List<Pair<ModelSize?, Int>> = listOf(
        null to R.string.settings_model_auto,
        ModelSize.BASE to R.string.settings_model_fast,
        ModelSize.SMALL to R.string.settings_model_accurate,
        ModelSize.SENSEVOICE to R.string.settings_model_experimental,
    )
    val current: ModelSize? = if (auto) null else selected
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            stringResource(R.string.settings_model),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (size, label) ->
                SegmentedButton(
                    selected = size == current,
                    onClick = { if (size == null) onAuto() else onSelect(size) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.heightIn(min = Spacing.Target),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        if (auto) {
            Text(
                stringResource(R.string.settings_model_auto_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.settings_model_helper),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Download all languages": one confirmed tap fetches every missing ML Kit pack for offline use. */
@Composable
private fun OfflineLanguagesSection(
    download: DownloadState,
    onDownloadAll: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            stringResource(R.string.settings_offline_languages),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        when (download) {
            is DownloadState.Downloading -> {
                val description = stringResource(R.string.download_progress_description)
                Text(
                    download.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LinearProgressIndicator(
                    progress = { download.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
                )
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Spacing.Target)) {
                    Text(stringResource(R.string.download_cancel))
                }
            }
            is DownloadState.Failed -> {
                Text(download.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.Target)) {
                    Text(stringResource(R.string.download_retry))
                }
            }
            else -> {
                Text(
                    stringResource(R.string.settings_offline_languages_helper),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirm = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.Target),
                ) { Text(stringResource(R.string.settings_offline_languages_action)) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_offline_languages_confirm_title)) },
            text = { Text(stringResource(R.string.settings_offline_languages_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDownloadAll() }) {
                    Text(stringResource(R.string.download_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
