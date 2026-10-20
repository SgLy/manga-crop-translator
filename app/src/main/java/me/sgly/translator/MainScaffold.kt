package me.sgly.translator

import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
                        onClick = onStartCapture,
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
