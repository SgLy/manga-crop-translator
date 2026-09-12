package me.sgly.translator

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
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
                var showSettings by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onOpenSettings = { showSettings = true },
                        onStartClicked = { checkPermissionsAndStart() }
                    )

                    if (showSettings) {
                        // 0f = fully shown, 1f = slid off to the right.
                        val progress = remember { Animatable(1f) }
                        val scope = rememberCoroutineScope()
                        LaunchedEffect(Unit) {
                            progress.animateTo(0f, animationSpec = tween(durationMillis = 250))
                        }
                        PredictiveBackHandler(enabled = true) { progressEvents ->
                            try {
                                progressEvents.collect { event ->
                                    progress.snapTo(event.progress)
                                }
                                showSettings = false
                            } catch (e: CancellationException) {
                                progress.animateTo(0f, animationSpec = tween(durationMillis = 200))
                                throw e
                            }
                        }
                        SettingsScreen(
                            settingsManager = settingsManager,
                            onBack = {
                                scope.launch {
                                    progress.animateTo(1f, animationSpec = tween(durationMillis = 250))
                                    showSettings = false
                                }
                            },
                            modifier = Modifier.graphicsLayer {
                                translationX = size.width * progress.value
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        // Install the parsed thinking ladder before resetting the state machine.
        TranslatorState.setThinkingLevels(settingsManager.thinkingLevelList)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onStartClicked: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_settings)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onStartClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.start),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    var visualModelApiBase by remember { mutableStateOf(settingsManager.visualModelApiBase) }
    var visualModelApiKey by remember { mutableStateOf(settingsManager.visualModelApiKey) }
    var visualModel by remember { mutableStateOf(settingsManager.visualModel) }

    var translateModelApiBase by remember { mutableStateOf(settingsManager.translateModelApiBase) }
    var translateModelApiKey by remember { mutableStateOf(settingsManager.translateModelApiKey) }
    var translateModel by remember { mutableStateOf(settingsManager.translateModel) }

    var useVisualModelForTranslation by remember { mutableStateOf(settingsManager.useVisualModelForTranslation) }
    var thinkingLevels by remember { mutableStateOf(settingsManager.thinkingLevels) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
        ) {
            // ---- Image recognition model (VisualModel) ----
            SectionTitle(stringResource(R.string.image_recognition_model))
            Text(
                text = stringResource(R.string.visual_model_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsField(
                    label = stringResource(R.string.label_base_url),
                    value = visualModelApiBase,
                    onValueChange = { visualModelApiBase = it; settingsManager.visualModelApiBase = it }
                )
                SettingsField(
                    label = stringResource(R.string.label_api_key),
                    value = visualModelApiKey,
                    password = true,
                    onValueChange = { visualModelApiKey = it; settingsManager.visualModelApiKey = it }
                )
                SettingsField(
                    label = stringResource(R.string.label_model),
                    value = visualModel,
                    onValueChange = { visualModel = it; settingsManager.visualModel = it }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Reuse VisualModel for translation ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.use_visual_model_for_translation),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = useVisualModelForTranslation,
                    onCheckedChange = {
                        useVisualModelForTranslation = it
                        settingsManager.useVisualModelForTranslation = it
                    }
                )
            }

            // ---- Translation model (TranslateModel), only when not reusing the VisualModel ----
            if (!useVisualModelForTranslation) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionTitle(stringResource(R.string.translation_model))
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsField(
                        label = stringResource(R.string.label_base_url),
                        value = translateModelApiBase,
                        onValueChange = { translateModelApiBase = it; settingsManager.translateModelApiBase = it }
                    )
                    SettingsField(
                        label = stringResource(R.string.label_api_key),
                        value = translateModelApiKey,
                        password = true,
                        onValueChange = { translateModelApiKey = it; settingsManager.translateModelApiKey = it }
                    )
                    SettingsField(
                        label = stringResource(R.string.label_model),
                        value = translateModel,
                        onValueChange = { translateModel = it; settingsManager.translateModel = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Thinking strength ladder ----
            SettingsField(
                label = stringResource(R.string.thinking_strength),
                value = thinkingLevels,
                onValueChange = { thinkingLevels = it; settingsManager.thinkingLevels = it }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.thinking_strength_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun SettingsField(
    label: String,
    value: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (label.isEmpty()) null else ({ Text(label) }),
        singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth()
    )
}
