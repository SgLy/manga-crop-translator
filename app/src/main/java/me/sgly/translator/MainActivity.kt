package me.sgly.translator

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.sgly.translator.ui.theme.CropTranslatorTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkOverlayAndStart()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, FloatingService::class.java).apply {
                putExtra("EXTRA_RESULT_DATA", result.data)
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
            }
            startForegroundService(serviceIntent)
        }
    }

    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        setContent {
            CropTranslatorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        settingsManager = settingsManager,
                        onStartClicked = { checkPermissionsAndStart() }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        TranslatorState.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkOverlayAndStart()
        }
    }

    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
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

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    settingsManager: SettingsManager,
    onStartClicked: () -> Unit
) {
    val scrollState = rememberScrollState()

    var vlmApiBase by remember { mutableStateOf(settingsManager.vlmApiBase) }
    var vlmApiKey by remember { mutableStateOf(settingsManager.vlmApiKey) }
    var vlmModel by remember { mutableStateOf(settingsManager.vlmModel) }

    var llmApiBase by remember { mutableStateOf(settingsManager.llmApiBase) }
    var llmApiKey by remember { mutableStateOf(settingsManager.llmApiKey) }
    var llmModel by remember { mutableStateOf(settingsManager.llmModel) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Manga Translator Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // VLM Settings (Vision Language Model for OCR/Understanding)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Vision Model (VLM)",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Used for reading manga panels and extracting Japanese text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = vlmApiBase,
                    onValueChange = { vlmApiBase = it; settingsManager.vlmApiBase = it },
                    label = { Text("API Base URL") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = vlmApiKey,
                    onValueChange = { vlmApiKey = it; settingsManager.vlmApiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = vlmModel,
                    onValueChange = { vlmModel = it; settingsManager.vlmModel = it },
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // LLM Settings (Translation Model)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Translation Model (LLM)",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Used for translating extracted Japanese text to your native language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = llmApiBase,
                    onValueChange = { llmApiBase = it; settingsManager.llmApiBase = it },
                    label = { Text("API Base URL") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = llmApiKey,
                    onValueChange = { llmApiKey = it; settingsManager.llmApiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = llmModel,
                    onValueChange = { llmModel = it; settingsManager.llmModel = it },
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStartClicked,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                "Start Translator UI",
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}