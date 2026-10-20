package me.sgly.translator.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R
import me.sgly.translator.state.ThinkingLevel

@Composable
internal fun PreferencesSection(settingsManager: SettingsManager) {
    var thinkingSequence by remember { mutableStateOf(settingsManager.thinkingSequence) }
    var showReasoning by remember { mutableStateOf(settingsManager.showReasoning) }
    var showTokenUsage by remember { mutableStateOf(settingsManager.showTokenUsage) }

    SectionTitle(stringResource(R.string.thinking_strength))
    Spacer(modifier = Modifier.height(8.dp))
    ThinkingSequence(
        sequence = thinkingSequence,
        onChange = {
            thinkingSequence = it
            settingsManager.thinkingSequence = it
        },
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.thinking_strength_hint),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(12.dp))

    SwitchRow(
        label = stringResource(R.string.show_reasoning),
        checked = showReasoning,
        onCheckedChange = {
            showReasoning = it
            settingsManager.showReasoning = it
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    SwitchRow(
        label = stringResource(R.string.show_token_usage),
        checked = showTokenUsage,
        onCheckedChange = {
            showTokenUsage = it
            settingsManager.showTokenUsage = it
        },
    )
}

@Composable
private fun ThinkingSequence(
    sequence: List<ThinkingLevel>,
    onChange: (List<ThinkingLevel>) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThinkingLevel.entries.forEach { level ->
            ToggleButton(
                checked = level in sequence,
                onCheckedChange = { checked ->
                    val next = if (checked) sequence + level else sequence - level
                    // An empty sequence would silently fall back to the default, so the last level stays on.
                    if (next.isNotEmpty()) {
                        onChange(ThinkingLevel.entries.filter { it in next })
                    }
                },
            ) {
                Text(level.effort)
            }
        }
    }
}
