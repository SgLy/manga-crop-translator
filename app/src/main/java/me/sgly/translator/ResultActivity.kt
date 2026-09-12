package me.sgly.translator

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ResultActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                val phase by TranslatorState.phase.collectAsState()
                val ocrResult by TranslatorState.ocrResult.collectAsState()
                val translationResult by TranslatorState.translationResult.collectAsState()
                val errorText by TranslatorState.errorText.collectAsState()

                val ocrStatus = when (phase) {
                    AppPhase.Recognizing -> StepUiStatus.Loading
                    AppPhase.OcrError -> StepUiStatus.Error
                    AppPhase.Parsing, AppPhase.Done, AppPhase.LlmError -> StepUiStatus.Done
                    else -> StepUiStatus.Idle
                }
                val llmStatus = when (phase) {
                    AppPhase.Parsing -> StepUiStatus.Loading
                    AppPhase.Done -> StepUiStatus.Done
                    AppPhase.LlmError -> StepUiStatus.Error
                    else -> StepUiStatus.Idle
                }

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
                        dragHandle = { BottomSheetDefaults.DragHandle() }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .padding(start = 16.dp, end = 16.dp, bottom = 0.dp)
                        ) {
                            // Scrollable content area
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp, max = 400.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // Show step rows while not yet Done
                                if (phase != AppPhase.Done) {
                                    StepRow(label = stringResource(R.string.step_recognize), status = ocrStatus)

                                    // LLM row visible after OCR phase
                                    if (phase != AppPhase.Recognizing && phase != AppPhase.OcrError) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        StepRow(label = stringResource(R.string.step_parse), status = llmStatus)
                                    }

                                    // Error detail
                                    if ((phase == AppPhase.OcrError || phase == AppPhase.LlmError) && errorText.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = errorText,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                // Result area: show structured blocks when both results are available
                                if (translationResult != null && ocrResult != null) {
                                    ResultBlocks(
                                        ocrResult = ocrResult!!,
                                        translationResult = translationResult!!,
                                        selectedIndices = selectedIndices,
                                        onToggleSelect = { index ->
                                            if (selectedIndices.contains(index)) {
                                                selectedIndices.remove(index)
                                            } else {
                                                selectedIndices.add(index)
                                            }
                                        }
                                    )
                                }
                            }

                            // Fixed bottom bar: show in Done/OcrError/LlmError phases
                            if (phase == AppPhase.Done || phase == AppPhase.OcrError || phase == AppPhase.LlmError) {
                                BottomActionBar(
                                    phase = phase,
                                    hasSelection = selectedIndices.isNotEmpty(),
                                    onDismiss = { TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss) },
                                    onRetry = {
                                        val nextPhase = if (phase == AppPhase.LlmError) AppPhase.Parsing else AppPhase.Recognizing
                                        TranslatorState.transition(nextPhase, TransitionReason.Retry)
                                    },
                                    onThinkHarder = { TranslatorState.transition(AppPhase.Recognizing, TransitionReason.ThinkHarder) },
                                    onConfirm = { TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss) }
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

enum class StepUiStatus { Idle, Loading, Done, Error }

@Composable
fun StepRow(label: String, status: StepUiStatus) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        when (status) {
            StepUiStatus.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StepUiStatus.Done -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            StepUiStatus.Error -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
            StepUiStatus.Idle -> {
                Spacer(modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = label,
            fontSize = 13.sp,
            color = when (status) {
                StepUiStatus.Loading -> MaterialTheme.colorScheme.onSurface
                StepUiStatus.Done -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                StepUiStatus.Error -> MaterialTheme.colorScheme.error
                StepUiStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            fontWeight = when (status) {
                StepUiStatus.Loading -> FontWeight.Medium
                else -> FontWeight.Normal
            }
        )
    }
}

@Composable
fun ResultBlocks(
    ocrResult: OcrResult,
    translationResult: TranslationResult,
    selectedIndices: Set<Int>,
    onToggleSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
    ) {
        ocrResult.blocks.forEachIndexed { index, block ->
            // Japanese original text: each string in the block as a separate segment with dividers
            FlowRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                block.forEachIndexed { segIndex, segment ->
                    if (segIndex > 0) {
                        VerticalDivider(
                            modifier = Modifier
                                .height(16.dp)
                                .padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 1.dp
                        )
                    }
                    Text(
                        text = segment,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Corresponding Chinese translation
            val translation = translationResult.results.getOrNull(index)?.translation ?: ""
            Text(
                text = translation,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Divider between blocks (not after the last one)
            if (index < ocrResult.blocks.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Vocabulary cards: collect all words, filter null difficulty, sort by difficulty descending
        val allWords = translationResult.results
            .flatMap { it.columns.flatten() }
            .filter { it.difficulty != null }
            .sortedByDescending { it.difficulty }

        if (allWords.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allWords.forEachIndexed { index, word ->
                    val isSelected = selectedIndices.contains(index)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable { onToggleSelect(index) }
                    ) {
                        Text(
                            text = word.lemma,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
    onConfirm: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = {
            android.widget.Toast.makeText(context, context.getString(R.string.not_implemented), android.widget.Toast.LENGTH_SHORT).show()
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
                    Button(onClick = {
                        android.widget.Toast.makeText(context, context.getString(R.string.not_implemented), android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                } else {
                    OutlinedButton(onClick = onConfirm) {
                        Text(stringResource(R.string.action_got_it))
                    }
                }
            }
            AppPhase.OcrError, AppPhase.LlmError -> {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            else -> {}
        }
    }
}
