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
                val isVisible by TranslatorState.isVisible.collectAsState()
                val ocrStatus by TranslatorState.ocrStatus.collectAsState()
                val llmStatus by TranslatorState.llmStatus.collectAsState()
                val ocrResult by TranslatorState.ocrResult.collectAsState()
                val translationResult by TranslatorState.translationResult.collectAsState()

                // Word card selection state
                val selectedIndices = remember { mutableStateSetOf<Int>() }

                LaunchedEffect(isVisible) {
                    if (!isVisible) {
                        finish()
                    }
                }

                if (isVisible) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    ModalBottomSheet(
                        onDismissRequest = { TranslatorState.dismiss() },
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
                                // Show step rows only while in progress, hide when both done
                                if (ocrStatus != StepStatus.DONE || llmStatus != StepStatus.DONE) {
                                    // Step 1: OCR status row
                                    StepRow(
                                        label = "识别图片",
                                        status = ocrStatus
                                    )

                                    // Step 2: LLM status row (only visible after OCR starts)
                                    if (llmStatus != StepStatus.IDLE) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        StepRow(
                                            label = "解析文本",
                                            status = llmStatus
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

                            // Fixed bottom bar
                            if (translationResult != null && ocrResult != null) {
                                BottomActionBar(
                                    hasSelection = selectedIndices.isNotEmpty(),
                                    onLogClick = { /* TODO: 日志 */ },
                                    onRetryClick = { /* TODO: 再想想 */ },
                                    onConfirmClick = { /* TODO: 记一下/知道了 */ }
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

@Composable
fun StepRow(label: String, status: StepStatus) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        when (status) {
            StepStatus.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StepStatus.DONE -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
            StepStatus.ERROR -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
            StepStatus.IDLE -> {
                Spacer(modifier = Modifier.size(16.dp))
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = label,
            fontSize = 13.sp,
            color = when (status) {
                StepStatus.LOADING -> MaterialTheme.colorScheme.onSurface
                StepStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                StepStatus.ERROR -> MaterialTheme.colorScheme.error
                StepStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            fontWeight = when (status) {
                StepStatus.LOADING -> FontWeight.Medium
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
    hasSelection: Boolean,
    onLogClick: () -> Unit,
    onRetryClick: () -> Unit,
    onConfirmClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: text button "日志"
        TextButton(onClick = onLogClick) {
            Text("日志")
        }
        // Right side: push to the right
        Spacer(modifier = Modifier.weight(1f))
        // Text button "再想想" (no border)
        TextButton(onClick = onRetryClick) {
            Text("再想想")
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Conditional right button
        if (hasSelection) {
            Button(onClick = onConfirmClick) {
                Text("记一下")
            }
        } else {
            OutlinedButton(onClick = onConfirmClick) {
                Text("知道了")
            }
        }
    }
}
