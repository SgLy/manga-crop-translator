package me.sgly.translator.sheet

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.sgly.translator.R
import me.sgly.translator.api.OcrResult
import me.sgly.translator.api.RequestState
import me.sgly.translator.api.RequestStats
import me.sgly.translator.api.TranslationResult
import me.sgly.translator.logs.LogsActivity
import me.sgly.translator.settings.SettingsManager
import me.sgly.translator.state.AppPhase
import me.sgly.translator.state.TransitionReason
import me.sgly.translator.state.TranslatorState
import me.sgly.translator.vocabulary.occurrencesFor
import me.sgly.translator.vocabulary.recordSavedWords

@Composable
fun ResultSheet(phase: AppPhase) {
    val ocrResult by TranslatorState.ocrResult.collectAsState()
    val translationResult by TranslatorState.translationResult.collectAsState()
    val runRequests by TranslatorState.runRequests.collectAsState()

    val cards = remember(translationResult) { translationResult?.let(::wordCards).orEmpty() }
    val selectedIndices = remember { mutableStateSetOf<Int>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val showTokenUsage = remember { SettingsManager(context).showTokenUsage }
    val requests = if (showTokenUsage) runRequests.filter { it.state == RequestState.Success } else emptyList()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(start = 16.dp, end = 16.dp),
    ) {
        ResultBody(ocrResult, translationResult)

        ResultExtras(
            requests = requests,
            cards = cards,
            selectedIndices = selectedIndices,
            onToggleSelect = { index ->
                if (selectedIndices.contains(index)) selectedIndices.remove(index) else selectedIndices.add(index)
            },
        )

        ResultActions(
            phase = phase,
            hasSelection = selectedIndices.isNotEmpty(),
            onThinkHarder = { TranslatorState.transition(AppPhase.Recognizing, TransitionReason.ThinkHarder) },
            onSave = {
                val occurrences = occurrencesFor(cards, selectedIndices, ocrResult, translationResult)
                scope.launch {
                    recordSavedWords(context, occurrences)
                    val message = resources.getQuantityString(R.plurals.saved_words, occurrences.size, occurrences.size)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss)
                }
            },
            onConfirm = { TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss) },
            onShowLogs = {
                TranslatorState.transition(AppPhase.ShowingLogs, TransitionReason.ShowLogs)
                context.startActivity(Intent(context, LogsActivity::class.java))
            },
        )
    }
}

@Composable
private fun ResultBody(
    ocrResult: OcrResult?,
    translationResult: TranslationResult?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 400.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        if (ocrResult != null && translationResult != null) {
            ResultBlocks(
                ocrResult = ocrResult,
                translationResult = translationResult,
            )
        }
    }
}

@Composable
private fun ResultExtras(
    requests: List<RequestStats>,
    cards: List<WordCard>,
    selectedIndices: Set<Int>,
    onToggleSelect: (Int) -> Unit,
) {
    if (requests.isNotEmpty() || cards.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 48.dp),
        ) {
            if (requests.isNotEmpty()) {
                UsageStats(requests)
            }
            if (cards.isNotEmpty()) {
                CardStrip(
                    cards = cards,
                    selectedIndices = selectedIndices,
                    onToggleSelect = onToggleSelect,
                )
            }
        }
    }
}
