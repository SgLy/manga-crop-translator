package me.sgly.translator.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.sgly.translator.R
import me.sgly.translator.api.RequestStats
import me.sgly.translator.api.TokenUsage
import java.util.Locale

@Composable
internal fun UsageStats(requests: List<RequestStats>) {
    val usage = requests.map { it.usage }.reduce(TokenUsage::plus)
    val elapsedMillis = requests.sumOf { it.elapsedMillis }
    val generationMillis = (elapsedMillis - requests.sumOf { it.ttftMillis }).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenCount(R.drawable.ic_arrow_upward, R.string.cd_token_input, usage.freshInput.toString())
                TokenValue("+")
                TokenCount(R.drawable.ic_cached, R.string.cd_token_cached, usage.cached.toString())
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenCount(R.drawable.ic_arrow_downward, R.string.cd_token_output, usage.freshOutput.toString())
                TokenValue("+")
                TokenCount(R.drawable.ic_psychology, R.string.cd_token_reasoning, usage.reasoning.toString())
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TokenCount(R.drawable.ic_bolt, R.string.cd_ttft, requests.joinToString(" / ") { formatSeconds(it.ttftMillis) })
            TokenCount(R.drawable.ic_schedule, R.string.cd_elapsed, formatSeconds(elapsedMillis, 1))
            TokenCount(R.drawable.ic_speed, R.string.cd_token_rate, "${usage.output * 1000L / generationMillis} t/s")
        }
    }
}

@Composable
private fun TokenCount(
    icon: Int,
    description: Int,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(12.dp),
        )
        TokenValue(value)
    }
}

@Composable
private fun TokenValue(value: String) {
    Text(
        text = value,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun formatSeconds(
    millis: Long,
    decimals: Int = 2,
): String = String.format(Locale.US, "%.${decimals}fs", millis / 1000.0)
