package me.sgly.translator.sheet

import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.sgly.translator.R
import me.sgly.translator.logs.LogsActivity
import me.sgly.translator.settings.SettingsManager
import me.sgly.translator.state.AppPhase
import me.sgly.translator.state.TransitionReason
import me.sgly.translator.state.TranslatorState

@Composable
fun ProgressSheet(phase: AppPhase) {
    val errorText by TranslatorState.errorText.collectAsState()
    val firstTokenReceived by TranslatorState.firstTokenReceived.collectAsState()
    val reasoning by TranslatorState.reasoning.collectAsState()
    val context = LocalContext.current
    val showReasoning = remember { SettingsManager(context).showReasoning }
    val (ocrStatus, translateStatus) = stepStatuses(phase, firstTokenReceived)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(start = 16.dp, end = 16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 400.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            StepFlow(ocrStatus = ocrStatus, translateStatus = translateStatus)

            if ((phase == AppPhase.OcrError || phase == AppPhase.TranslateError) && errorText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
        }

        val inFlight = phase == AppPhase.Recognizing || phase == AppPhase.Translating
        if (inFlight) {
            when {
                showReasoning && reasoning.isNotEmpty() -> ReasoningStrip(reasoning)
                !showReasoning -> ReasoningGrid()
            }
        }

        ProgressActions(phase)
    }
}

private fun stepStatuses(
    phase: AppPhase,
    firstTokenReceived: Boolean,
): Pair<StepUiStatus, StepUiStatus> {
    val ocrStatus =
        when (phase) {
            AppPhase.Recognizing -> if (firstTokenReceived) StepUiStatus.Streaming else StepUiStatus.Waiting
            AppPhase.OcrError -> StepUiStatus.Error
            AppPhase.Translating, AppPhase.TranslateError -> StepUiStatus.Done
            else -> StepUiStatus.Idle
        }
    val translateStatus =
        when (phase) {
            AppPhase.Translating -> if (firstTokenReceived) StepUiStatus.Streaming else StepUiStatus.Waiting
            AppPhase.TranslateError -> StepUiStatus.Error
            else -> StepUiStatus.Idle
        }
    return ocrStatus to translateStatus
}

@Composable
private fun ProgressActions(phase: AppPhase) {
    if (phase != AppPhase.OcrError && phase != AppPhase.TranslateError) return
    val context = LocalContext.current
    HorizontalFloatingToolbar(
        expanded = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
    ) {
        // The toolbar sizes its container from its first child's x offset, so this leading button also
        // keeps the height at ContainerSize — replacing it with a Spacer inflates the sheet to a blob.
        TextButton(
            onClick = {
                TranslatorState.transition(AppPhase.ShowingLogs, TransitionReason.ShowLogs)
                context.startActivity(Intent(context, LogsActivity::class.java))
            },
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
        ) {
            Text(stringResource(R.string.action_logs))
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                val nextPhase = if (phase == AppPhase.TranslateError) AppPhase.Translating else AppPhase.Recognizing
                TranslatorState.transition(nextPhase, TransitionReason.Retry)
            },
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

private enum class StepUiStatus { Idle, Waiting, Streaming, Done, Error }

@Composable
private fun StepFlow(
    ocrStatus: StepUiStatus,
    translateStatus: StepUiStatus,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepRow(label = stringResource(R.string.step_recognize), status = ocrStatus)
        Spacer(modifier = Modifier.width(40.dp))
        StepRow(label = stringResource(R.string.step_translate), status = translateStatus)
    }
}

@Composable
private fun StepRow(
    label: String,
    status: StepUiStatus,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepIcon(status)

        Spacer(modifier = Modifier.width(5.dp))

        Text(
            text = label,
            fontSize = 14.sp,
            color =
                when (status) {
                    StepUiStatus.Waiting, StepUiStatus.Streaming -> MaterialTheme.colorScheme.onSurface
                    StepUiStatus.Done -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    StepUiStatus.Error -> MaterialTheme.colorScheme.error
                    StepUiStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            fontWeight =
                when (status) {
                    StepUiStatus.Waiting, StepUiStatus.Streaming -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepIcon(status: StepUiStatus) {
    when (status) {
        StepUiStatus.Waiting -> {
            LoadingIndicator(
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        StepUiStatus.Streaming -> {
            val blink by rememberInfiniteTransition(label = "streamingBlink").animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 400, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "blinkAlpha",
            )
            Icon(
                painter = painterResource(R.drawable.ic_arrow_downward),
                contentDescription = stringResource(R.string.cd_streaming),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = blink),
                modifier = Modifier.size(16.dp),
            )
        }

        StepUiStatus.Done -> {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.cd_done),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }

        StepUiStatus.Error -> {
            Icon(
                painter = painterResource(R.drawable.ic_error),
                contentDescription = stringResource(R.string.cd_error),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }

        StepUiStatus.Idle -> {
            Spacer(modifier = Modifier.size(16.dp))
        }
    }
}
