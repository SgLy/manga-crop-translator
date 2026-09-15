package me.sgly.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

class OverlayScreenManager(
    private val context: Context,
) : LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    suspend fun showOverlay(bitmap: Bitmap): Bitmap? =
        suspendCancellableCoroutine { cont ->
            if (overlayView != null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation { hideOverlay() }

            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            overlayView =
                ComposeView(context).apply {
                    setViewTreeLifecycleOwner(this@OverlayScreenManager)
                    setViewTreeSavedStateRegistryOwner(this@OverlayScreenManager)
                    setViewTreeViewModelStoreOwner(this@OverlayScreenManager)

                    setContent {
                        OverlayScreenContent(bitmap = bitmap) { result ->
                            // 不在这里 hideOverlay：让调用方在 startActivity 之后再 hide，
                            // 确保 startActivity 时 app 还有可见窗口（Android 10+ 限制）
                            if (!cont.isCompleted) cont.resume(result)
                        }
                    }
                }

            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.O
                    ) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            params.gravity = Gravity.TOP or Gravity.START

            windowManager.addView(overlayView, params)
        }

    internal fun hideOverlay() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        overlayView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        overlayView = null
    }

    fun release() {
        hideOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

@Composable
fun OverlayScreenContent(
    bitmap: Bitmap,
    onCropAreaSelected: (Bitmap?) -> Unit,
) {
    var startPoint by remember { mutableStateOf<Offset?>(null) }
    var currentPoint by remember { mutableStateOf<Offset?>(null) }
    val minCropSize = 80f // 最小有效框选尺寸

    Box(modifier = Modifier.fillMaxSize()) {
        // 全屏显示图片，作为底层
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Frozen Screen",
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )

        // 绘制图层：包含暗色半透明遮罩与动态抠出的亮色框选区
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.99f } // 强制离屏渲染以支持 BlendMode.Clear 擦除效果
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                startPoint = offset
                                currentPoint = offset
                            },
                            onDrag = { change, _ ->
                                currentPoint = change.position
                            },
                            onDragEnd = {
                                val s = startPoint
                                val e = currentPoint
                                if (s != null && e != null) {
                                    val left = minOf(s.x, e.x)
                                    val top = minOf(s.y, e.y)
                                    val right = maxOf(s.x, e.x)
                                    val bottom = maxOf(s.y, e.y)
                                    val width = right - left
                                    val height = bottom - top

                                    val isValid = width >= minCropSize && height >= minCropSize

                                    if (isValid) {
                                        val cLeft = left.toInt().coerceIn(0, bitmap.width - 1)
                                        val cTop = top.toInt().coerceIn(0, bitmap.height - 1)
                                        var cWidth = width.toInt()
                                        var cHeight = height.toInt()

                                        if (cLeft + cWidth > bitmap.width) cWidth = bitmap.width - cLeft
                                        if (cTop + cHeight > bitmap.height) cHeight = bitmap.height - cTop

                                        if (cWidth > 0 && cHeight > 0) {
                                            val cropped = Bitmap.createBitmap(bitmap, cLeft, cTop, cWidth, cHeight)
                                            onCropAreaSelected(cropped) // 返回截图并退出
                                        }
                                    }
                                    // 如果小于阈值，什么都不做，仅仅清空起始点，让用户能重画
                                }
                                startPoint = null
                                currentPoint = null
                            },
                            onDragCancel = {
                                startPoint = null
                                currentPoint = null
                            },
                        )
                    }.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // 轻触点击直接退出
                                onCropAreaSelected(null)
                            },
                        )
                    },
        ) {
            // 画遮罩
            drawRect(color = Color.Black.copy(alpha = 0.6f), size = size)

            val s = startPoint
            val e = currentPoint
            if (s != null && e != null) {
                val left = minOf(s.x, e.x)
                val top = minOf(s.y, e.y)
                val right = maxOf(s.x, e.x)
                val bottom = maxOf(s.y, e.y)
                val rect = Rect(left, top, right, bottom)
                val isValid = rect.width >= minCropSize && rect.height >= minCropSize

                if (isValid) {
                    // 合法时：擦除框选区域背景，让底层的原图透出来
                    drawRect(
                        color = Color.Transparent,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        blendMode = BlendMode.Clear,
                    )
                    // 画明显白边框
                    drawRect(
                        color = Color.White,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = 6f),
                    )
                } else {
                    // 不合法时：不抠图（保持和背景一样暗），仅画一个半透明的细白线作为范围提示 (dimmed)
                    drawRect(
                        color = Color.White.copy(alpha = 0.3f),
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = 4f),
                    )
                }
            }
        }

        if (startPoint == null) {
            Text(
                stringResource(R.string.overlay_hint),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
