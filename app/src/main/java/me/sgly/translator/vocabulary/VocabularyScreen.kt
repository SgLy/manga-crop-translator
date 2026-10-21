package me.sgly.translator.vocabulary

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.sgly.translator.ui.theme.jp.japaneseTextStyle

@Composable
internal fun VocabularyListScreen(summaries: List<VocabularySummary>) {
    val context = LocalContext.current

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(ListPadding),
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

private val ListPadding = 16.dp

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
