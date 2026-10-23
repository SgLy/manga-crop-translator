package me.sgly.translator.runtime

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.sgly.translator.R
import me.sgly.translator.settings.SettingsManager
import me.sgly.translator.state.AppPhase
import me.sgly.translator.state.TransitionReason
import me.sgly.translator.state.TranslatorState

internal class FloatingButton(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateRegistryOwner: SavedStateRegistryOwner,
    private val onLongPress: () -> Unit,
) {
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var floatingView: View

    /** Held as a field so a pending press can be cancelled when the service goes away. */
    private val fabLongPress =
        Runnable {
            isFabLongPressed = true
            floatingView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
    private var isFabLongPressed = false

    fun setVisible(visible: Boolean) {
        floatingView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun show() {
        val density = context.resources.displayMetrics.density
        val fabSize = (56 * density).toInt()
        val shadowMargin = (16 * density).toInt()
        val containerSize = fabSize + shadowMargin * 2

        val composeView =
            ComposeView(context).apply {
                setContent {
                    FabButton()
                }
                isClickable = false
                isFocusable = false
            }

        val params = buildLayoutParams(containerSize, density)

        val frameLayout =
            FabContainer(context).apply {
                setOnClickListener {
                    TranslatorState.transition(AppPhase.Capturing, TransitionReason.FabClicked)
                }
                clipChildren = false
                clipToPadding = false
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
                addView(composeView, FrameLayout.LayoutParams(fabSize, fabSize, Gravity.CENTER))
            }

        attachTouchHandling(frameLayout, params)

        floatingView = frameLayout
        windowManager.addView(floatingView, params)
    }

    private fun buildLayoutParams(
        containerSize: Int,
        density: Float,
    ): LayoutParams {
        val params =
            LayoutParams(
                containerSize,
                containerSize,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START

        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - containerSize).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - containerSize).coerceAtLeast(0)
        val savedX = settingsManager.fabX
        val savedY = settingsManager.fabY
        if (savedX >= 0 && savedY >= 0) {
            params.x = savedX.coerceIn(0, maxX)
            params.y = savedY.coerceIn(0, maxY)
        } else {
            // Default: bottom-right, just off the edges — comfortable for the right thumb.
            val rightMargin = 0
            val bottomMargin = (100 * density).toInt()
            params.x = (metrics.widthPixels - containerSize - rightMargin).coerceIn(0, maxX)
            params.y = (metrics.heightPixels - containerSize - bottomMargin).coerceIn(0, maxY)
        }
        return params
    }

    private fun attachTouchHandling(
        container: FabContainer,
        params: LayoutParams,
    ) {
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        container.setOnTouchListener(
            object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(
                    v: View,
                    event: MotionEvent,
                ): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            isFabLongPressed = false
                            v.postDelayed(fabLongPress, longPressTimeout)
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                                if (!isDragging) v.removeCallbacks(fabLongPress)
                                isDragging = true
                            }
                            if (isDragging) {
                                params.x = initialX + dx.toInt()
                                params.y = initialY + dy.toInt()
                                windowManager.updateViewLayout(floatingView, params)
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.removeCallbacks(fabLongPress)
                            if (isDragging) {
                                settingsManager.fabX = params.x
                                settingsManager.fabY = params.y
                            } else if (!isFabLongPressed) {
                                v.performClick()
                            }
                            return true
                        }
                    }
                    return false
                }
            },
        )
    }

    fun remove() {
        if (!::floatingView.isInitialized) return
        floatingView.removeCallbacks(fabLongPress)
        windowManager.removeView(floatingView)
    }
}

private class FabContainer(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    // Views that consume touch input must expose performClick for accessibility services.
    override fun performClick(): Boolean = super.performClick()
}

@Composable
private fun FabButton() {
    MaterialTheme {
        Surface(
            shape = CircleShape,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(56.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.cd_translate),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
