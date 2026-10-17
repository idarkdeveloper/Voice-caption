package com.voicecaption.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.voicecaption.R
import com.voicecaption.settings.SupportedLanguages
import com.voicecaption.ui.theme.Spacing

/** Above this system font scale the language pair stacks vertically so the names are not squeezed. */
private const val STACK_FONT_SCALE = 1.3f

/**
 * The film-language and caption-language fields with a swap button; the same component is used on
 * Home and in Settings. Picking opens [LanguagePickerDialog].
 */
@Composable
fun LanguagePairRow(
    sourceLang: String,
    targetLang: String,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingSource by rememberSaveable { mutableStateOf(false) }
    var pickingTarget by rememberSaveable { mutableStateOf(false) }
    val filmLabel = stringResource(R.string.language_film)
    val captionsLabel = stringResource(R.string.language_captions)

    val film: @Composable (Modifier) -> Unit = {
        LanguageField(filmLabel, sourceLang, onClick = { pickingSource = true }, modifier = it)
    }
    val captions: @Composable (Modifier) -> Unit = {
        LanguageField(captionsLabel, targetLang, onClick = { pickingTarget = true }, modifier = it)
    }
    val swap: @Composable () -> Unit = {
        IconButton(onClick = onSwap) {
            Icon(painterResource(R.drawable.ic_swap), contentDescription = stringResource(R.string.language_swap))
        }
    }

    if (LocalConfiguration.current.fontScale > STACK_FONT_SCALE) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            film(Modifier.fillMaxWidth())
            swap()
            captions(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            film(Modifier.weight(1f))
            swap()
            captions(Modifier.weight(1f))
        }
    }

    if (pickingSource) {
        LanguagePickerDialog(
            title = filmLabel,
            selected = sourceLang,
            onSelect = { onSourceSelected(it); pickingSource = false },
            onDismiss = { pickingSource = false },
        )
    }
    if (pickingTarget) {
        LanguagePickerDialog(
            title = captionsLabel,
            selected = targetLang,
            onSelect = { onTargetSelected(it); pickingTarget = false },
            onDismiss = { pickingTarget = false },
        )
    }
}

/** A tappable field that shows [label] and the name of language [code]. */
@Composable
fun LanguageField(label: String, code: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Spacing.Small)
    val name = SupportedLanguages.displayName(code)
    Row(
        modifier = modifier
            .heightIn(min = Spacing.PrimaryButton)
            .clip(shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
            .clickable(
                onClickLabel = stringResource(R.string.language_choose, label),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A dialog listing every supported language (never "auto"). The current choice is marked with a
 * check icon and bold text and is exposed as selected to TalkBack; rows are at least 48 dp tall.
 */
@Composable
fun LanguagePickerDialog(
    title: String,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val codes = SupportedLanguages.codes
    val names = remember { codes.map { SupportedLanguages.displayName(it) } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = codes.indexOf(selected).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            LazyColumn(state = listState, modifier = Modifier.selectableGroup()) {
                itemsIndexed(codes, key = { _, code -> code }) { index, code ->
                    val isSelected = code == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.Target)
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(code) })
                            .padding(horizontal = Spacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        if (isSelected) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Spacer(Modifier.size(Spacing.Large))
                        }
                        Spacer(Modifier.width(Spacing.Small))
                        Text(
                            names[index],
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
