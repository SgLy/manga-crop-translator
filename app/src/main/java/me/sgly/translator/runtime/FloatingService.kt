package me.sgly.translator.runtime

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
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
import me.sgly.translator.R
import me.sgly.translator.api.OcrResult
import me.sgly.translator.api.OpenAIApiClient
import me.sgly.translator.capture.CaptureSession
import me.sgly.translator.logs.AppLog
import me.sgly.translator.settings.SettingsManager
import me.sgly.translator.sheet.SheetActivity
import me.sgly.translator.state.AppPhase
import me.sgly.translator.state.TransitionReason
import me.sgly.translator.state.TranslatorState
import me.sgly.translator.stats.recordRequest

class FloatingService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner {
    private var floatingButton: FloatingButton? = null

    private var session: CaptureSession? = null
    private var sessionJobs: List<Job> = emptyList()
    private lateinit var cropOverlay: CropOverlay
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
        createNotificationChannel(this)
        cropOverlay = CropOverlay(this)
        settingsManager = SettingsManager(this)
        AppLog.forwardToLogcat = settingsManager.forwardLogsToLogcat
        openAIApiClient = OpenAIApiClient(settingsManager)

        observeWindows()
        observeStats()
        drivePipeline()
    }

    // The FAB and the result sheet are recomputed whenever the phase or the sheet window changes: the
    // sheet is re-driven when its window disappears without the phase changing.
    private fun observeWindows() {
        serviceScope.launch {
            combine(TranslatorState.phase, TranslatorState.sheetOnScreen) { phase, _ -> phase }
                .collect { phase ->
                    updateFabVisibility()
                    updateSheetVisibility(phase)
                }
        }
    }

    private fun observeStats() {
        serviceScope.launch {
            TranslatorState.reportedRequests.collect { recordRequest(this@FloatingService, it) }
        }
    }

    private fun drivePipeline() {
        serviceScope.launch {
            TranslatorState.phase.collectLatest { phase -> runStage(phase) }
        }
    }

    private suspend fun runStage(phase: AppPhase) {
        // None / Idle / ViewingLast / Done / OcrError / TranslateError need no work here: the UI handles them.
        when (phase) {
            AppPhase.Capturing -> runCaptureStage()
            AppPhase.Cropping -> runCropStage()
            AppPhase.Recognizing -> runRecognizeStage()
            AppPhase.Translating -> runTranslateStage()
            else -> Unit
        }
    }

    private suspend fun runCaptureStage() {
        val capture = session
        if (capture == null) {
            TranslatorState.transition(AppPhase.None, TransitionReason.CaptureLost)
            return
        }
        val frame = capture.captureFrame()
        if (frame == null) {
            TranslatorState.transition(AppPhase.Idle, TransitionReason.CaptureError)
            return
        }
        AppLog.i("TranslatorState", "capturedBitmap: ${frame.width}×${frame.height}")
        TranslatorState.capturedBitmap = frame
        TranslatorState.transition(AppPhase.Cropping, TransitionReason.FrameCaptured)
    }

    private suspend fun runCropStage() {
        val cropped = cropOverlay.showOverlay(TranslatorState.capturedBitmap!!)
        if (cropped != null) {
            TranslatorState.croppedBitmap = cropped
            AppLog.i("TranslatorState", "croppedBitmap: ${cropped.width}×${cropped.height}")
            cropOverlay.hideOverlay()
            TranslatorState.transition(AppPhase.Recognizing, TransitionReason.CropSelected)
        } else {
            cropOverlay.hideOverlay()
            TranslatorState.transition(AppPhase.Idle, TransitionReason.CropCancelled)
        }
    }

    private suspend fun runRecognizeStage() {
        val cropped = TranslatorState.croppedBitmap
        if (cropped == null) {
            AppLog.i("TranslatorState", "Recognizing with no cropped bitmap; back to Idle")
            TranslatorState.transition(AppPhase.Idle, TransitionReason.Dismiss)
            return
        }
        try {
            val result =
                openAIApiClient.extractTextFromImage(
                    cropped,
                    TranslatorState.currentThinkingEffort(),
                    onOutputDelta = { TranslatorState.onOutputDelta(it) },
                    onReasoningDelta = { TranslatorState.onReasoningDelta(it) },
                    onStats = TranslatorState::onStats,
                )
            // The request may have finished after the run was cancelled.
            currentCoroutineContext().ensureActive()
            if (result.blocks.isEmpty()) {
                TranslatorState.setErrorText(getString(R.string.error_ocr_empty))
                TranslatorState.transition(AppPhase.OcrError, TransitionReason.OcrFailed)
            } else {
                TranslatorState.setOcrResult(result)
                TranslatorState.transition(AppPhase.Translating, TransitionReason.OcrSuccess)
            }
        } catch (e: Exception) {
            // A run the user dismissed is not a failure; leave the state alone.
            currentCoroutineContext().ensureActive()
            TranslatorState.setErrorText(e.userMessage(this))
            TranslatorState.transition(AppPhase.OcrError, TransitionReason.OcrFailed)
        }
    }

    private suspend fun runTranslateStage() {
        try {
            val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
            val ocrText = prettyJson.encodeToString(OcrResult.serializer(), TranslatorState.ocrResult.value!!)
            val result =
                openAIApiClient.translateText(
                    ocrText,
                    TranslatorState.currentThinkingEffort(),
                    onOutputDelta = { TranslatorState.onOutputDelta(it) },
                    onReasoningDelta = { TranslatorState.onReasoningDelta(it) },
                    onStats = TranslatorState::onStats,
                )
            currentCoroutineContext().ensureActive()
            TranslatorState.setTranslationResult(result)
            TranslatorState.transition(AppPhase.Done, TransitionReason.TranslateSuccess)
        } catch (e: Exception) {
            // A run the user dismissed is not a failure; leave the state alone.
            currentCoroutineContext().ensureActive()
            TranslatorState.setErrorText(e.userMessage(this))
            TranslatorState.transition(AppPhase.TranslateError, TransitionReason.TranslateFailed)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notification = createNotification(this)
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

        if (floatingButton == null) {
            showFloatingWindow()
        }

        return START_NOT_STICKY
    }

    private fun showFloatingWindow() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        floatingButton =
            FloatingButton(
                context = this,
                settingsManager = settingsManager,
                lifecycleOwner = this,
                savedStateRegistryOwner = this,
                onLongPress = ::onFabLongPressed,
            ).also { it.show() }
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
        val button = floatingButton ?: return
        serviceScope.launch {
            // Delay past the system's transition to the shared app: launching during it makes
            // MIUI abort the move-to-front ("avoidMoveToFront by anim effect").
            if (returning) delay(500)
            val abandoned =
                !TranslatorState.phase.value.showsSheet ||
                    TranslatorState.sheetOnScreen.value ||
                    (returning && !isCapturedContentVisible())
            if (abandoned) return@launch
            button.setVisible(true)
            delay(120)
            showSheet(clearTask = returning)
            delay(400)
            updateFabVisibility()
        }
    }

    /** With Android 14 app screen sharing, "on screen" means the shared app is in the foreground. */
    private fun updateFabVisibility() {
        val button = floatingButton ?: return
        val show = TranslatorState.phase.value == AppPhase.Idle && isCapturedContentVisible()
        button.setVisible(show)
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
        cropOverlay.release()
        floatingButton?.remove()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
    }
}
