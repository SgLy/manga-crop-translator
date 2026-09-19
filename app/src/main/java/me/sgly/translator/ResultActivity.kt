package me.sgly.translator

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class ResultActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                val phase by TranslatorState.phase.collectAsState()
                val ocrResult by TranslatorState.ocrResult.collectAsState()
                val translationResult by TranslatorState.translationResult.collectAsState()
                val errorText by TranslatorState.errorText.collectAsState()
                val firstTokenReceived by TranslatorState.firstTokenReceived.collectAsState()
                val reasoning by TranslatorState.reasoning.collectAsState()

                val ocrStatus =
                    when (phase) {
                        AppPhase.Recognizing -> if (firstTokenReceived) StepUiStatus.Streaming else StepUiStatus.Waiting
                        AppPhase.OcrError -> StepUiStatus.Error
                        AppPhase.Parsing, AppPhase.Done, AppPhase.TranslateError -> StepUiStatus.Done
                        else -> StepUiStatus.Idle
                    }
                val translateStatus =
                    when (phase) {
                        AppPhase.Parsing -> if (firstTokenReceived) StepUiStatus.Streaming else StepUiStatus.Waiting
                        AppPhase.Done -> StepUiStatus.Done
                        AppPhase.TranslateError -> StepUiStatus.Error
                        else -> StepUiStatus.Idle
                    }

                // The bottom vocabulary strip, and the indices its selection refers to. Every card
                // remembers the block it came from, which is how saving recovers its sentence.
                val cards =
                    remember(translationResult) {
                        translationResult?.let(::vocabularyCards).orEmpty()
                    }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val resources = LocalResources.current
                val showReasoning = remember { SettingsManager(context).showReasoning }

                // Word card selection state
                val selectedIndices = remember { mutableStateSetOf<Int>() }

                LaunchedEffect(phase) {
                    if (phase == AppPhase.Idle || phase == AppPhase.Capturing || phase == AppPhase.Cropping) {
                        finish()
                    }
                }

                val isSheetVisible = phase != AppPhase.Idle && phase != AppPhase.Capturing && phase != AppPhase.Cropping
                if (isSheetVisible) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    ModalBottomSheet(
                        onDismissRequest = { TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss) },
                        sheetState = sheetState,
                        scrimColor = Color.Transparent,
                        dragHandle = { BottomSheetDefaults.DragHandle() },
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp)
                                    .padding(start = 16.dp, end = 16.dp, bottom = 0.dp),
                        ) {
                            // Scrollable content area
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp, max = 400.dp)
                                        .verticalScroll(rememberScrollState()),
                            ) {
                                if (phase != AppPhase.Done && phase != AppPhase.ViewingLast) {
                                    StepFlow(ocrStatus = ocrStatus, translateStatus = translateStatus)

                                    // Error detail
                                    if ((phase == AppPhase.OcrError || phase == AppPhase.TranslateError) && errorText.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = errorText,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }

                                // Result area: show structured blocks when both results are available
                                if (translationResult != null && ocrResult != null) {
                                    ResultBlocks(
                                        ocrResult = ocrResult!!,
                                        translationResult = translationResult!!,
                                        cards = cards,
                                        selectedIndices = selectedIndices,
                                        onToggleSelect = { index ->
                                            if (selectedIndices.contains(index)) {
                                                selectedIndices.remove(index)
                                            } else {
                                                selectedIndices.add(index)
                                            }
                                        },
                                    )
                                }
                            }

                            val inFlight = phase == AppPhase.Recognizing || phase == AppPhase.Parsing
                            if (showReasoning && inFlight && reasoning.isNotEmpty()) {
                                ReasoningStrip(reasoning)
                            }

                            // Fixed bottom bar: show in Done/OcrError/TranslateError phases
                            if (phase == AppPhase.Done || phase == AppPhase.OcrError || phase == AppPhase.TranslateError ||
                                phase == AppPhase.ViewingLast
                            ) {
                                BottomActionBar(
                                    phase = phase,
                                    hasSelection = selectedIndices.isNotEmpty(),
                                    onDismiss = { TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss) },
                                    onRetry = {
                                        val nextPhase = if (phase == AppPhase.TranslateError) AppPhase.Parsing else AppPhase.Recognizing
                                        TranslatorState.transition(nextPhase, TransitionReason.Retry)
                                    },
                                    onThinkHarder = { TranslatorState.transition(AppPhase.Recognizing, TransitionReason.ThinkHarder) },
                                    onSave = {
                                        val occurrences = occurrencesFor(cards, selectedIndices, ocrResult, translationResult)
                                        scope.launch {
                                            VocabularyDatabase.get(context).vocabularyDao().record(occurrences)
                                            val message =
                                                resources.getQuantityString(
                                                    R.plurals.saved_words,
                                                    occurrences.size,
                                                    occurrences.size,
                                                )
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                            TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss)
                                        }
                                    },
                                    onConfirm = { TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
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

/** A piece of a source segment: either verbatim text or a vocabulary word to gloss. */
private sealed interface SourceToken {
    data class Plain(
        val text: String,
    ) : SourceToken

    data class Annotated(
        val word: TranslationWord,
        val surface: String,
    ) : SourceToken
}

/**
 * Splits [segment] around [words]: each word is located at its first occurrence after the
 * previous match, so the annotated pieces keep the original reading order.
 */
private fun tokenizeSegment(
    segment: String,
    words: List<TranslationWord>,
): List<SourceToken> {
    val tokens = mutableListOf<SourceToken>()
    var cursor = 0
    for (word in words) {
        if (word.word.isEmpty()) continue
        val index = segment.indexOf(word.word, cursor)
        if (index < 0) continue
        if (index > cursor) tokens += SourceToken.Plain(segment.substring(cursor, index))
        tokens += SourceToken.Annotated(word, segment.substring(index, index + word.word.length))
        cursor = index + word.word.length
    }
    if (cursor < segment.length) tokens += SourceToken.Plain(segment.substring(cursor))
    return tokens
}

/**
 * Breaks plain text into pieces small enough to wrap inside a [FlowRow]: runs of ASCII
 * letters/digits stay glued together, everything else goes character by character (which is how
 * Japanese breaks anyway). Trailing whitespace sticks to the piece before it.
 */
private fun chunkPlainText(text: String): List<String> =
    buildList {
        var i = 0
        while (i < text.length) {
            val start = i
            if (text[i].isAsciiWordChar()) {
                while (i < text.length && text[i].isAsciiWordChar()) i++
            } else {
                i++
            }
            while (i < text.length && text[i].isWhitespace()) i++
            add(text.substring(start, i))
        }
    }

private fun Char.isAsciiWordChar(): Boolean = isLetterOrDigit() && code < 0x80

/**
 * A source word with its gloss underneath, tooltipped with the reading — or, on a loanword, with the
 * etymology, marked by a "+". The whole word is the anchor so it is easy to hit with a thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotatedWord(token: SourceToken.Annotated) {
    val origin = token.word.origin?.takeIf { it.isNotBlank() }
    val tooltip = origin ?: token.word.reading

    val word: @Composable () -> Unit = {
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
            word()
        }
    }
}

/**
 * One card in the bottom vocabulary strip. The block index is what makes a card more than a label:
 * it points back at the sentence the word was found in.
 */
data class VocabularyCard(
    val blockIndex: Int,
    val word: TranslationWord,
)

/** The cards of a result, ranked by difficulty — the strip's order, and the indices selection uses. */
private fun vocabularyCards(result: TranslationResult): List<VocabularyCard> =
    result.results
        .flatMapIndexed { blockIndex, block ->
            block.columns.flatten().map { VocabularyCard(blockIndex, it) }
        }.filter { it.word.difficulty != null }
        .sortedByDescending { it.word.difficulty }

@Composable
fun ResultBlocks(
    ocrResult: OcrResult,
    translationResult: TranslationResult,
    cards: List<VocabularyCard>,
    selectedIndices: Set<Int>,
    onToggleSelect: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
    ) {
        ocrResult.blocks.forEachIndexed { index, block ->
            // Japanese original text, with each vocabulary word glossed underneath.
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
                    tokenizeSegment(segment, columns.getOrNull(segIndex).orEmpty()).forEach { token ->
                        when (token) {
                            is SourceToken.Plain -> {
                                chunkPlainText(token.text).forEach { chunk ->
                                    Text(
                                        text = chunk,
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
            }
            Spacer(modifier = Modifier.height(10.dp))
            // Corresponding Chinese translation
            val translation = translationResult.results.getOrNull(index)?.translation ?: ""
            Text(
                text = translation,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Divider between blocks (not after the last one)
            if (index < ocrResult.blocks.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (cards.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cards.forEachIndexed { index, card ->
                    val isSelected = selectedIndices.contains(index)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        border =
                            BorderStroke(
                                1.dp,
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        contentColor =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.clickable { onToggleSelect(index) },
                    ) {
                        Text(
                            text = card.word.lemma,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomActionBar(
    phase: AppPhase,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onThinkHarder: () -> Unit,
    onSave: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = {
            context.startActivity(Intent(context, LogsActivity::class.java))
        }) {
            Text(stringResource(R.string.action_logs))
        }
        Spacer(modifier = Modifier.weight(1f))
        when (phase) {
            AppPhase.Done -> {
                TextButton(onClick = onThinkHarder) {
                    Text(stringResource(R.string.action_think_harder))
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (hasSelection) {
                    Button(onClick = onSave) {
                        Text(stringResource(R.string.action_save))
                    }
                } else {
                    OutlinedButton(onClick = onConfirm) {
                        Text(stringResource(R.string.action_got_it))
                    }
                }
            }

            AppPhase.ViewingLast -> {
                if (hasSelection) {
                    Button(onClick = onSave) {
                        Text(stringResource(R.string.action_save))
                    }
                } else {
                    OutlinedButton(onClick = onConfirm) {
                        Text(stringResource(R.string.action_got_it))
                    }
                }
            }

            AppPhase.OcrError, AppPhase.TranslateError -> {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }

            else -> {}
        }
    }
}

/**
 * The database rows behind one 「记一下」. A card's sentence is its whole block: that is the unit the
 * model translated, so it is also the unit the reader heard as "this sentence".
 */
private fun occurrencesFor(
    cards: List<VocabularyCard>,
    selectedIndices: Set<Int>,
    ocrResult: OcrResult?,
    translationResult: TranslationResult?,
): List<VocabularyOccurrence> {
    val blocks = ocrResult?.blocks ?: return emptyList()
    val results = translationResult?.results ?: return emptyList()
    return selectedIndices.mapNotNull { index ->
        val card = cards.getOrNull(index) ?: return@mapNotNull null
        val sentence = blocks.getOrNull(card.blockIndex)?.joinToString("") ?: return@mapNotNull null
        VocabularyOccurrence(
            lemma = card.word.lemma,
            reading = card.word.reading,
            origin = card.word.origin.orEmpty(),
            surface = card.word.word,
            definition = card.word.definition,
            sentence = sentence,
            translation = results.getOrNull(card.blockIndex)?.translation.orEmpty(),
        )
    }
}
