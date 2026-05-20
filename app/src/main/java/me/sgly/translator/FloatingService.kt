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
import kotlinx.coroutines.launch

class FloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var overlayScreenManager: OverlayScreenManager
    private lateinit var resultOverlayManager: ResultOverlayManager
    private lateinit var openAIApiClient: OpenAIApiClient

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
        resultOverlayManager = ResultOverlayManager(this)
        openAIApiClient = OpenAIApiClient(SettingsManager(this))

        serviceScope.launch {
            var wasVisible = false
            TranslatorState.isVisible.collect { visible ->
                if (wasVisible && !visible && ::floatingView.isInitialized) {
                    floatingView.visibility = View.VISIBLE
                }
                wasVisible = visible
            }
        }

        serviceScope.launch {
            screenCaptureManager.screenCaptureFlow.collectLatest { bitmap ->
                var didStartTranslation = false
                overlayScreenManager.showOverlay(
                    bitmap,
                    { croppedBitmap ->
                        if (croppedBitmap != null) {
                            didStartTranslation = true
                            serviceScope.launch {
                                resultOverlayManager.startOcr()
                                try {
                                    val ocrResult = openAIApiClient.extractTextFromImage(croppedBitmap) { }
                                    if (ocrResult.blocks.isEmpty()) {
                                        resultOverlayManager.ocrError("OCR returned empty text. Please check if your VLM endpoint and model support image inputs.")
                                        return@launch
                                    }
                                    resultOverlayManager.ocrDone(ocrResult)
                                    resultOverlayManager.startLlm()
                                    val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
                                    val ocrText = prettyJson.encodeToString(OcrResult.serializer(), ocrResult)
                                    val translateResult = openAIApiClient.translateText(ocrText) { }
                                    resultOverlayManager.llmDone(translateResult)
                                } catch (e: Exception) {
                                    val currentOcrStatus = TranslatorState.ocrStatus.value
                                    if (currentOcrStatus == StepStatus.LOADING) {
                                        resultOverlayManager.ocrError("Error: ${e.message}")
                                    } else {
                                        resultOverlayManager.llmError("Error: ${e.message}")
                                    }
                                }
                            }
                        }
                    },
                    {
                        if (!didStartTranslation) {
                            floatingView.visibility = View.VISIBLE
                        }
                    }
                )
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
        params.x = 0
        params.y = 200

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
                            floatingView.visibility = View.INVISIBLE
                            screenCaptureManager.captureScreen()
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
        resultOverlayManager.release()
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
