package com.voicecaption.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.voicecaption.R
import com.voicecaption.ui.theme.Spacing

/** [current] moved by [delta] and kept inside [min]..[max]. */
internal fun steppedFontSize(current: Int, delta: Int, min: Int, max: Int): Int = (current + delta).coerceIn(min, max)

/**
 * A "- value +" control for fine caption-size changes; each press moves one step. A button is
 * disabled at its limit. The two 48 dp buttons and the value are announced together by TalkBack.
 */
@Composable
fun SizeStepper(
    value: Int,
    min: Int,
    max: Int,
    valueText: String,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics { stateDescription = valueText },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onValue(steppedFontSize(value, -1, min, max)) }, enabled = value > min) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.settings_size_decrease))
        }
        Text(
            valueText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = valueText },
        )
        IconButton(onClick = { onValue(steppedFontSize(value, 1, min, max)) }, enabled = value < max) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.settings_size_increase))
        }
    }
}
