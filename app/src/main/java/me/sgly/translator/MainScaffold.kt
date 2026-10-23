package me.sgly.translator

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.sgly.translator.settings.SettingsManager
import me.sgly.translator.settings.SettingsScreen
import me.sgly.translator.stats.StatsScreen
import me.sgly.translator.vocabulary.VocabularyDatabase
import me.sgly.translator.vocabulary.VocabularyListScreen

@Composable
internal fun MainScaffold(
    settingsManager: SettingsManager,
    onStartCapture: () -> Unit,
) {
    // Hoisted above the tab switch: switching tabs destroys the page, and both the list
    // and the search bar must come back with what they already had.
    val context = LocalContext.current
    val summaries by remember {
        VocabularyDatabase.get(context).vocabularyDao().summaries()
    }.collectAsState(initial = null)
    val searchQuery = rememberTextFieldState()
    val searchBarState = rememberSearchBarState()
    val searchScrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val scrollStates =
        rememberSaveable(saver = ScrollStatesSaver) {
            Destination.entries.associateWith { ScrollState(0) }
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
                    scrollBehavior = searchScrollBehavior,
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
                        onClick = onStartCapture,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier =
            Modifier
                .contentBottomInset(innerPadding)
                .fillMaxSize()
                .statusBarScrim()
        Box(
            modifier =
                if (destination == Destination.WordList) {
                    contentModifier.nestedScroll(searchScrollBehavior.nestedScrollConnection)
                } else {
                    contentModifier
                },
        ) {
            when (destination) {
                Destination.WordList -> {
                    summaries?.let { list ->
                        if (list.isEmpty()) {
                            PlaceholderScreen(stringResource(R.string.vocabulary_empty), innerPadding)
                        } else {
                            ScrollablePage(scrollStates.getValue(destination), innerPadding) {
                                VocabularyListScreen(summaries = list)
                            }
                        }
                    }
                }

                Destination.Practice -> {
                    PlaceholderScreen(stringResource(R.string.practice_placeholder), innerPadding)
                }

                Destination.Stats -> {
                    ScrollablePage(scrollStates.getValue(destination), innerPadding) { StatsScreen() }
                }

                Destination.Settings -> {
                    ScrollablePage(scrollStates.getValue(destination), innerPadding) { SettingsScreen(settingsManager) }
                }
            }
        }
    }
}

private enum class Destination { WordList, Practice, Stats, Settings }

private val ScrollStatesSaver: Saver<Map<Destination, ScrollState>, List<Int>> =
    Saver(
        save = { states -> Destination.entries.map { states.getValue(it).value } },
        restore = { offsets ->
            Destination.entries
                .mapIndexed { index, destination -> destination to ScrollState(offsets.getOrNull(index) ?: 0) }
                .toMap()
        },
    )

private val StartButtonSize = 64.dp

/** Scroll viewport for a tab: content runs to the window top, and its end clears the start button's overhang. */
@Composable
private fun ScrollablePage(
    scrollState: ScrollState,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val statusBarHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this) }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .contentTopInset(contentPadding, statusBarHeight)
                .padding(bottom = StartButtonOverhang),
    ) {
        content()
    }
}

/** Measure-time read: the bar writes its height into the scaffold padding on every scroll frame. */
private fun Modifier.contentTopInset(
    contentPadding: PaddingValues,
    statusBarHeight: Int,
): Modifier =
    this.layout { measurable, constraints ->
        val top = maxOf(contentPadding.calculateTopPadding().roundToPx(), statusBarHeight)
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
    }

/** Reserves the bar and status bar area above a page that has nothing to scroll. */
private fun Modifier.contentTopReserve(
    contentPadding: PaddingValues,
    statusBarHeight: Int,
): Modifier =
    this.layout { measurable, constraints ->
        val top = maxOf(contentPadding.calculateTopPadding().roundToPx(), statusBarHeight)
        val placeable =
            measurable.measure(
                constraints.copy(maxHeight = (constraints.maxHeight - top).coerceAtLeast(constraints.minHeight)),
            )
        layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
    }

/** Measure-time read: reading the scaffold padding in composition would recompose the content on every bar frame. */
private fun Modifier.contentBottomInset(contentPadding: PaddingValues): Modifier =
    this.layout { measurable, constraints ->
        val bottom = contentPadding.calculateBottomPadding().roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(maxHeight = (constraints.maxHeight - bottom).coerceAtLeast(constraints.minHeight)),
            )
        layout(placeable.width, placeable.height + bottom) { placeable.place(0, 0) }
    }

/** The status bar draws nothing itself, so its inset gets a translucent layer over the content. */
@Composable
private fun Modifier.statusBarScrim(): Modifier {
    val color = MaterialTheme.colorScheme.surface.copy(alpha = StatusBarScrimAlpha)
    val height = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this) }.toFloat()
    return this.drawWithContent {
        drawContent()
        drawRect(color = color, size = Size(size.width, height))
    }
}

private val StatusBarScrimAlpha = 0.8f

private val StartButtonOverhang = 16.dp

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
    scrollBehavior: SearchBarScrollBehavior,
    onSearch: (String) -> Unit,
) {
    AppBarWithSearch(
        state = state,
        scrollBehavior = scrollBehavior,
        colors =
            SearchBarDefaults.appBarWithSearchColors(
                appBarContainerColor = Color.Transparent,
                scrolledAppBarContainerColor = Color.Transparent,
            ),
        shadowElevation = SearchBarShadowElevation,
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

private val SearchBarShadowElevation = 3.dp

@Composable
private fun PlaceholderScreen(
    message: String,
    contentPadding: PaddingValues,
) {
    val statusBarHeight = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this) }
    Box(
        modifier = Modifier.contentTopReserve(contentPadding, statusBarHeight).fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}
