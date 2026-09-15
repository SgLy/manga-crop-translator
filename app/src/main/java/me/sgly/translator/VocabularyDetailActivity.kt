package me.sgly.translator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.sgly.translator.ui.theme.CropTranslatorTheme

/** Everything ever recorded about one word, newest sighting first. */
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

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                                    .padding(innerPadding)
                                    .padding(horizontal = 20.dp),
                        ) {
                            // Outside the list, so a long history scrolls under a headword that stays put.
                            HeadwordRow(loaded.word)
                            Spacer(modifier = Modifier.height(80.dp))
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                itemsIndexed(
                                    items = loaded.entries.sortedByDescending { it.createdAt },
                                    key = { _, entry -> entry.id },
                                ) { index, entry ->
                                    if (index > 0) {
                                        Spacer(modifier = Modifier.height(28.dp))
                                    }
                                    EntryRow(entry)
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

/** The headword in display size, with its etymology beside it on the same baseline. */
@Composable
private fun HeadwordRow(word: VocabularyWord) {
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
}

/** One sighting: the meaning it carried here, when it was noted, and the sentence it came from. */
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
