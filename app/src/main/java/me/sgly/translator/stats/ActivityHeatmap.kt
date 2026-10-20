package me.sgly.translator.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.sgly.translator.R
import java.time.DayOfWeek
import java.time.LocalDate

private const val GRID_WEEKS = 53

private val CellSize = 11.dp
private val CellGap = 3.dp

private fun level(runs: Int): Int =
    when {
        runs >= 10 -> 4
        runs >= 6 -> 3
        runs >= 3 -> 2
        runs >= 1 -> 1
        else -> 0
    }

@Composable
internal fun ActivityHeatmap(daily: List<DailyRuns>) {
    val runsByDay = remember(daily) { daily.associate { LocalDate.parse(it.day) to it.runs } }
    val firstMonday =
        remember {
            LocalDate.now().minusWeeks((GRID_WEEKS - 1).toLong()).with(DayOfWeek.MONDAY)
        }
    val gridScroll = rememberScrollState()
    LaunchedEffect(gridScroll.maxValue) { gridScroll.scrollTo(gridScroll.maxValue) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
