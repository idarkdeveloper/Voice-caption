package com.voicecaption.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.voicecaption.R
import com.voicecaption.ui.state.DownloadState
import com.voicecaption.ui.theme.Spacing

/** What is still missing, which decides the wording of the idle card. */
data class DownloadNeeds(val modelMegabytes: Int?, val needsPack: Boolean)

/**
 * Download card for the idle, downloading and failed states. Labels and failure messages come from
 * the download controller (English) and are shown as they are. [Done][DownloadState.Done] shows nothing.
 */
@Composable
fun DownloadCard(
    download: DownloadState,
    needs: DownloadNeeds,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (download is DownloadState.Done) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(Spacing.Margin), verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
            when (download) {
                is DownloadState.Downloading -> DownloadingContent(download, onCancel)
                is DownloadState.Failed -> FailedContent(download, onRetry)
                else -> IdleContent(needs, onDownload)
            }
        }
    }
}

@Composable
private fun IdleContent(needs: DownloadNeeds, onDownload: () -> Unit) {
    val text = when {
        needs.modelMegabytes != null && needs.needsPack -> stringResource(R.string.download_both, needs.modelMegabytes)
        needs.modelMegabytes != null -> stringResource(R.string.download_model_only, needs.modelMegabytes)
        else -> stringResource(R.string.download_pack_only)
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.Target)) {
        Text(stringResource(R.string.download_action))
    }
}

@Composable
private fun DownloadingContent(download: DownloadState.Downloading, onCancel: () -> Unit) {
    val percent = (download.progress.coerceIn(0f, 1f) * 100).toInt()
    val progressDescription = stringResource(R.string.download_progress_description)
    Text(
        download.label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        LinearProgressIndicator(
            progress = { download.progress.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).semantics { contentDescription = progressDescription },
        )
        Text(stringResource(R.string.download_percent, percent), style = MaterialTheme.typography.bodyMedium)
    }
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Spacing.Target)) {
        Text(stringResource(R.string.download_cancel))
    }
}

@Composable
private fun FailedContent(download: DownloadState.Failed, onRetry: () -> Unit) {
    StatusLine(
        icon = Icons.Filled.Warning,
        text = download.message,
        tint = MaterialTheme.colorScheme.error,
        announce = true,
    )
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.Target)) {
        Text(stringResource(R.string.download_retry))
    }
}
