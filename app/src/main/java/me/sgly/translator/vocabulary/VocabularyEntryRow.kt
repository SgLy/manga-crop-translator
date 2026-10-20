package me.sgly.translator.vocabulary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import me.sgly.translator.R
import me.sgly.translator.ui.theme.jp.japaneseTextStyle

private enum class Deletion { Entry, Word }

/**
 * One entry, swipe-able leftwards to delete it. The last one cannot go on its own — that would
 * leave a headword with nothing behind it — so it stays swiped open while the dialog asks, and only
 * slides back if the answer is to keep the word.
 */
@Composable
internal fun SwipeableEntryRow(
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
            backgroundContent = { SwipeDeleteBackground() },
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
        DeleteWordDialog(
            lemma = lemma,
            onConfirm = {
                askingToDeleteWord = false
                collapse(Deletion.Word)
            },
            onDismiss = keepWord,
        )
    }
}

@Composable
private fun SwipeDeleteBackground() {
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
}

@Composable
private fun DeleteWordDialog(
    lemma: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_word_title, lemma)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_keep))
            }
        },
    )
}

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
            style = japaneseTextStyle(FontWeight.Normal),
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
