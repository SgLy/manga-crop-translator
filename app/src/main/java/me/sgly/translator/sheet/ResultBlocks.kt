package me.sgly.translator.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.sgly.translator.api.OcrResult
import me.sgly.translator.api.TranslationResult
import me.sgly.translator.api.TranslationWord
import me.sgly.translator.ui.theme.jp.japaneseTextStyle

@Composable
internal fun ResultBlocks(
    ocrResult: OcrResult,
    translationResult: TranslationResult,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
    ) {
        ocrResult.blocks.forEachIndexed { index, block ->
            val columns =
                translationResult.results
                    .getOrNull(index)
                    ?.columns
                    .orEmpty()
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                // Breathing room between wrapped source lines (the gloss row sits inside them).
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.forEachIndexed { segIndex, segment ->
                    if (segIndex > 0) {
                        VerticalDivider(
                            modifier =
                                Modifier
                                    .height(16.dp)
                                    .padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 1.dp,
                        )
                    }
                    SourceColumn(
                        segment = segment,
                        words = columns.getOrNull(segIndex).orEmpty(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val translation = translationResult.results.getOrNull(index)?.translation ?: ""
            Text(
                text = translation,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (index < ocrResult.blocks.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SourceColumn(
    segment: String,
    words: List<TranslationWord>,
) {
    tokenizeSegment(segment, words).forEach { token ->
        when (token) {
            is SourceToken.Plain -> {
                chunkPlainText(token.text).forEach { chunk ->
                    Text(
                        text = chunk,
                        style = japaneseTextStyle(FontWeight.Normal),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            is SourceToken.Annotated -> {
                AnnotatedWord(token)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotatedWord(token: SourceToken.Annotated) {
    val origin = token.word.origin?.takeIf { it.isNotBlank() }
    val tooltip = origin ?: token.word.reading

    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider =
            TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Below,
            ),
        state = tooltipState,
        enableUserInput = false,
        tooltip = { PlainTooltip { Text(tooltip) } },
    ) {
        Box(modifier = Modifier.clickable { scope.launch { tooltipState.show() } }) {
            AnnotatedWordFace(token = token, origin = origin)
        }
    }
}

@Composable
private fun AnnotatedWordFace(
    token: SourceToken.Annotated,
    origin: String?,
) {
    Column(
        modifier =
            Modifier
                .width(IntrinsicSize.Max)
                .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = token.surface,
            style = japaneseTextStyle(FontWeight.Normal),
            fontSize = 15.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(1.dp),
                    ),
        )
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = token.word.definition,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (origin != null) {
                Text(
                    text = "+",
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.offset(y = (-3).dp),
                )
            }
        }
    }
}
