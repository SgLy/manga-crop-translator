package me.sgly.translator.vocabulary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.sgly.translator.R
import me.sgly.translator.ui.theme.jp.japaneseTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VocabularyDetailScreen(
    wordId: Long,
    dao: VocabularyDao,
    onFinish: () -> Unit,
) {
    val stored by remember { dao.wordWithEntries(wordId) }
        .collectAsState(initial = null)
    val word = stored
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var restoredEntryId by remember { mutableStateOf<Long?>(null) }
    val entryDeleted = stringResource(R.string.entry_deleted)
    val undoLabel = stringResource(R.string.action_undo)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DetailTopBar(
                word = word?.word,
                scrollBehavior = scrollBehavior,
                onBack = onFinish,
            )
        },
    ) { innerPadding ->
        val loaded = word
        if (loaded != null) {
            EntryList(
                word = loaded,
                scrollBehavior = scrollBehavior,
                restoredEntryId = restoredEntryId,
                onEntryExpanded = { restoredEntryId = null },
                onDelete = { entry ->
                    scope.launch {
                        dao.deleteEntry(entry)
                        val undo =
                            snackbarHostState.showSnackbar(entryDeleted, undoLabel)
                        if (undo == SnackbarResult.ActionPerformed) {
                            // Flagged before the write, so the row that comes back knows to expand.
                            restoredEntryId = entry.id
                            dao.restoreEntry(entry)
                        }
                    }
                },
                onDeleteWord = {
                    scope.launch {
                        dao.deleteWord(wordId)
                        onFinish()
                    }
                },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    word: VocabularyWord?,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
) {
    LargeFlexibleTopAppBar(
        title = {
            Text(
                text = word?.lemma.orEmpty(),
                style = japaneseTextStyle(FontWeight.Normal),
            )
        },
        subtitle = {
            Text(
                text = word?.let { it.origin.ifEmpty { it.reading } }.orEmpty(),
                style = japaneseTextStyle(FontWeight.Normal),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryList(
    word: VocabularyWordWithEntries,
    scrollBehavior: TopAppBarScrollBehavior,
    restoredEntryId: Long?,
    onEntryExpanded: () -> Unit,
    onDelete: (VocabularyEntry) -> Unit,
    onDeleteWord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val entries = word.entries.sortedByDescending { it.createdAt }
        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 10.dp),
        ) {
            items(
                items = entries,
                key = { entry -> entry.id },
            ) { entry ->
                SwipeableEntryRow(
                    entry = entry,
                    lemma = word.word.lemma,
                    isOnlyEntry = entries.size == 1,
                    isRestored = restoredEntryId == entry.id,
                    onDelete = { onDelete(entry) },
                    onDeleteWord = onDeleteWord,
                    onExpanded = { if (restoredEntryId == entry.id) onEntryExpanded() },
                )
            }
        }
    }
}
