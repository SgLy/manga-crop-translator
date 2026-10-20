package me.sgly.translator.sheet

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R
import me.sgly.translator.state.AppPhase

@Composable
internal fun ResultActions(
    phase: AppPhase,
    hasSelection: Boolean,
    onThinkHarder: () -> Unit,
    onSave: () -> Unit,
    onConfirm: () -> Unit,
    onShowLogs: () -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
    ) {
        TextButton(
            onClick = onShowLogs,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
        ) {
            Text(stringResource(R.string.action_logs))
        }
        Spacer(modifier = Modifier.weight(1f))
        when (phase) {
            AppPhase.Done -> {
                TextButton(onClick = onThinkHarder) {
                    Text(stringResource(R.string.action_think_harder))
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (hasSelection) {
                    Button(onClick = onSave) {
                        Text(stringResource(R.string.action_save))
                    }
                } else {
                    OutlinedButton(onClick = onConfirm) {
                        Text(stringResource(R.string.action_got_it))
                    }
                }
            }

            AppPhase.ViewingLast -> {
                if (hasSelection) {
                    Button(onClick = onSave) {
                        Text(stringResource(R.string.action_save))
                    }
                } else {
                    OutlinedButton(onClick = onConfirm) {
                        Text(stringResource(R.string.action_got_it))
                    }
                }
            }

            else -> {}
        }
    }
}
