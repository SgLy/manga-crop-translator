package me.sgly.translator.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R
import me.sgly.translator.ui.theme.jp.NotoSerifJp

@Composable
internal fun JapaneseFontDownloadDialog(
    onDismiss: () -> Unit,
    onDownload: (NotoSerifJp.Mirror) -> Unit,
) {
    val state by NotoSerifJp.state.collectAsState()
    val downloading = state is NotoSerifJp.State.Downloading
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.japanese_font_download_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.japanese_font_download_message))
                val progress = state as? NotoSerifJp.State.Downloading
                if (progress != null) {
                    Text(stringResource(R.string.japanese_font_downloading, formatProgress(progress)))
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.received.toFloat() / progress.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                val failure = state as? NotoSerifJp.State.Failed
                if (failure != null) {
                    Text(
                        text = stringResource(R.string.japanese_font_download_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = failure.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onDownload(NotoSerifJp.Mirror.GitHub) },
                        enabled = !downloading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.japanese_font_download_github))
                    }
                    OutlinedButton(
                        onClick = { onDownload(NotoSerifJp.Mirror.JsDelivr) },
                        enabled = !downloading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.japanese_font_download_jsdelivr))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_not_now))
            }
        },
    )
}

private fun formatProgress(state: NotoSerifJp.State.Downloading): String =
    if (state.total > 0) {
        "${formatBytes(state.received)} / ${formatBytes(state.total)}"
    } else {
        formatBytes(state.received)
    }

internal fun formatBytes(bytes: Long): String =
    if (bytes >= 1L shl 20) {
        "%.1f MB".format(bytes.toFloat() / (1L shl 20))
    } else {
        "%.0f KB".format(bytes.toFloat() / 1024f)
    }
