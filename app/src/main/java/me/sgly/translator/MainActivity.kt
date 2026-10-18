package me.sgly.translator

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.sgly.translator.ui.theme.CropTranslatorTheme
import me.sgly.translator.ui.theme.jp.MiuiFontWeight
import me.sgly.translator.ui.theme.jp.NotoSerifJp
import me.sgly.translator.ui.theme.jp.japaneseTextStyle

class MainActivity : CapturePermissionActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        AppLog.forwardToLogcat = settingsManager.forwardLogsToLogcat
        NotoSerifJp.refresh(this)

        setContent {
            CropTranslatorTheme {
                // Hoisted above the tab switch: switching tabs destroys the page, and both the list
                // and the search bar must come back with what they already had.
                val context = LocalContext.current
                val summaries by remember {
                    VocabularyDatabase.get(context).vocabularyDao().summaries()
                }.collectAsState(initial = null)
                val searchQuery = rememberTextFieldState()
                val searchBarState =
                    remember {
                        SearchBarState(
                            initialValue = SearchBarValue.Collapsed,
                            animationSpecForExpand = spring<Float>(),
                            animationSpecForCollapse = spring<Float>(),
                        )
                    }

                var destination by rememberSaveable { mutableStateOf(Destination.WordList) }
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val resources = LocalResources.current

                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (destination == Destination.WordList) {
                            SearchTopBar(
                                query = searchQuery,
                                state = searchBarState,
                                onSearch = { term ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(resources.getString(R.string.search_done, term))
                                    }
                                },
                            )
                        }
                    },
                    bottomBar = {
                        Box {
                            ShortNavigationBar {
                                ShortNavigationBarItem(
                                    selected = destination == Destination.WordList,
                                    onClick = { destination = Destination.WordList },
                                    icon = { Icon(painter = painterResource(R.drawable.ic_menu_book), contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_word_list_title)) },
                                )
                                ShortNavigationBarItem(
                                    selected = destination == Destination.Practice,
                                    onClick = { destination = Destination.Practice },
                                    icon = { Icon(painter = painterResource(R.drawable.ic_quiz), contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_practice_title)) },
                                )
                                Spacer(modifier = Modifier)
                                ShortNavigationBarItem(
                                    selected = destination == Destination.Stats,
                                    onClick = { destination = Destination.Stats },
                                    icon = { Icon(painter = painterResource(R.drawable.ic_bar_chart), contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_stats_title)) },
                                )
                                ShortNavigationBarItem(
                                    selected = destination == Destination.Settings,
                                    onClick = { destination = Destination.Settings },
                                    icon = { Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = null) },
                                    label = { Text(stringResource(R.string.nav_settings_title)) },
                                )
                            }
                            Box(modifier = Modifier.matchParentSize()) {
                                StartButton(
                                    onClick = { startCapture() },
                                    modifier = Modifier.align(Alignment.TopCenter),
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(bottom = StartButtonOverhang),
                    ) {
                        when (destination) {
                            Destination.WordList -> summaries?.let { VocabularyListScreen(summaries = it) }
                            Destination.Practice -> PlaceholderScreen(stringResource(R.string.practice_placeholder))
                            Destination.Stats -> StatsScreen()
                            Destination.Settings -> SettingsScreen(settingsManager)
                        }
                    }
                }
            }
        }
    }
}

private enum class Destination { WordList, Practice, Stats, Settings }

private val StartButtonSize = 64.dp

internal val StartButtonOverhang = 16.dp

@Composable
private fun StartButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        shape = CircleShape,
        modifier =
            modifier
                .offset(y = -StartButtonOverhang)
                .requiredSize(StartButtonSize),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = stringResource(R.string.start),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: TextFieldState,
    state: SearchBarState,
    onSearch: (String) -> Unit,
) {
    AppBarWithSearch(
        state = state,
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = query,
                searchBarState = state,
                onSearch = onSearch,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null) },
            )
        },
    )
}

@Composable
private fun VocabularyListScreen(summaries: List<VocabularySummary>) {
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp),
    ) {
        if (summaries.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.vocabulary_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            FlowRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
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
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PlaceholderScreen(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VocabularyCard(
    summary: VocabularySummary,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.widthIn(max = 220.dp),
    ) {
        Box {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            ) {
                Text(
                    text = summary.lemma,
                    style = japaneseTextStyle(FontWeight.Medium),
                    fontSize = 17.sp,
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
            Badge(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp),
            ) {
                Text(summary.entryCount.toString())
            }
        }
    }
}

@Composable
private fun SettingsScreen(settingsManager: SettingsManager) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var visualModelApiBase by remember { mutableStateOf(settingsManager.visualModelApiBase) }
    var visualModelApiKey by remember { mutableStateOf(settingsManager.visualModelApiKey) }
    var visualModel by remember { mutableStateOf(settingsManager.visualModel) }

    var translateModelApiBase by remember { mutableStateOf(settingsManager.translateModelApiBase) }
    var translateModelApiKey by remember { mutableStateOf(settingsManager.translateModelApiKey) }
    var translateModel by remember { mutableStateOf(settingsManager.translateModel) }

    var useVisualModelForTranslation by remember { mutableStateOf(settingsManager.useVisualModelForTranslation) }
    var thinkingSequence by remember { mutableStateOf(settingsManager.thinkingSequence) }
    var forwardLogsToLogcat by remember { mutableStateOf(settingsManager.forwardLogsToLogcat) }
    var showReasoning by remember { mutableStateOf(settingsManager.showReasoning) }
    var showTokenUsage by remember { mutableStateOf(settingsManager.showTokenUsage) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(scrollState)
                .padding(start = 24.dp, top = 16.dp, end = 24.dp),
    ) {
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        SwitchRow(
            label = stringResource(R.string.use_visual_model_for_translation),
            checked = useVisualModelForTranslation,
            onCheckedChange = {
                useVisualModelForTranslation = it
                settingsManager.useVisualModelForTranslation = it
            },
        )

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

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

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

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon =
            if (password) {
                {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            painter =
                                painterResource(
                                    if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                ),
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

@Composable
private fun LinkRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = null,
            modifier = Modifier.rotate(180f),
        )
    }
}

@Composable
private fun AppearanceSection(settingsManager: SettingsManager) {
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
}

@Composable
private fun JapaneseFontDownloadDialog(
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

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1L shl 20) {
        "%.1f MB".format(bytes.toFloat() / (1L shl 20))
    } else {
        "%.0f KB".format(bytes.toFloat() / 1024f)
    }
