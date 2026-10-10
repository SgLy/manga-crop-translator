package me.sgly.translator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

private const val GRID_WEEKS = 53

private val CellSize = 11.dp
private val CellGap = 3.dp
private val CardGap = 12.dp
private val SectionGap = 28.dp

private fun level(runs: Int): Int =
    when {
        runs >= 10 -> 4
        runs >= 6 -> 3
        runs >= 3 -> 2
        runs >= 1 -> 1
        else -> 0
    }

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val dao = remember { StatsDatabase.get(context).statsDao() }
    val totals by remember { dao.totals() }.collectAsState(initial = null)
    val counts by remember { dao.countsByKind() }.collectAsState(initial = emptyList())
    val daily by remember { dao.dailyRuns() }.collectAsState(initial = emptyList())

    val stats = totals ?: return
    val visionCount = counts.firstOrNull { it.kind == RequestKind.Vision.name }?.count ?: 0L
    val translateCount = counts.firstOrNull { it.kind == RequestKind.Translate.name }?.count ?: 0L
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = StartButtonOverhang + 16.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        if (stats.translations == 0L) {
            Text(
                text = stringResource(R.string.stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
            WordsCard(stats)
            ActivityCard(daily)
        }
        Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
            TokenCard(stats)
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                MetricCard(
                    label = R.string.stats_inference_total,
                    value = compact(stats.requests),
                    modifier = Modifier.weight(1f),
                    notes =
                        listOf(
                            "${stringResource(R.string.stats_vision)} ${compact(visionCount)}",
                            "${stringResource(R.string.stats_parse)} ${compact(translateCount)}",
                        ),
                )
                MetricCard(R.string.stats_speed, "${tokensPerSecond(stats)} t/s", Modifier.weight(1f))
            }
            AverageCard(stats)
        }
    }
}

@Composable
private fun WordsCard(stats: StatsTotals) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_saved),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = compact(stats.recordedWordCount),
                style = MaterialTheme.typography.displaySmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Breakdown(R.string.stats_runs, compact(stats.translations))
                Breakdown(R.string.stats_loanwords, compact(stats.recordedLoanwordCount), nested = true)
            }
        }
    }
}

@Composable
private fun TokenCard(stats: StatsTotals) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_total_tokens),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = compact(stats.inputTokens + stats.outputTokens),
                style = MaterialTheme.typography.headlineMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Breakdown(R.string.stats_input, compact(stats.inputTokens))
                Breakdown(R.string.stats_cached, compact(stats.cachedInputTokens), nested = true)
                Breakdown(R.string.stats_cache_hit, percent(stats.cachedInputTokens, stats.inputTokens), nested = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Breakdown(R.string.stats_output, compact(stats.outputTokens))
                Breakdown(R.string.stats_reasoning, compact(stats.reasoningTokens), nested = true)
                Breakdown(R.string.stats_reasoning_share, percent(stats.reasoningTokens, stats.outputTokens), nested = true)
            }
        }
    }
}

@Composable
private fun Breakdown(
    label: Int,
    value: String,
    nested: Boolean = false,
) {
    Row(
        modifier = if (nested) Modifier.alpha(0.7f) else Modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current.copy(alpha = 0.75f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MetricCard(
    label: Int,
    value: String,
    modifier: Modifier = Modifier,
    notes: List<String> = emptyList(),
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notes.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActivityCard(daily: List<DailyRuns>) {
    val runsByDay = remember(daily) { daily.associate { LocalDate.parse(it.day) to it.runs } }
    val firstMonday =
        remember {
            LocalDate.now().minusWeeks((GRID_WEEKS - 1).toLong()).with(DayOfWeek.MONDAY)
        }
    val gridScroll = rememberScrollState()
    LaunchedEffect(gridScroll.maxValue) { gridScroll.scrollTo(gridScroll.maxValue) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_activity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(gridScroll),
                horizontalArrangement = Arrangement.spacedBy(CellGap),
            ) {
                repeat(GRID_WEEKS) { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(CellGap)) {
                        repeat(7) { day ->
                            val date = firstMonday.plusWeeks(week.toLong()).plusDays(day.toLong())
                            Cell(level(runsByDay[date] ?: 0), marked = date.dayOfMonth == 1)
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_legend_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                repeat(5) { Cell(it) }
                Text(
                    text = stringResource(R.string.stats_legend_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Cell(
    level: Int,
    marked: Boolean = false,
) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier =
            Modifier
                .size(CellSize)
                .clip(shape)
                .background(levelColor(level))
                .then(
                    if (marked) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    } else {
                        Modifier
                    },
                ),
    )
}

@Composable
private fun levelColor(level: Int): Color =
    if (level == 0) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f + 0.2f * level)
    }

@Composable
private fun AverageCard(stats: StatsTotals) {
    val rounds = stats.rounds.coerceAtLeast(1)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_average),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AverageRow(R.string.stats_average_tokens, compact((stats.inputTokens + stats.outputTokens) / rounds))
            AverageRow(R.string.stats_average_elapsed, duration(stats.elapsedMillis / rounds))
            AverageRow(R.string.stats_avg_reasoning, duration((stats.elapsedMillis - stats.totalTtftMillis) / rounds))
        }
    }
}

@Composable
private fun AverageRow(
    label: Int,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Tokens generated per second over the whole history, generation time only. */
private fun tokensPerSecond(stats: StatsTotals): Int {
    val generation = (stats.elapsedMillis - stats.totalTtftMillis).coerceAtLeast(1)
    return (stats.outputTokens * 1000L / generation).toInt()
}

private fun percent(
    part: Long,
    whole: Long,
): String = if (whole == 0L) "—" else String.format(Locale.US, "%.0f%%", part * 100.0 / whole)

private fun compact(value: Long): String =
    when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000.0)
        value >= 10_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> exact(value)
    }

private fun exact(value: Long): String = String.format(Locale.US, "%,d", value)

private fun duration(millis: Long): String =
    when {
        millis >= 3_600_000 -> String.format(Locale.US, "%.1fh", millis / 3_600_000.0)
        millis >= 60_000 -> String.format(Locale.US, "%.0fm", millis / 60_000.0)
        else -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    }
