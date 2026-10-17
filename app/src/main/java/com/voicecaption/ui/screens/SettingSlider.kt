package com.voicecaption.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.voicecaption.ui.theme.Spacing

/**
 * A labeled slider. [value] is the persisted value; the slider keeps its own draft while it is
 * edited. [onChange] reports every step (for the live preview); [onCommit] receives the CURRENT
 * draft when the gesture ends. TalkBack/keyboard adjustments call `onValueChange` and then
 * `onValueChangeFinished` before any recomposition, so the draft is read from state at call time.
 * The draft re-syncs from [value] when it changes while the user is not touching the slider.
 */
@Composable
fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** What is shown at the right of the label; the value as text unless a control replaces it. */
    valueContent: @Composable () -> Unit = {
        Text(valueText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    },
) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    var draft by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { if (!dragged && !pressed) draft = value }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            valueContent()
        }
        Slider(
            value = draft,
            onValueChange = {
                draft = it
                onChange(it)
            },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = valueRange,
            steps = steps,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.Target)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                },
        )
    }
}
