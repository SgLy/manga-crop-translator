package me.sgly.translator.sheet

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.sgly.translator.state.TranslatorState
import kotlin.math.exp
import kotlin.math.roundToInt

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
                .padding(horizontal = StripInnerGap),
    )
}

private val StripSideGap = 16.dp
private val StripTopGap = 16.dp
private val StripBottomGap = 56.dp
private val StripInnerGap = 12.dp

private val CellSize = 9.dp
private val CellGap = 3.dp
private val CellCorner = 2.dp
private const val GRID_ROWS = 8
private const val STRONG_ALPHA = 0.4f
private const val WEAK_ALPHA = 0.3f
private const val GRID_LAG_MILLIS = 60f
private const val SCROLL_SNAP_EPSILON = 0.005f

@Composable
internal fun ReasoningGrid() {
    val grid = TranslatorState.streamGrid
    val cellColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = StripSideGap + StripInnerGap,
                    top = StripTopGap,
                    end = StripSideGap + StripInnerGap,
                    bottom = StripBottomGap,
                ),
    ) {
        val pitch = CellSize + CellGap
        val columns = maxOf(1, ((maxWidth + CellGap) / pitch).toInt())
        val fadeHeight = with(LocalDensity.current) { pitch.toPx() }
        LaunchedEffect(columns) { grid.setColumns(columns) }
        var offset by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            var scroll = grid.scrollTarget(GRID_ROWS).toFloat()
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val elapsedMillis = (now - last) / 1_000_000f
                last = now
                val target = grid.scrollTarget(GRID_ROWS).toFloat()
                val next =
                    if (target < scroll) {
                        target
                    } else {
                        scroll + (target - scroll) * (1f - exp(-elapsedMillis / GRID_LAG_MILLIS))
                    }
                scroll = if (target - next < SCROLL_SNAP_EPSILON) target else next
                var gridCells = grid.state
                while (scroll - gridCells.firstRowIndex >= 1f && gridCells.rows.size > 1) {
                    grid.popFirstRow()
                    gridCells = grid.state
                }
                // Trimmed rows are no longer in the buffer; the view must follow, or the offset goes negative.
                if (scroll < gridCells.firstRowIndex) scroll = gridCells.firstRowIndex.toFloat()
                offset = scroll - gridCells.firstRowIndex
            }
        }
        Layout(
            content = {
                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .requiredHeight(pitch * grid.capacity)
                            .graphicsLayer {
                                translationY = -offset * pitch.toPx()
                                compositingStrategy = CompositingStrategy.Offscreen
                            },
                ) {
                    val gridCells = grid.state
                    val pitchPx = pitch.toPx().roundToInt()
                    val cellPx = CellSize.toPx().roundToInt().toFloat()
                    val corner = CornerRadius(CellCorner.toPx().roundToInt().toFloat())
                    val strongColor = cellColor.copy(alpha = STRONG_ALPHA)
                    val weakColor = cellColor.copy(alpha = WEAK_ALPHA)
                    val leftPad = ((size.width - ((columns - 1) * pitchPx + cellPx)) / 2f).roundToInt()
                    gridCells.rows.forEachIndexed { index, row ->
                        val filled = gridCells.filledIn(index)
                        val y = (index * pitchPx).toFloat()
                        for (column in 0 until filled) {
                            drawRoundRect(
                                color = if (row[column]) strongColor else weakColor,
                                topLeft = Offset((leftPad + column * pitchPx).toFloat(), y),
                                size = Size(cellPx, cellPx),
                                cornerRadius = corner,
                            )
                        }
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(pitch * GRID_ROWS - CellGap)
                    .clipToBounds()
                    .fadeEdges(fadeHeight),
        ) { measurables, constraints ->
            // The child is taller than the viewport, so default measuring would clamp and centre it.
            val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
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
