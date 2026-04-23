package me.sgly.translator

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ResultActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 配置 Activity 的 Window 层级透明度并允许外部事件穿透传递
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
                val text by TranslatorState.text.collectAsState()
                
                LaunchedEffect(isVisible) {
                    if (!isVisible) {
                        finish()
                    }
                }

                if (isVisible) {
                    val scrollState = rememberScrollState()
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    LaunchedEffect(text) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }

                    // 真正的 Jetpack Compose 原生 ModalBottomSheet
                    ModalBottomSheet(
                        onDismissRequest = { TranslatorState.dismiss() },
                        sheetState = sheetState,
                        scrimColor = Color.Transparent, // 无遮罩暗层，保证不遮挡漫画
                        dragHandle = { BottomSheetDefaults.DragHandle() }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                        ) {
                            Text(
                                text = text,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 500.dp)
                                    .verticalScroll(scrollState)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        // 去除 Activity 退出时的动画，使其看起来如悬浮窗般自然
        overridePendingTransition(0, 0)
    }
}
