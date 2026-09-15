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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.sgly.translator.ui.theme.CropTranslatorTheme
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
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
            }
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent =
                    Intent(this, FloatingService::class.java).apply {
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
        AppLog.forwardToLogcat = settingsManager.forwardLogsToLogcat

        setContent {
            CropTranslatorTheme {
                var showSettings by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onOpenSettings = { showSettings = true },
                        onStartClicked = { checkPermissionsAndStart() },
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
                            modifier =
                                Modifier.graphicsLayer {
                                    translationX = size.width * progress.value
                                },
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
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
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
    onStartClicked: () -> Unit,
) {
    val context = LocalContext.current
    val summaries by remember {
        VocabularyDatabase.get(context).vocabularyDao().summaries()
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) {
                if (summaries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.vocabulary_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    FlowRow(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        summaries.forEach { summary ->
                            VocabularyCard(
                                summary = summary,
                                onClick = {
                                    context.startActivity(
                                        Intent(context, VocabularyDetailActivity::class.java)
                                            .putExtra(VocabularyDetailActivity.EXTRA_WORD_ID, summary.id),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onStartClicked,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.start),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** One tile of the vocabulary list: the headword, its meanings, and how often it was recorded. */
@Composable
private fun VocabularyCard(
    summary: VocabularySummary,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.widthIn(max = 220.dp)) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            ) {
                Text(
                    text = summary.lemma,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Leaves room for the count badge sitting in the card's top-right corner.
                    modifier = Modifier.padding(end = 26.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary.definitions,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = summary.entryCount.toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
    var forwardLogsToLogcat by remember { mutableStateOf(settingsManager.forwardLogsToLogcat) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp),
        ) {
            // ---- Image recognition model (VisualModel) ----
            SectionTitle(stringResource(R.string.image_recognition_model))
            Text(
                text = stringResource(R.string.visual_model_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsField(
                    label = stringResource(R.string.label_base_url),
                    value = visualModelApiBase,
                    onValueChange = {
                        visualModelApiBase = it
                        settingsManager.visualModelApiBase = it
                    },
                )
                SettingsField(
                    label = stringResource(R.string.label_api_key),
                    value = visualModelApiKey,
                    password = true,
                    onValueChange = {
                        visualModelApiKey = it
                        settingsManager.visualModelApiKey = it
                    },
                )
                SettingsField(
                    label = stringResource(R.string.label_model),
                    value = visualModel,
                    onValueChange = {
                        visualModel = it
                        settingsManager.visualModel = it
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Reuse VisualModel for translation ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.use_visual_model_for_translation),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = useVisualModelForTranslation,
                    onCheckedChange = {
                        useVisualModelForTranslation = it
                        settingsManager.useVisualModelForTranslation = it
                    },
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
                        onValueChange = {
                            translateModelApiBase = it
                            settingsManager.translateModelApiBase = it
                        },
                    )
                    SettingsField(
                        label = stringResource(R.string.label_api_key),
                        value = translateModelApiKey,
                        password = true,
                        onValueChange = {
                            translateModelApiKey = it
                            settingsManager.translateModelApiKey = it
                        },
                    )
                    SettingsField(
                        label = stringResource(R.string.label_model),
                        value = translateModel,
                        onValueChange = {
                            translateModel = it
                            settingsManager.translateModel = it
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Thinking strength ladder ----
            SettingsField(
                label = stringResource(R.string.thinking_strength),
                value = thinkingLevels,
                onValueChange = {
                    thinkingLevels = it
                    settingsManager.thinkingLevels = it
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.thinking_strength_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Debug ----
            SectionTitle(stringResource(R.string.debug_section))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.forward_logs_to_logcat),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = forwardLogsToLogcat,
                    onCheckedChange = {
                        forwardLogsToLogcat = it
                        settingsManager.forwardLogsToLogcat = it
                        AppLog.forwardToLogcat = it
                    },
                )
            }

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
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun SettingsField(
    label: String,
    value: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (label.isEmpty()) null else ({ Text(label) }),
        singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon =
            if (password) {
                {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                        )
                    }
                }
            } else {
                null
            },
        modifier = Modifier.fillMaxWidth(),
    )
}
