package com.voicecaption.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.voicecaption.R
import com.voicecaption.core.ColorMath
import com.voicecaption.ui.theme.Spacing

/** A preset colour (opaque ARGB) with its spoken name. */
class ColorOption(val color: Int, @StringRes val name: Int)

val TextColorOptions = listOf(
    ColorOption(0xFFFFFFFF.toInt(), R.string.color_white),
    ColorOption(0xFFFFE45C.toInt(), R.string.color_yellow),
    ColorOption(0xFF7FE3FF.toInt(), R.string.color_cyan),
    ColorOption(0xFF8DF28D.toInt(), R.string.color_green),
    ColorOption(0xFFD0D0D0.toInt(), R.string.color_light_grey),
)

val PlateColorOptions = listOf(
    ColorOption(0xFF000000.toInt(), R.string.color_black),
    ColorOption(0xFF1C1C1C.toInt(), R.string.color_dark_grey),
    ColorOption(0xFF0B1A33.toInt(), R.string.color_navy),
    ColorOption(0xFF24113D.toInt(), R.string.color_purple),
    ColorOption(0xFF2B1A10.toInt(), R.string.color_brown),
)

/**
 * One colour setting: a title, a row of preset swatches (the chosen one has a ring and a tick,
 * never colour alone) and a custom "#RRGGBB" field that applies as soon as it is a valid colour.
 */
@Composable
fun ColorChoice(
    title: String,
    options: List<ColorOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small), verticalAlignment = Alignment.CenterVertically) {
            options.forEach { option ->
                Swatch(option, isSelected = ColorMath.opaque(selected) == option.color, onClick = { onSelect(option.color) })
            }
        }
        HexField(selected, onValid = onSelect)
    }
}

@Composable
private fun Swatch(option: ColorOption, isSelected: Boolean, onClick: () -> Unit) {
    val name = stringResource(option.name)
    val description = if (isSelected) stringResource(R.string.color_selected, name) else name
    val ring = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(Spacing.Target)
            .padding(Spacing.Mini)
            .clip(CircleShape)
            .background(Color(option.color))
            .border(BorderStroke(if (isSelected) 3.dp else 1.dp, ring), CircleShape)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color(ColorMath.readableOn(option.color)))
        }
    }
}

@Composable
private fun HexField(color: Int, onValid: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf(ColorMath.formatHex(color).removePrefix("#")) }
    val invalid = text.length == 6 && ColorMath.parseHex(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isLetterOrDigit() }.take(6).uppercase()
            text = cleaned
            ColorMath.parseHex(cleaned)?.let(onValid)
        },
        label = { Text(stringResource(R.string.settings_color_hex_label)) },
        prefix = { Text("#") },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.settings_color_hex_error)) }) else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
}
