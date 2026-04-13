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
    private var resultViewLayout: View? = null
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var overlayScreenManager: OverlayScreenManager
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
        openAIApiClient = OpenAIApiClient(SettingsManager(this))

        serviceScope.launch {
            screenCaptureManager.screenCaptureFlow.collectLatest { bitmap ->
                floatingView.visibility = View.VISIBLE // 恢复悬浮按钮
                overlayScreenManager.showOverlay(bitmap) { croppedBitmap ->
                    if (croppedBitmap != null) {
                        Toast.makeText(this@FloatingService, "Fetching VLM OCR...", Toast.LENGTH_SHORT).show()
                        // Launch API calls sequentially in a background coroutine
                        serviceScope.launch {
                            showResultWindow("\n--- New Translate Request ---\n[System]: Fetching VLM OCR...\n[OCR]: ", isAppend = true)
                            try {
                                var ocrText = ""
                                openAIApiClient.extractTextFromImageStream(croppedBitmap).collect { chunk ->
                                    ocrText += chunk
                                    withContext(Dispatchers.Main) {
                                        appendResultText(chunk)
                                    }
                                }
                                
                                if (ocrText.isBlank()) {
                                    withContext(Dispatchers.Main) {
                                        appendResultText("\n\n[Error]: OCR returned empty text. Please check if your VLM endpoint and model support image inputs.")
                                    }
                                    return@launch
                                }

                                withContext(Dispatchers.Main) {
                                    appendResultText("\n\n[System]: OCR Success! Translating...\n[Translate]: ")
                                }

                                var translatedText = ""
                                openAIApiClient.translateTextStream(ocrText).collect { chunk ->
                                    translatedText += chunk
                                    withContext(Dispatchers.Main) {
                                        appendResultText(chunk)
                                    }
                                }
                                
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    appendResultText("\n\n[Error]: ${e.message}")
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
                    showResultWindow("[System]: Screen frozen, ready to crop!", isAppend = false)
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

    private var resultTextView: TextView? = null
    private var resultScrollView: ScrollView? = null
    private val historyText = StringBuilder()

    private suspend fun showResultWindow(text: String, isAppend: Boolean = false) = withContext(Dispatchers.Main) {
        if (resultViewLayout == null) {
            val params = LayoutParams(
                LayoutParams.MATCH_PARENT,
                800, // Fixed height for the bottom sheet
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) LayoutParams.TYPE_APPLICATION_OVERLAY else LayoutParams.TYPE_PHONE,
                LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = 0

            val layout = FrameLayout(this@FloatingService)
            // Use dark solid color for clear text reading
            layout.setBackgroundColor(Color.parseColor("#EE222222"))

            val scrollView = ScrollView(this@FloatingService)
            val scrollParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scrollParams.setMargins(32, 80, 32, 32)
            scrollView.layoutParams = scrollParams

            val textView = TextView(this@FloatingService)
            textView.setTextColor(Color.WHITE)
            textView.textSize = 16f
            resultTextView = textView
            resultScrollView = scrollView

            scrollView.addView(textView)
            layout.addView(scrollView)

            val closeButton = Button(this@FloatingService)
            closeButton.text = "✖ Dismiss"
            closeButton.textSize = 14f
            closeButton.setBackgroundColor(Color.TRANSPARENT)
            closeButton.setTextColor(Color.LTGRAY)
            val btnParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            btnParams.gravity = Gravity.TOP or Gravity.END
            closeButton.layoutParams = btnParams
            closeButton.setOnClickListener {
                hideResultWindow()
            }
            layout.addView(closeButton)

            resultViewLayout = layout
            windowManager.addView(layout, params)
        }

        if (!isAppend) {
            historyText.clear()
        }
        historyText.append(text)
        resultTextView?.text = historyText.toString()

        scrollTextToBottom()
    }

    private suspend fun appendResultText(text: String) = showResultWindow(text, true)

    private fun scrollTextToBottom() {
        resultScrollView?.post {
            resultScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun hideResultWindow() {
        resultViewLayout?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
            resultViewLayout = null
            resultTextView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenCaptureManager.release()
        overlayScreenManager.release()
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
