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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.voicecaption.R
import com.voicecaption.core.ModelSize
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
    val onModelSize: (ModelSize) -> Unit,
    val onSourceLanguage: (String) -> Unit,
    val onTargetLanguage: (String) -> Unit,
    val onSwapLanguages: () -> Unit,
    val onDeleteModel: (ModelSize) -> Unit,
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
                )
            }

            SettingSlider(
                label = stringResource(R.string.settings_size_label),
                valueText = stringResource(R.string.settings_size_value, fontDraft),
                value = settings.fontSizeSp.toFloat(),
                valueRange = MIN_FONT_SP.toFloat()..MAX_FONT_SP.toFloat(),
                steps = MAX_FONT_SP - MIN_FONT_SP - 1,
                onChange = { fontDraft = it.roundToInt() },
                onCommit = { actions.onFontSize(it.roundToInt()) },
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

            ModelChoice(settings.modelSize, actions.onModelSize)

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
private fun ModelChoice(selected: ModelSize, onSelect: (ModelSize) -> Unit) {
    val options = listOf(
        ModelSize.BASE to R.string.settings_model_fast,
        ModelSize.SMALL to R.string.settings_model_accurate,
        ModelSize.SENSEVOICE to R.string.settings_model_experimental,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            stringResource(R.string.settings_model),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (size, label) ->
                SegmentedButton(
                    selected = size == selected,
                    onClick = { onSelect(size) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.heightIn(min = Spacing.Target),
                ) { Text(stringResource(label)) }
            }
        }
        Text(
            stringResource(R.string.settings_model_helper),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
