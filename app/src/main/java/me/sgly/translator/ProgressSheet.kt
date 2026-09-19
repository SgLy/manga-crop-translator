package me.sgly.translator

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown while a request is running or has failed: the pipeline steps, the error detail, and the
 * model's reasoning once the setting asks for it.
 */
@Composable
fun ProgressSheet(phase: AppPhase) {
    val errorText by TranslatorState.errorText.collectAsState()
    val firstTokenReceived by TranslatorState.firstTokenReceived.collectAsState()
    val reasoning by TranslatorState.reasoning.collectAsState()
    val context = LocalContext.current
    val showReasoning = remember { SettingsManager(context).showReasoning }
    val ocrStatus =
        when (phase) {
            AppPhase.Recognizing -> if (firstTokenReceived) StepUiStatus.Streaming else StepUiStatus.Waiting
            AppPhase.OcrError -> StepUiStatus.Error
            AppPhase.Parsing, AppPhase.TranslateError -> StepUiStatus.Done
            else -> StepUiStatus.Idle
        }
    val translateStatus =
        when (phase) {
            AppPhase.Parsing -> if (firstTokenReceived) StepUiStatus.Streaming else StepUiStatus.Waiting
            AppPhase.TranslateError -> StepUiStatus.Error
            else -> StepUiStatus.Idle
        }

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

        val inFlight = phase == AppPhase.Recognizing || phase == AppPhase.Parsing
        if (showReasoning && inFlight && reasoning.isNotEmpty()) {
            ReasoningStrip(reasoning)
        }

        ProgressActions(phase)
    }
}

@Composable
private fun ProgressActions(phase: AppPhase) {
    if (phase != AppPhase.OcrError && phase != AppPhase.TranslateError) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = {
                val nextPhase = if (phase == AppPhase.TranslateError) AppPhase.Parsing else AppPhase.Recognizing
                TranslatorState.transition(nextPhase, TransitionReason.Retry)
            },
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

enum class StepUiStatus { Idle, Waiting, Streaming, Done, Error }

/** Shows the newest lines of the model's reasoning in a short window that follows the stream. */
@Composable
fun ReasoningStrip(reasoning: String) {
    val lineHeight = 16.sp
    val verticalPadding = 8.dp
    val scrollState = rememberScrollState()
    var lastMaxValue by remember { mutableIntStateOf(0) }
    val maxValue = scrollState.maxValue
    LaunchedEffect(maxValue) {
        // maxValue stays Int.MAX_VALUE until the first layout, so the very first frame must be ignored.
        if (maxValue == Int.MAX_VALUE || maxValue <= lastMaxValue) return@LaunchedEffect
        lastMaxValue = maxValue
        scrollState.animateScrollTo(maxValue, tween(durationMillis = 100, easing = LinearEasing))
    }
    // The padding sits above the first line, so it has to be part of the fade as well.
    val fadeHeight = with(LocalDensity.current) { verticalPadding.toPx() + (lineHeight / 2).toPx() }
    Text(
        text = reasoning,
        fontSize = 12.sp,
        lineHeight = lineHeight,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(vertical = verticalPadding)
                .fadeEdges(fadeHeight)
                .height(with(LocalDensity.current) { (lineHeight * 5f).toDp() })
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp),
    )
}

private fun Modifier.fadeEdges(fadeHeight: Float): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val fade = fadeHeight / size.height
            drawRect(
                brush =
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        fade to Color.Black,
                        (1f - fade) to Color.Black,
                        1f to Color.Transparent,
                    ),
                blendMode = BlendMode.DstIn,
            )
        }

@Composable
fun StepFlow(
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
        StepRow(label = stringResource(R.string.step_parse), status = translateStatus)
    }
}

@Composable
fun StepRow(
    label: String,
    status: StepUiStatus,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            StepUiStatus.Waiting -> {
                // Request sent, no SSE chunk yet: something is happening, we just cannot see it.
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            StepUiStatus.Streaming -> {
                // First token has landed: the spinner gives way to a pulsing down arrow,
                // which reads as "output is flowing" rather than "still waiting".
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
                    imageVector = Icons.Outlined.ArrowDownward,
                    contentDescription = stringResource(R.string.cd_streaming),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = blink),
                    modifier = Modifier.size(16.dp),
                )
            }

            StepUiStatus.Done -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp),
                )
            }

            StepUiStatus.Error -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }

            StepUiStatus.Idle -> {
                Spacer(modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

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
