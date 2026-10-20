package me.sgly.translator.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.sgly.translator.api.TranslationResult
import me.sgly.translator.api.TranslationWord

/**
 * One card in the bottom vocabulary strip. The block index is what makes a card more than a label:
 * it points back at the sentence the word was found in.
 */
internal data class WordCard(
    val blockIndex: Int,
    val word: TranslationWord,
)

/** The cards of a result, ranked by difficulty. */
internal fun wordCards(result: TranslationResult): List<WordCard> =
    result.results
        .flatMapIndexed { blockIndex, block ->
            block.columns.flatten().map { WordCard(blockIndex, it) }
        }.filter { it.word.difficulty != null }
        .sortedByDescending { it.word.difficulty }

@Composable
internal fun CardStrip(
    cards: List<WordCard>,
    selectedIndices: Set<Int>,
    onToggleSelect: (Int) -> Unit,
) {
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cards.forEachIndexed { index, card ->
            ToggleButton(
                checked = selectedIndices.contains(index),
                onCheckedChange = { onToggleSelect(index) },
            ) {
                Text(
                    text = card.word.lemma,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
