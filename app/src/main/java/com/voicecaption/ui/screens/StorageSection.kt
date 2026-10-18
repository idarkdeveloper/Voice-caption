package com.voicecaption.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.voicecaption.R
import com.voicecaption.core.ModelSize
import com.voicecaption.ui.theme.Spacing

@Composable
private fun modelTitle(size: ModelSize): String = stringResource(
    when (size) {
        ModelSize.BASE -> R.string.storage_model_fast
        ModelSize.SMALL -> R.string.storage_model_accurate
        ModelSize.SENSEVOICE -> R.string.settings_model_experimental
    },
)

@Composable
private fun modelName(size: ModelSize): String = stringResource(
    when (size) {
        ModelSize.BASE -> R.string.model_name_fast
        ModelSize.SMALL -> R.string.model_name_accurate
        ModelSize.SENSEVOICE -> R.string.model_name_experimental
    },
)

/**
 * Lists each speech model with its install status and a Delete button behind a confirmation
 * dialog. Delete is disabled while [running], with an explanation.
 */
@Composable
fun StorageSection(
    installed: Set<ModelSize>,
    megabytes: Map<ModelSize, Int>,
    running: Boolean,
    onDelete: (ModelSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf<ModelSize?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        SectionTitle(stringResource(R.string.settings_storage))
        if (running) {
            Text(
                stringResource(R.string.settings_storage_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsCard(padding = 0.dp) {
        ModelSize.entries.forEachIndexed { index, size ->
            if (index > 0) SettingsDivider()
            val isInstalled = size in installed
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.Target),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(modelTitle(size), style = MaterialTheme.typography.bodyLarge)
                    StatusLine(
                        icon = if (isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Info,
                        text = if (isInstalled) {
                            stringResource(R.string.settings_storage_installed, megabytes[size] ?: 0)
                        } else {
                            stringResource(R.string.settings_storage_not_installed)
                        },
                        tint = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isInstalled) {
                    IconButton(onClick = { confirming = size }, enabled = !running) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.settings_delete_description, modelName(size)),
                        )
                    }
                }
            }
        }
        }
    }

    confirming?.let { size ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.settings_delete_title)) },
            text = { Text(stringResource(R.string.settings_delete_message, modelName(size))) },
            confirmButton = {
                TextButton(onClick = { confirming = null; onDelete(size) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
