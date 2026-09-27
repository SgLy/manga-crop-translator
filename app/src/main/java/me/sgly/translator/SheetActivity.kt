package me.sgly.translator

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Hosts the bottom sheet: [ProgressSheet] while a request runs or has failed, [ResultSheet] after. */
class SheetActivity : ComponentActivity() {
    private var resumeCount by mutableIntStateOf(0)

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

                LaunchedEffect(phase) {
                    if (!phase.showsSheet && phase != AppPhase.ShowingLogs) {
                        finish()
                    }
                }

                LaunchedEffect(resumeCount) {
                    TranslatorState.closeLogsIfOpen()
                }

                val isSheetVisible = phase.showsSheet
                if (isSheetVisible) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        onDismissRequest = {
                            if (phase != AppPhase.ShowingLogs) {
                                TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss)
                            }
                        },
                        sheetState = sheetState,
                        scrimColor = Color.Transparent,
                        dragHandle = { BottomSheetDefaults.DragHandle() },
                    ) {
                        val isProgressPhase =
                            phase == AppPhase.Recognizing ||
                                phase == AppPhase.Parsing ||
                                phase == AppPhase.OcrError ||
                                phase == AppPhase.TranslateError
                        if (isProgressPhase) {
                            ProgressSheet(phase)
                        } else {
                            ResultSheet(phase)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TranslatorState.setSheetShown(true)
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    override fun onDestroy() {
        TranslatorState.setSheetShown(false)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
