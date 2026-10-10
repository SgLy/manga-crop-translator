package me.sgly.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FloatingService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    /** Held as a field so a pending press can be cancelled when the service goes away. */
    private val fabLongPress =
        Runnable {
            isFabLongPressed = true
            floatingView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onFabLongPressed()
        }
    private var isFabLongPressed = false

    private var session: CaptureSession? = null
    private var sessionJobs: List<Job> = emptyList()
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
        overlayScreenManager = OverlayScreenManager(this)
        settingsManager = SettingsManager(this)
        AppLog.forwardToLogcat = settingsManager.forwardLogsToLogcat
        openAIApiClient = OpenAIApiClient(settingsManager)

        // The FAB and the result sheet are recomputed whenever the phase or the sheet window changes: the
        // sheet is re-driven when its window disappears without the phase changing.
        serviceScope.launch {
            combine(TranslatorState.phase, TranslatorState.sheetOnScreen) { phase, _ -> phase }
                .collect { phase ->
                    updateFabVisibility()
                    updateSheetVisibility(phase)
                }
        }

        serviceScope.launch {
            TranslatorState.phase.collectLatest { phase ->
                when (phase) {
                    AppPhase.Capturing -> {
                        val capture = session
                        if (capture == null) {
                            TranslatorState.transition(AppPhase.None, TransitionReason.CaptureLost)
                            return@collectLatest
                        }
                        val frame = capture.captureFrame()
                        if (frame == null) {
                            TranslatorState.transition(AppPhase.Idle, TransitionReason.CaptureError)
                            return@collectLatest
                        }
                        AppLog.i("TranslatorState", "capturedBitmap: ${frame.width}×${frame.height}")
                        TranslatorState.capturedBitmap = frame
                        TranslatorState.transition(AppPhase.Cropping, TransitionReason.FrameCaptured)
                    }

                    AppPhase.Cropping -> {
                        val cropped = overlayScreenManager.showOverlay(TranslatorState.capturedBitmap!!)
                        if (cropped != null) {
                            TranslatorState.croppedBitmap = cropped
                            AppLog.i("TranslatorState", "croppedBitmap: ${cropped.width}×${cropped.height}")
                            overlayScreenManager.hideOverlay()
                            TranslatorState.transition(AppPhase.Recognizing, TransitionReason.CropSelected)
                        } else {
                            overlayScreenManager.hideOverlay()
                            TranslatorState.transition(AppPhase.Idle, TransitionReason.CropCancelled)
                        }
                    }

                    AppPhase.Recognizing -> {
                        val cropped = TranslatorState.croppedBitmap
                        if (cropped == null) {
                            AppLog.i("TranslatorState", "Recognizing with no cropped bitmap; back to Idle")
                            TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss)
                            return@collectLatest
                        }
                        try {
                            val result =
                                openAIApiClient.extractTextFromImage(
                                    cropped,
                                    TranslatorState.currentThinkingEffort(),
                                    onOutputDelta = { TranslatorState.onOutputDelta(it) },
                                    onReasoningDelta = { TranslatorState.onReasoningDelta(it) },
                                    onStats = {
                                        TranslatorState.onStats(it)
                                        TranslatorState.onVisionRequest()
                                    },
                                )
                            // The request may have finished after the run was cancelled.
                            currentCoroutineContext().ensureActive()
                            if (result.blocks.isEmpty()) {
                                TranslatorState.setErrorText(
                                    "OCR returned empty text. Please check if your VisualModel endpoint and model support image inputs.",
                                )
                                TranslatorState.transition(AppPhase.OcrError, TransitionReason.OcrFailed)
                            } else {
                                TranslatorState.setOcrResult(result)
                                TranslatorState.transition(AppPhase.Parsing, TransitionReason.OcrSuccess)
                            }
                        } catch (e: Exception) {
                            // A run the user dismissed is not a failure; leave the state alone.
                            currentCoroutineContext().ensureActive()
                            TranslatorState.setErrorText("Error: ${e.message}")
                            TranslatorState.transition(AppPhase.OcrError, TransitionReason.OcrFailed)
                        }
                    }

                    AppPhase.Parsing -> {
                        try {
                            val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
                            val ocrText = prettyJson.encodeToString(OcrResult.serializer(), TranslatorState.ocrResult.value!!)
                            val result =
                                openAIApiClient.translateText(
                                    ocrText,
                                    TranslatorState.currentThinkingEffort(),
                                    onOutputDelta = { TranslatorState.onOutputDelta(it) },
                                    onReasoningDelta = { TranslatorState.onReasoningDelta(it) },
                                    onStats = {
                                        TranslatorState.onStats(it)
                                        TranslatorState.onParseRequest()
                                    },
                                )
                            currentCoroutineContext().ensureActive()
                            recordRound()
                            TranslatorState.setTranslationResult(result)
                            TranslatorState.transition(AppPhase.Done, TransitionReason.TranslateSuccess)
                        } catch (e: Exception) {
                            // A run the user dismissed is not a failure; leave the state alone.
                            currentCoroutineContext().ensureActive()
                            TranslatorState.setErrorText("Error: ${e.message}")
                            TranslatorState.transition(AppPhase.TranslateError, TransitionReason.TranslateFailed)
                        }
                    }

                    else -> { /* None/Idle/ViewingLast/Done/OcrError/TranslateError: UI handles these */ }
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notification = createNotification()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE)) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            val projection =
                try {
                    resultData?.let {
                        getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it)
                    }
                } catch (e: Exception) {
                    AppLog.w("CaptureSession", "grant rejected: ${e.message}")
                    null
                }
            if (projection == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startSession(projection)
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

        val composeView =
            ComposeView(this).apply {
                setContent {
                    FabButton()
                }
                isClickable = false
                isFocusable = false
            }

        val frameLayout =
            FabContainer(this).apply {
                setOnClickListener {
                    TranslatorState.transition(AppPhase.Capturing, TransitionReason.FabClicked)
                }
                clipChildren = false
                clipToPadding = false
                setViewTreeLifecycleOwner(this@FloatingService)
                setViewTreeSavedStateRegistryOwner(this@FloatingService)
                addView(composeView, FrameLayout.LayoutParams(fabSize, fabSize, Gravity.CENTER))
            }

        val params =
            LayoutParams(
                containerSize,
                containerSize,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.START

        val metrics = resources.displayMetrics
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

        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        frameLayout.setOnTouchListener(
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

        floatingView = frameLayout
        windowManager.addView(floatingView, params)
        updateFabVisibility()
    }

    private fun onFabLongPressed() {
        if (TranslatorState.lastTranslation == null) {
            Toast.makeText(this, R.string.no_last_result, Toast.LENGTH_SHORT).show()
            return
        }
        TranslatorState.transition(AppPhase.ViewingLast, TransitionReason.FabLongPressed)
    }

    private var lastPhase: AppPhase? = null
    private var lastCapturedContentVisible = false

    private fun startSession(projection: MediaProjection) {
        endSession()
        val started =
            try {
                CaptureSession(this, projection)
            } catch (e: Exception) {
                AppLog.w("CaptureSession", "not started: ${e.message}")
                stopSelf()
                return
            }
        session = started
        sessionJobs =
            listOf(
                serviceScope.launch {
                    started.capturedContentVisible.collect { visible ->
                        if (visible && !lastCapturedContentVisible) TranslatorState.closeLogsIfOpen()
                        updateFabVisibility()
                        updateSheetVisibility(TranslatorState.phase.value)
                    }
                },
                serviceScope.launch {
                    started.leaseLost.collect { lost ->
                        if (lost) onLeaseLost()
                    }
                },
            )
        TranslatorState.transition(AppPhase.Idle, TransitionReason.CaptureGranted)
    }

    private fun endSession() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs = emptyList()
        session?.close()
        session = null
        lastCapturedContentVisible = false
        if (TranslatorState.phase.value != AppPhase.None) {
            TranslatorState.transition(AppPhase.None, TransitionReason.CaptureLost)
        }
        updateFabVisibility()
    }

    private fun onLeaseLost() {
        endSession()
        stopSelf()
    }

    private fun isCapturedContentVisible(): Boolean = session?.capturedContentVisible?.value ?: false

    /** The launch needs a visible window of ours, so the FAB window is flashed first. */
    private fun updateSheetVisibility(phase: AppPhase) {
        val visible = isCapturedContentVisible()
        val returning = visible && !lastCapturedContentVisible
        lastCapturedContentVisible = visible
        val enteredSheetPhase = phase != lastPhase
        lastPhase = phase
        if (!phase.showsSheet || TranslatorState.sheetOnScreen.value) return
        if (!enteredSheetPhase && !visible) return
        if (!::floatingView.isInitialized) return
        serviceScope.launch {
            // Delay past the system's transition to the shared app: launching during it makes
            // MIUI abort the move-to-front ("avoidMoveToFront by anim effect").
            if (returning) delay(500)
            val abandoned =
                !TranslatorState.phase.value.showsSheet ||
                    TranslatorState.sheetOnScreen.value ||
                    (returning && !isCapturedContentVisible())
            if (abandoned) return@launch
            floatingView.visibility = View.VISIBLE
            delay(120)
            showSheet(clearTask = returning)
            delay(400)
            updateFabVisibility()
        }
    }

    /** A stats failure must never surface, so it is swallowed. */
    private suspend fun recordRound() {
        val stats = TranslatorState.runStats.value ?: return
        val reason = TranslatorState.runReason ?: return
        try {
            val dao = StatsDatabase.get(this).statsDao()
            val now = System.currentTimeMillis()
            val translationId =
                TranslatorState.translationId
                    ?: dao.insertTranslation(Translation(createdAt = now)).also {
                        TranslatorState.translationId = it
                    }
            dao.insertRound(
                InferenceRound(
                    translationId = translationId,
                    createdAt = now,
                    reason = reason.name,
                    model = stats.model,
                    thinkingEffort = TranslatorState.currentThinkingEffort(),
                    visionRequests = TranslatorState.roundVisionRequests,
                    parseRequests = TranslatorState.roundParseRequests,
                    inputTokens = stats.usage.input,
                    cachedInputTokens = stats.usage.cached,
                    outputTokens = stats.usage.output,
                    reasoningTokens = stats.usage.reasoning,
                    totalTtftMillis = stats.ttfts.sum(),
                    elapsedMillis = stats.elapsedMillis,
                ),
            )
        } catch (e: Exception) {
            AppLog.w("Stats", "round not recorded: ${e.message}")
        }
    }

    /** With Android 14 app screen sharing, "on screen" means the shared app is in the foreground. */
    private fun updateFabVisibility() {
        if (!::floatingView.isInitialized) return
        val show = TranslatorState.phase.value == AppPhase.Idle && isCapturedContentVisible()
        floatingView.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    private fun showSheet(clearTask: Boolean = false) {
        val intent =
            Intent(this, SheetActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                // Re-launching a still-alive singleInstance task only "delivers to top" and never
                // comes to the foreground (observed on HyperOS). Clearing the task first forces a
                // fresh, visible launch instead of reusing the orphaned background instance.
                addFlags(if (clearTask) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        endSession()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        overlayScreenManager.release()
        if (::windowManager.isInitialized && ::floatingView.isInitialized) {
            floatingView.removeCallbacks(fabLongPress)
            windowManager.removeView(floatingView)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("floating_service", "Manga Translator Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val builder = Notification.Builder(this, "floating_service")
        return builder
            .setContentTitle("Manga Translator Active")
            .setContentText("Ready to translate manga panels.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
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
                    contentDescription = "Translate",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
