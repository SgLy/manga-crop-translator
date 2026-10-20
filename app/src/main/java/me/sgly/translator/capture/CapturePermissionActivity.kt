package me.sgly.translator.capture

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import me.sgly.translator.runtime.FloatingService
import me.sgly.translator.settings.SettingsManager
import me.sgly.translator.state.TranslatorState

abstract class CapturePermissionActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            checkOverlayAndStart()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { _ ->
            if (Settings.canDrawOverlays(this)) {
                requestMediaProjection()
            } else {
                onCaptureDone(started = false)
            }
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent =
                    Intent(this, FloatingService::class.java).apply {
                        putExtra(FloatingService.EXTRA_RESULT_DATA, result.data)
                        putExtra(FloatingService.EXTRA_RESULT_CODE, result.resultCode)
                    }
                startForegroundService(serviceIntent)
                onCaptureDone(started = true)
            } else {
                onCaptureDone(started = false)
            }
        }

    protected fun startCapture() {
        TranslatorState.setThinkingSequence(SettingsManager(this).thinkingSequence)
        stopService(Intent(this, FloatingService::class.java))
        TranslatorState.reset()
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    protected open fun onCaptureDone(started: Boolean) {}

    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri(),
                )
            overlayPermissionLauncher.launch(intent)
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
