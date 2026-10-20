package me.sgly.translator

import android.os.Bundle
import androidx.activity.compose.setContent
import me.sgly.translator.capture.CapturePermissionActivity
import me.sgly.translator.logs.AppLog
import me.sgly.translator.settings.SettingsManager
import me.sgly.translator.ui.theme.CropTranslatorTheme
import me.sgly.translator.ui.theme.jp.NotoSerifJp

class MainActivity : CapturePermissionActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        AppLog.forwardToLogcat = settingsManager.forwardLogsToLogcat
        NotoSerifJp.refresh(this)

        setContent {
            CropTranslatorTheme {
                MainScaffold(
                    settingsManager = settingsManager,
                    onStartCapture = { startCapture() },
                )
            }
        }
    }
}
