package me.sgly.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.sgly.translator.ui.theme.CropTranslatorTheme

/** Everything ever recorded about one word, newest entry first. */
class VocabularyDetailActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wordId = intent.getLongExtra(EXTRA_WORD_ID, -1L)
        val dao = VocabularyDatabase.get(this).vocabularyDao()

        setContent {
            CropTranslatorTheme {
                val word by remember { dao.wordWithEntries(wordId) }
                    .collectAsState(initial = null)
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var restoredEntryId by remember { mutableStateOf<Long?>(null) }
                val entryDeleted = stringResource(R.string.entry_deleted)
                val undoLabel = stringResource(R.string.action_undo)

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_back),
                                        contentDescription = stringResource(R.string.cd_back),
                                    )
                                }
                            },
                        )
                    },
                ) { innerPadding ->
                    val loaded = word
                    if (loaded != null) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                        ) {
                            // Outside the list, so a long history scrolls under a headword that stays put.
                            HeadwordRow(
                                word = loaded.word,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                            // 80dp of clearance, less the share the first row now carries itself.
                            Spacer(modifier = Modifier.height(66.dp))
                            val entries = loaded.entries.sortedByDescending { it.createdAt }
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(bottom = 10.dp),
                            ) {
                                items(
                                    items = entries,
                                    key = { entry -> entry.id },
                                ) { entry ->
                                    SwipeableEntryRow(
                                        entry = entry,
                                        lemma = loaded.word.lemma,
                                        isOnlyEntry = entries.size == 1,
                                        isRestored = restoredEntryId == entry.id,
                                        onDelete = {
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
                                                finish()
                                            }
                                        },
                                        onExpanded = {
                                            if (restoredEntryId == entry.id) restoredEntryId = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_WORD_ID = "wordId"
    }
}

/** The headword in display size, its reading underneath and its etymology beside it on the baseline. */
@Composable
private fun HeadwordRow(
    word: VocabularyWord,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row {
            Text(
                text = word.lemma,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.alignByBaseline(),
            )
            if (word.origin.isNotEmpty()) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = word.origin,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Text(
            text = word.reading,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What a collapsing row should remove once it has finished collapsing. */
private enum class Deletion { Entry, Word }

/**
 * One entry, swipe-able leftwards to delete it. The last one cannot go on its own — that would
 * leave a headword with nothing behind it — so it stays swiped open while the dialog asks, and only
 * slides back if the answer is to keep the word.
 */
@Composable
private fun SwipeableEntryRow(
    entry: VocabularyEntry,
    lemma: String,
    isOnlyEntry: Boolean,
    isRestored: Boolean,
    onDelete: () -> Unit,
    onDeleteWord: () -> Unit,
    onExpanded: () -> Unit,
) {
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState =
        remember {
            SwipeToDismissBoxState(
                initialValue = SwipeToDismissBoxValue.Settled,
                positionalThreshold = positionalThreshold,
            )
        }
    val scope = rememberCoroutineScope()
    var askingToDeleteWord by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<Deletion?>(null) }
    val visibleState = remember { MutableTransitionState(!isRestored) }
    LaunchedEffect(Unit) { if (isRestored) visibleState.targetState = true }

    val keepWord: () -> Unit = {
        askingToDeleteWord = false
        scope.launch { dismissState.reset() }
    }

    val collapse: (Deletion) -> Unit = { deletion ->
        pendingDeletion = deletion
        visibleState.targetState = false
    }

    // The row leaves by collapsing to zero height, and only then is the database told: deleting first
    // would have the flow yank the row away mid-air. A collapse we asked for is the only one that
    // counts — a row that merely *starts* collapsed is a restored one, not a departing one.
    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (!visibleState.isIdle) return@LaunchedEffect
        if (visibleState.currentState) {
            if (isRestored) onExpanded()
            return@LaunchedEffect
        }
        val deletion = pendingDeletion ?: return@LaunchedEffect
        pendingDeletion = null
        if (deletion == Deletion.Word) onDeleteWord() else onDelete()
    }

    // The last entry cannot go away on its own, so it stays open and lets the dialog decide.
    LaunchedEffect(dismissState.settledValue) {
        if (dismissState.settledValue != SwipeToDismissBoxValue.EndToStart) return@LaunchedEffect
        if (isOnlyEntry) askingToDeleteWord = true else collapse(Deletion.Entry)
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            },
        ) {
            // The text is inset, but the background behind it spans the whole slot — the screen edge
            // sideways, and the neighbouring rows above and below — so a swipe reveals one band.
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                EntryRow(entry)
            }
        }
    }

    if (askingToDeleteWord) {
        AlertDialog(
            onDismissRequest = keepWord,
            title = { Text(stringResource(R.string.delete_word_title, lemma)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToDeleteWord = false
                        collapse(Deletion.Word)
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = keepWord) {
                    Text(stringResource(R.string.action_keep))
                }
            },
        )
    }
}

/** One entry: the meaning it carried here, when it was noted, and the sentence it came from. */
@Composable
private fun EntryRow(entry: VocabularyEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row {
            Text(
                text = entry.definition,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = formatAddedAt(entry.createdAt),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text =
                sentenceWithSurfaceHighlighted(
                    sentence = entry.sentence,
                    surface = entry.surface,
                    highlight = MaterialTheme.colorScheme.primary,
                ),
            fontSize = 15.sp,
            lineHeight = 22.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.translation,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Marks the word inside the sentence it was taken from, underlined in the app's emphasis colour. */
private fun sentenceWithSurfaceHighlighted(
    sentence: String,
    surface: String,
    highlight: Color,
) = buildAnnotatedString {
    val index = sentence.indexOf(surface)
    if (index < 0) {
        append(sentence)
        return@buildAnnotatedString
    }
    append(sentence, 0, index)
    withStyle(SpanStyle(color = highlight, textDecoration = TextDecoration.Underline)) {
        append(surface)
    }
    append(sentence, index + surface.length, sentence.length)
}

private fun formatAddedAt(millis: Long): String =
    android.text.format.DateFormat
        .format("yyyy-MM-dd HH:mm", millis)
        .toString()
