package me.sgly.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var overlayScreenManager: OverlayScreenManager
    private lateinit var openAIApiClient: OpenAIApiClient
    private lateinit var settingsManager: SettingsManager

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        screenCaptureManager = ScreenCaptureManager(this)
        overlayScreenManager = OverlayScreenManager(this)
        settingsManager = SettingsManager(this)
        AppLog.forwardToLogcat = settingsManager.forwardLogsToLogcat
        openAIApiClient = OpenAIApiClient(settingsManager)

        // FAB visibility: visible only when Idle
        serviceScope.launch {
            TranslatorState.phase.collect { phase ->
                if (::floatingView.isInitialized) {
                    floatingView.visibility = if (phase == AppPhase.Idle) View.VISIBLE else View.INVISIBLE
                }
            }
        }

        // Orchestration: drive the pipeline based on current phase
        serviceScope.launch {
            TranslatorState.phase.collectLatest { phase ->
                when (phase) {
                    AppPhase.Capturing -> {
                        if (!screenCaptureManager.captureScreen()) {
                            TranslatorState.transition(AppPhase.Idle, TransitionReason.CaptureError)
                            return@collectLatest
                        }
                        val bitmap = screenCaptureManager.screenCaptureFlow.first()
                        AppLog.i("TranslatorState", "capturedBitmap: ${bitmap.width}×${bitmap.height}")
                        TranslatorState.capturedBitmap = bitmap
                        TranslatorState.transition(AppPhase.Cropping, TransitionReason.FrameCaptured)
                    }
                    AppPhase.Cropping -> {
                        val cropped = overlayScreenManager.showOverlay(TranslatorState.capturedBitmap!!)
                        if (cropped != null) {
                            TranslatorState.croppedBitmap = cropped
                            AppLog.i("TranslatorState", "croppedBitmap: ${cropped.width}×${cropped.height}")
                            startActivity(Intent(this@FloatingService, ResultActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            })
                            overlayScreenManager.hideOverlay()
                            TranslatorState.transition(AppPhase.Recognizing, TransitionReason.CropSelected)
                        } else {
                            overlayScreenManager.hideOverlay()
                            TranslatorState.transition(AppPhase.Idle, TransitionReason.CropCancelled)
                        }
                    }
                    AppPhase.Recognizing -> {
                        try {
                            val result = openAIApiClient.extractTextFromImage(TranslatorState.croppedBitmap!!, TranslatorState.currentThinkingEffort()) { }
                            if (result.blocks.isEmpty()) {
                                TranslatorState.setErrorText("OCR returned empty text. Please check if your VisualModel endpoint and model support image inputs.")
                                TranslatorState.transition(AppPhase.OcrError, TransitionReason.OcrFailed)
                            } else {
                                TranslatorState.setOcrResult(result)
                                TranslatorState.transition(AppPhase.Parsing, TransitionReason.OcrSuccess)
                            }
                        } catch (e: Exception) {
                            TranslatorState.setErrorText("Error: ${e.message}")
                            TranslatorState.transition(AppPhase.OcrError, TransitionReason.OcrFailed)
                        }
                    }
                    AppPhase.Parsing -> {
                        try {
                            val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
                            val ocrText = prettyJson.encodeToString(OcrResult.serializer(), TranslatorState.ocrResult.value!!)
                            val result = openAIApiClient.translateText(ocrText, TranslatorState.currentThinkingEffort()) { }
                            TranslatorState.setTranslationResult(result)
                            TranslatorState.transition(AppPhase.Done, TransitionReason.TranslateSuccess)
                        } catch (e: Exception) {
                            TranslatorState.setErrorText("Error: ${e.message}")
                            TranslatorState.transition(AppPhase.TranslateError, TransitionReason.TranslateFailed)
                        }
                    }
                    else -> { /* Idle/Done/OcrError/TranslateError: UI handles these */ }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        if (intent != null && intent.hasExtra("EXTRA_RESULT_CODE")) {
            val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", 0)
            val resultData = intent.getParcelableExtra<Intent>("EXTRA_RESULT_DATA")
            if (resultData != null) {
                val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                projection?.let { screenCaptureManager.setMediaProjection(it) }
            }
        }

        if (!::windowManager.isInitialized) {
            showFloatingWindow()
        }

        return START_NOT_STICKY
    }

    private fun showFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val fabSize = (56 * density).toInt()
        val shadowMargin = (16 * density).toInt()
        val containerSize = fabSize + shadowMargin * 2

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = ComposeView(this).apply {
            setContent {
                FabButton()
            }
            isClickable = false
            isFocusable = false
        }

        val frameLayout = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                return true
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
            addView(composeView, FrameLayout.LayoutParams(fabSize, fabSize, Gravity.CENTER))
        }

        val params = LayoutParams(
            containerSize,
            containerSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) LayoutParams.TYPE_APPLICATION_OVERLAY else LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - containerSize).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - containerSize).coerceAtLeast(0)
        val savedX = settingsManager.fabX
        val savedY = settingsManager.fabY
        if (savedX >= 0 && savedY >= 0) {
            // Restore the last position the user dragged the button to.
            params.x = savedX.coerceIn(0, maxX)
            params.y = savedY.coerceIn(0, maxY)
        } else {
            // Default: bottom-right, just off the edges — comfortable for the right thumb.
            val rightMargin = 0
            val bottomMargin = (100 * density).toInt()
            params.x = (metrics.widthPixels - containerSize - rightMargin).coerceIn(0, maxX)
            params.y = (metrics.heightPixels - containerSize - bottomMargin).coerceIn(0, maxY)
        }

        frameLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(floatingView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            TranslatorState.transition(AppPhase.Capturing, TransitionReason.FabClicked)
                        } else {
                            // Persist the dragged position so it survives restarts.
                            settingsManager.fabX = params.x
                            settingsManager.fabY = params.y
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingView = frameLayout
        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        screenCaptureManager.release()
        overlayScreenManager.release()
        if (::windowManager.isInitialized && ::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("floating_service", "Manga Translator Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "floating_service")
        } else {
            Notification.Builder(this)
        }
        return builder.setContentTitle("Manga Translator Active")
            .setContentText("Ready to translate manga panels.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}

@Composable
private fun FabButton() {
    MaterialTheme {
        Surface(
            shape = CircleShape,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(56.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Translate",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
