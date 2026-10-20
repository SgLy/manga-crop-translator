package me.sgly.translator.vocabulary

import android.content.Context
import me.sgly.translator.api.OcrResult
import me.sgly.translator.api.TranslationResult
import me.sgly.translator.logs.AppLog
import me.sgly.translator.sheet.WordCard
import me.sgly.translator.state.TranslatorState
import me.sgly.translator.stats.StatsDatabase

internal suspend fun recordSavedWords(
    context: Context,
    occurrences: List<VocabularyOccurrence>,
) {
    VocabularyDatabase.get(context).vocabularyDao().record(occurrences)
    val translationId = TranslatorState.translationId ?: return
    // A stats failure must never surface, so it is swallowed.
    try {
        StatsDatabase.get(context).statsDao().setRecordedWords(
            translationId,
            occurrences.size,
            occurrences.count { !it.origin.isNullOrBlank() },
        )
    } catch (e: Exception) {
        AppLog.w("Stats", "saved words not recorded: ${e.message}")
    }
}

/**
 * The database rows behind one save: a card's sentence is its whole block, which is the unit the
 * model translated and the unit the reader heard as "this sentence".
 */
internal fun occurrencesFor(
    cards: List<WordCard>,
    selectedIndices: Set<Int>,
    ocrResult: OcrResult?,
    translationResult: TranslationResult?,
): List<VocabularyOccurrence> {
    val blocks = ocrResult?.blocks ?: return emptyList()
    val results = translationResult?.results ?: return emptyList()
    return selectedIndices.mapNotNull { index ->
        val card = cards.getOrNull(index) ?: return@mapNotNull null
        val sentence = blocks.getOrNull(card.blockIndex)?.joinToString("") ?: return@mapNotNull null
        VocabularyOccurrence(
            lemma = card.word.lemma,
            reading = card.word.reading,
            origin = card.word.origin,
            surface = card.word.word,
            definition = card.word.definition,
            sentence = sentence,
            translation = results.getOrNull(card.blockIndex)?.translation.orEmpty(),
        )
    }
}
