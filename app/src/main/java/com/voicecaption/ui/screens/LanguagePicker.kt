package com.voicecaption.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.voicecaption.R
import com.voicecaption.settings.SupportedLanguages
import com.voicecaption.ui.theme.Spacing

/**
 * The film-language and caption-language card with a swap button; the same component is used on
 * Home and in Settings. Picking opens [LanguagePickerSheet]. [downloaded] are the languages whose
 * pack is on the phone; the sheet lists them first.
 */
@Composable
fun LanguagePairRow(
    sourceLang: String,
    targetLang: String,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
    downloaded: Set<String> = setOf("en"),
) {
    var pickingSource by rememberSaveable { mutableStateOf(false) }
    var pickingTarget by rememberSaveable { mutableStateOf(false) }
    val filmLabel = stringResource(R.string.language_film)
    val captionsLabel = stringResource(R.string.language_captions)
    val swapEnabled = !SupportedLanguages.isAuto(sourceLang)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column {
                LanguageField(filmLabel, sourceLang, onClick = { pickingSource = true })
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Spacing.Margin),
                    color = MaterialTheme.colorScheme.background,
                )
                LanguageField(captionsLabel, targetLang, onClick = { pickingTarget = true })
            }
            val swapLabel = stringResource(R.string.language_swap)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = Spacing.Medium)
                    .size(Spacing.Target)
                    .alpha(if (swapEnabled) 1f else 0.38f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(enabled = swapEnabled, onClickLabel = swapLabel, role = Role.Button, onClick = onSwap),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_swap_vert),
                    contentDescription = swapLabel,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (SupportedLanguages.isAuto(sourceLang)) {
            Text(
                stringResource(R.string.language_auto_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.Mini),
            )
        }
    }

    if (pickingSource) {
        LanguagePickerSheet(
            title = filmLabel,
            selected = sourceLang,
            downloaded = downloaded,
            allowAuto = true,
            onSelect = { onSourceSelected(it); pickingSource = false },
            onDismiss = { pickingSource = false },
        )
    }
    if (pickingTarget) {
        LanguagePickerSheet(
            title = captionsLabel,
            selected = targetLang,
            downloaded = downloaded,
            onSelect = { onTargetSelected(it); pickingTarget = false },
            onDismiss = { pickingTarget = false },
        )
    }
}

/** One tappable row of the language card: [label] over the name of language [code]. */
@Composable
fun LanguageField(label: String, code: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val name = if (SupportedLanguages.isAuto(code)) stringResource(R.string.language_auto) else SupportedLanguages.displayName(code)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(
                onClickLabel = stringResource(R.string.language_choose, label),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = Spacing.Margin, end = 64.dp, top = Spacing.Medium, bottom = Spacing.Medium),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Which rows the picker shows, split into "on this phone" and the rest. Pure, for tests. */
internal data class LanguageSections(val onPhone: List<String>, val others: List<String>)

internal fun languageSections(
    query: String,
    allowAuto: Boolean,
    downloaded: Set<String>,
    autoName: String,
    nameOf: (String) -> String = SupportedLanguages::displayName,
): LanguageSections {
    val q = query.trim()
    val codes = (if (allowAuto) listOf(SupportedLanguages.AUTO) else emptyList()) + SupportedLanguages.codes
    val shown = codes.filter { code ->
        q.isEmpty() ||
            (if (code == SupportedLanguages.AUTO) autoName else nameOf(code)).contains(q, ignoreCase = true) ||
            (code == "tl" && "filipino".contains(q, ignoreCase = true) && q.length >= 2)
    }
    val (phone, rest) = shown.partition { it == SupportedLanguages.AUTO || it in downloaded }
    return LanguageSections(phone, rest)
}

/**
 * A bottom sheet with a search field and every supported language: "On this phone" (packs that
 * are downloaded, plus Auto-detect for the film language) first, then all other languages with a
 * download icon. The current choice is marked with a check and exposed as selected to TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerSheet(
    title: String,
    selected: String,
    downloaded: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    allowAuto: Boolean = false,
) {
    val autoName = stringResource(R.string.language_auto)
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(query, allowAuto, downloaded, autoName) {
        languageSections(query, allowAuto, downloaded, autoName)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.Margin, vertical = Spacing.Small).semantics { heading() },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(R.string.language_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedBorderColor = MaterialTheme.colorScheme.background,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Margin, vertical = Spacing.Mini),
            )
            LazyColumn(Modifier.selectableGroup(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.Large)) {
                if (sections.onPhone.isNotEmpty()) {
                    item(key = "h-phone") { SectionLabel(stringResource(R.string.language_section_phone)) }
                    items(sections.onPhone, key = { it }) { code ->
                        LanguageRow(code, autoName, code == selected, downloaded = true, onSelect)
                    }
                }
                if (sections.others.isNotEmpty()) {
                    item(key = "h-all") { SectionLabel(stringResource(R.string.language_section_all)) }
                    items(sections.others, key = { it }) { code ->
                        LanguageRow(code, autoName, code == selected, downloaded = false, onSelect)
                    }
                }
                if (sections.onPhone.isEmpty() && sections.others.isEmpty()) {
                    item(key = "none") {
                        Text(
                            stringResource(R.string.language_no_match, query.trim()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.Margin),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = Spacing.Margin, end = Spacing.Margin, top = Spacing.Medium, bottom = Spacing.Mini)
            .semantics { heading() },
    )
}

@Composable
private fun LanguageRow(
    code: String,
    autoName: String,
    isSelected: Boolean,
    downloaded: Boolean,
    onSelect: (String) -> Unit,
) {
    val name = if (code == SupportedLanguages.AUTO) autoName else SupportedLanguages.displayName(code)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Small)
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (isSelected) MaterialTheme.colorScheme.background else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(code) })
            .padding(horizontal = Spacing.Margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.size(Spacing.Large))
        }
        Spacer(Modifier.width(Spacing.Medium))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        when {
            code == SupportedLanguages.AUTO -> Unit
            downloaded -> Text(
                stringResource(R.string.language_downloaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Icon(
                painterResource(R.drawable.ic_download),
                contentDescription = stringResource(R.string.language_not_downloaded),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
