package me.sgly.translator.sheet

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.sgly.translator.state.TranslatorState
import kotlin.math.exp
import kotlin.math.floor

@Composable
internal fun ReasoningStrip(reasoning: String) {
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
                .padding(start = StripSideGap, top = StripTopGap, end = StripSideGap, bottom = StripBottomGap)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(vertical = verticalPadding)
                .fadeEdges(fadeHeight)
                .height(with(LocalDensity.current) { (lineHeight * 5f).toDp() })
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp),
    )
}

private val StripSideGap = 16.dp
private val StripTopGap = 16.dp
private val StripBottomGap = 56.dp
private val TapeHeight = 32.dp
private val TapePitch = 6.dp
private val TapeGap = 1.dp
private val TapeCorner = 2.dp
private val TapeMinHeight = 1.dp
private val TapeHeightPerChar = 1.5.dp
private const val TAPE_FADE_BARS = 12f
private const val TAPE_LAG_MILLIS = 10f
private const val TAPE_OPACITY = 0.3f

/**
 * A background pattern rather than a chart: no container of its own, faint bars, and both borders
 * fading a fixed number of bars' width.
 */
@Composable
internal fun ReasoningTape() {
    val tape = TranslatorState.streamTape
    val barColor = MaterialTheme.colorScheme.primary
    var head by remember { mutableFloatStateOf(tape.total.toFloat()) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsedMillis = (now - last) / 1_000_000f
            last = now
            val total = tape.total.toFloat()
            head = if (total < head) total else head + (total - head) * (1f - exp(-elapsedMillis / TAPE_LAG_MILLIS))
        }
    }
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = StripSideGap, top = StripTopGap, end = StripSideGap, bottom = StripBottomGap)
                .height(TapeHeight),
    ) {
        val pitch = TapePitch.toPx()
        val barWidth = pitch - TapeGap.toPx()
        val fadeWidth = TAPE_FADE_BARS * pitch
        val heightPerChar = TapeHeightPerChar.toPx()
        val minHeight = TapeMinHeight.toPx()
        val corner = CornerRadius(TapeCorner.toPx())
        val newest = tape.total - 1
        val oldest = maxOf(0, floor(head - size.width / pitch).toInt(), tape.total - tape.available)
        val barPath = Path()
        for (index in oldest..newest) {
            val length = tape.lengthAt(newest - index) ?: continue
            val x = size.width - (head - index) * pitch
            val height = (minHeight + length * heightPerChar).coerceAtMost(size.height)
            val alpha = minOf(x / fadeWidth, (size.width - x - barWidth) / fadeWidth, 1f)
            if (alpha <= 0f) continue
            barPath.rewind()
            barPath.addRoundRect(
                RoundRect(
                    rect = Rect(x, size.height - height, x + barWidth, size.height),
                    topLeft = corner,
                    topRight = corner,
                    bottomRight = CornerRadius.Zero,
                    bottomLeft = CornerRadius.Zero,
                ),
            )
            drawPath(path = barPath, color = barColor.copy(alpha = alpha * TAPE_OPACITY))
        }
    }
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
