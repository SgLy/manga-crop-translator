package me.sgly.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var overlayScreenManager: OverlayScreenManager
    private lateinit var resultOverlayManager: ResultOverlayManager
    private lateinit var openAIApiClient: OpenAIApiClient

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        screenCaptureManager = ScreenCaptureManager(this)
        overlayScreenManager = OverlayScreenManager(this)
        resultOverlayManager = ResultOverlayManager(this)
        openAIApiClient = OpenAIApiClient(SettingsManager(this))

        serviceScope.launch {
            screenCaptureManager.screenCaptureFlow.collectLatest { bitmap ->
                floatingView.visibility = View.VISIBLE // 恢复悬浮按钮
                overlayScreenManager.showOverlay(bitmap) { croppedBitmap ->
                    if (croppedBitmap != null) {
                        Toast.makeText(this@FloatingService, "Fetching VLM OCR...", Toast.LENGTH_SHORT).show()
                        // Launch API calls sequentially in a background coroutine
                        serviceScope.launch {
                            resultOverlayManager.appendText("\n--- New Translate Request ---\n[System]: Fetching VLM OCR...\n[OCR]: ", clearFirst = false)
                            try {
                                val ocrResult = openAIApiClient.extractTextFromImage(croppedBitmap) { chunk ->
                                    withContext(Dispatchers.Main) {
                                        resultOverlayManager.appendText(chunk, clearFirst = false)
                                    }
                                }
                                val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
                                val ocrText = prettyJson.encodeToString(me.sgly.translator.OcrResult.serializer(), ocrResult)
                                withContext(Dispatchers.Main) {
                                    resultOverlayManager.appendText("\n\n[OCR Result]:\n" + ocrText)
                                }
                                
                                if (ocrText.isBlank()) {
                                    withContext(Dispatchers.Main) {
                                        resultOverlayManager.appendText("\n\n[Error]: OCR returned empty text. Please check if your VLM endpoint and model support image inputs.")
                                    }
                                    return@launch
                                }

                                withContext(Dispatchers.Main) {
                                    resultOverlayManager.appendText("\n\n[System]: OCR Success! Translating...\n[Translate]: ")
                                }

                                val translateResult = openAIApiClient.translateText(ocrText) { chunk ->
                                    withContext(Dispatchers.Main) {
                                        resultOverlayManager.appendText(chunk, clearFirst = false)
                                    }
                                }
                                val translatedText = prettyJson.encodeToString(me.sgly.translator.TranslationResult.serializer(), translateResult)
                                withContext(Dispatchers.Main) {
                                    resultOverlayManager.appendText("\n\n[Translate Result]:\n" + translatedText)
                                }
                                
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    resultOverlayManager.appendText("\n\n[Error]: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }

        if (intent != null && intent.hasExtra("EXTRA_RESULT_CODE")) {
            val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", 0)
            val resultData = intent.getParcelableExtra<Intent>("EXTRA_RESULT_DATA")

            if (resultData != null) {
                val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
                val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                projection?.let {
                    screenCaptureManager.setMediaProjection(it)
                }
            }
        }
        
        if (!::windowManager.isInitialized) {
            showFloatingWindow()
        }
        
        return START_NOT_STICKY
    }

    private fun showFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Setup layout params
        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) LayoutParams.TYPE_APPLICATION_OVERLAY else LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 200

        // Create a simple UI container
        val frameLayout = FrameLayout(this)
        
        val button = Button(this).apply {
            text = "译"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            
            // On click
            setOnClickListener {
                serviceScope.launch {
                    resultOverlayManager.appendText("[System]: Screen frozen, ready to crop!", clearFirst = true)
                }
                floatingView.visibility = View.INVISIBLE // 隐藏悬浮窗以免挡住截图
                screenCaptureManager.captureScreen()
            }
        }
        
        // Add drag behavior directly to the button
        button.setOnTouchListener(object : View.OnTouchListener {
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
                        return true // Consume down so we get move events
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
                            v.performClick() // Trigger the click listener if it was just a tap
                        }
                        return true
                    }
                }
                return false
            }
        })

        frameLayout.addView(button)
        floatingView = frameLayout
        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        screenCaptureManager.release()
        overlayScreenManager.release()
        resultOverlayManager.release()
        if (::windowManager.isInitialized && ::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_service",
                "Manga Translator Service",
                NotificationManager.IMPORTANCE_LOW
            )
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
        
        // Use a default icon for now, needs to be replaced with a real icon
        return builder.setContentTitle("Manga Translator Active")
            .setContentText("Ready to translate manga panels.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
