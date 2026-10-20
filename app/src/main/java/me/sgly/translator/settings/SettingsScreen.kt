package me.sgly.translator.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R
import me.sgly.translator.about.AboutActivity
import me.sgly.translator.logs.AppLog
import me.sgly.translator.logs.LogsActivity

@Composable
internal fun SettingsScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var forwardLogsToLogcat by remember { mutableStateOf(settingsManager.forwardLogsToLogcat) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(scrollState)
                .padding(start = 24.dp, top = 16.dp, end = 24.dp),
    ) {
        ModelSection(settingsManager)

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        PreferencesSection(settingsManager)

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        SectionTitle(stringResource(R.string.appearance_section))
        Spacer(modifier = Modifier.height(12.dp))
        AppearanceSection(settingsManager)

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        SectionTitle(stringResource(R.string.debug_section))
        SwitchRow(
            label = stringResource(R.string.forward_logs_to_logcat),
            checked = forwardLogsToLogcat,
            onCheckedChange = {
                forwardLogsToLogcat = it
                settingsManager.forwardLogsToLogcat = it
                AppLog.forwardToLogcat = it
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        LinkRow(label = stringResource(R.string.action_logs)) {
            context.startActivity(Intent(context, LogsActivity::class.java))
        }

        Spacer(modifier = Modifier.height(24.dp))

        LinkRow(label = stringResource(R.string.about_title)) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
