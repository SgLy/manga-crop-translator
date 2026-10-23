package me.sgly.translator.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R
import me.sgly.translator.ui.theme.jp.MiuiFontWeight
import me.sgly.translator.ui.theme.jp.NotoSerifJp

@Composable
internal fun AppearanceSection(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val miuiSupported = remember(context) { MiuiFontWeight.isSupported(context) }
    val fontState by NotoSerifJp.state.collectAsState()
    var miuiAdaptation by remember { mutableStateOf(settingsManager.japaneseFontMiuiAdaptation) }
    var variableFont by remember { mutableStateOf(settingsManager.japaneseFontVariable) }
    var askingToDownload by remember { mutableStateOf(false) }

    LaunchedEffect(context) { NotoSerifJp.refresh(context) }
    LaunchedEffect(fontState, askingToDownload) {
        if (askingToDownload && fontState is NotoSerifJp.State.Ready) {
            variableFont = true
            settingsManager.japaneseFontVariable = true
            askingToDownload = false
        }
    }

    SwitchRow(
        label = stringResource(R.string.japanese_font_miui_adaptation),
        checked = miuiAdaptation,
        enabled = miuiSupported,
        onCheckedChange = {
            miuiAdaptation = it
            settingsManager.japaneseFontMiuiAdaptation = it
        },
    )

    if (miuiAdaptation) {
        Spacer(modifier = Modifier.height(12.dp))

        SwitchRow(
            label = stringResource(R.string.japanese_font_variable),
            checked = variableFont,
            onCheckedChange = { wanted ->
                when {
                    !wanted -> {
                        variableFont = false
                        settingsManager.japaneseFontVariable = false
                    }

                    fontState is NotoSerifJp.State.Ready -> {
                        variableFont = true
                        settingsManager.japaneseFontVariable = true
                    }

                    else -> {
                        askingToDownload = true
                    }
                }
            },
        )

        val downloaded = fontState as? NotoSerifJp.State.Ready
        if (downloaded != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.japanese_font_downloaded, formatBytes(downloaded.size)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        NotoSerifJp.delete(context)
                        variableFont = false
                        settingsManager.japaneseFontVariable = false
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }

        if (askingToDownload) {
            JapaneseFontDownloadDialog(
                onDismiss = { askingToDownload = false },
                onDownload = { mirror -> NotoSerifJp.download(context, mirror) },
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    LanguageSection()
}
