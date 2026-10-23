package me.sgly.translator.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
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
import me.sgly.translator.R
import kotlin.coroutines.resume

internal class CropOverlay(
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

    internal suspend fun showOverlay(bitmap: Bitmap): Bitmap? =
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
                    setViewTreeLifecycleOwner(this@CropOverlay)
                    setViewTreeSavedStateRegistryOwner(this@CropOverlay)
                    setViewTreeViewModelStoreOwner(this@CropOverlay)

                    setContent {
                        OverlayScreenContent(bitmap = bitmap) { result ->
                            // Not here: the caller hides the overlay after startActivity, so the app still
                            // has a visible window then (Android 10+ requires one for the launch to succeed).
                            if (!cont.isCompleted) cont.resume(result)
                        }
                    }
                }

            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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

    internal fun release() {
        hideOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

@Composable
private fun OverlayScreenContent(
    bitmap: Bitmap,
    onCropAreaSelected: (Bitmap?) -> Unit,
) {
    var startPoint by remember { mutableStateOf<Offset?>(null) }
    var currentPoint by remember { mutableStateOf<Offset?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_frozen_screen),
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.99f } // Forces offscreen rendering, which BlendMode.Clear needs to erase.
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
                                    val bounds = selectionBounds(s, e)
                                    if (bounds.isCropSelectionValid()) {
                                        bitmap.cropTo(bounds)?.let(onCropAreaSelected)
                                    }
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
                                onCropAreaSelected(null)
                            },
                        )
                    },
        ) {
            drawRect(color = Color.Black.copy(alpha = 0.6f), size = size)

            val s = startPoint
            val e = currentPoint
            if (s != null && e != null) {
                val bounds = selectionBounds(s, e)
                drawSelection(bounds, bounds.isCropSelectionValid())
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

private fun DrawScope.drawSelection(
    bounds: Rect,
    isValid: Boolean,
) {
    if (isValid) {
        drawRect(
            color = Color.Transparent,
            topLeft = bounds.topLeft,
            size = bounds.size,
            blendMode = BlendMode.Clear,
        )
        drawRect(
            color = Color.White,
            topLeft = bounds.topLeft,
            size = bounds.size,
            style = Stroke(width = 6f),
        )
    } else {
        drawRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = bounds.topLeft,
            size = bounds.size,
            style = Stroke(width = 4f),
        )
    }
}
