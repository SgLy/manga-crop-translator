package me.sgly.translator.stats

import android.content.Context
import me.sgly.translator.api.RequestStats
import me.sgly.translator.logs.AppLog
import me.sgly.translator.state.TranslatorState

/** A stats failure must never surface, so it is swallowed. */
internal suspend fun recordRequest(
    context: Context,
    stats: RequestStats,
) {
    val reason = TranslatorState.runReason ?: return
    try {
        val dao = StatsDatabase.get(context).statsDao()
        val translationId =
            TranslatorState.translationId
                ?: dao.insertTranslation(Translation(createdAt = stats.startedAtMillis)).also {
                    TranslatorState.translationId = it
                }
        val roundId =
            TranslatorState.roundId
                ?: dao
                    .insertRoundRow(
                        InferenceRound(
                            translationId = translationId,
                            reason = reason.name,
                        ),
                    ).also { TranslatorState.roundId = it }
        dao.insertRequests(
            listOf(
                InferenceRequest(
                    roundId = roundId,
                    createdAt = stats.startedAtMillis,
                    kind = stats.kind.name,
                    state = stats.state.name,
                    model = stats.model,
                    thinkingEffort = TranslatorState.currentThinkingEffort(),
                    inputTokens = stats.usage.input,
                    cachedInputTokens = stats.usage.cached,
                    outputTokens = stats.usage.output,
                    reasoningTokens = stats.usage.reasoning,
                    ttftMillis = stats.ttftMillis,
                    elapsedMillis = stats.elapsedMillis,
                ),
            ),
        )
    } catch (e: Exception) {
        AppLog.w("Stats", "request not recorded: ${e.message}")
    }
}
