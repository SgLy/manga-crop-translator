package me.sgly.translator

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppPhase {
    Idle, // FAB visible, waiting for user
    Capturing, // FAB hidden, waiting for screen frame
    Cropping, // Crop overlay visible, user selecting region
    Recognizing, // Result sheet open, OCR in progress
    Parsing, // Result sheet open, TranslateModel in progress
    Done, // Result sheet open, both steps complete
    OcrError, // Result sheet open, OCR failed
    TranslateError, // Result sheet open, TranslateModel failed (OCR result preserved)
    ViewingLast, // Result sheet open, showing the previous result
}

enum class TransitionReason {
    FabClicked, // Idle → Capturing
    FabLongPressed, // Idle → ViewingLast
    FrameCaptured, // Capturing → Cropping
    CaptureError, // Capturing → Idle (mediaProjection not ready)
    CropSelected, // Cropping → Recognizing
    CropCancelled, // Cropping → Idle
    OcrSuccess, // Recognizing → Parsing
    OcrFailed, // Recognizing → OcrError
    TranslateSuccess, // Parsing → Done
    TranslateFailed, // Parsing → TranslateError
    ThinkHarder, // Done → Recognizing (re-run everything, same crop)
    Retry, // OcrError → Recognizing; TranslateError → Parsing
    Dismiss, // Done/OcrError/TranslateError → Idle
    Reset, // any → Idle (triggered from MainActivity on fresh start)
}

data class Transition(
    val from: AppPhase,
    val to: AppPhase,
    val reason: TransitionReason,
)

/** The last completed translation, kept so a long press on the FAB can bring it back. */
data class LastTranslation(
    val ocr: OcrResult,
    val translation: TranslationResult,
)

object TranslatorState {
    private val _phase = MutableStateFlow(AppPhase.Idle)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    /** Whether the captured region (the shared app) is currently visible on screen. */
    private val _capturedContentVisible = MutableStateFlow(false)
    val capturedContentVisible: StateFlow<Boolean> = _capturedContentVisible.asStateFlow()

    /**
     * Whether the in-flight streaming response has already delivered its first token.
     * False while we are still waiting for the first SSE chunk, true from that first delta on.
     */
    private val _firstTokenReceived = MutableStateFlow(false)
    val firstTokenReceived: StateFlow<Boolean> = _firstTokenReceived.asStateFlow()

    private val _reasoning = MutableStateFlow("")
    val reasoning: StateFlow<String> = _reasoning.asStateFlow()

    private val _lastTransition = MutableSharedFlow<Transition>(extraBufferCapacity = 1)
    val lastTransition: SharedFlow<Transition> = _lastTransition.asSharedFlow()

    // Thinking ladder: user-configured effort tokens, walked by index on each ThinkHarder.
    private var thinkingLevels: List<String> = listOf("low", "high", "max")
    private val _thinkingLevelIndex = MutableStateFlow(0)
    val thinkingLevelIndex: StateFlow<Int> = _thinkingLevelIndex.asStateFlow()

    /** Installs the parsed thinking ladder (from settings) and resets to the first level. */
    fun setThinkingLevels(levels: List<String>) {
        thinkingLevels = levels.ifEmpty { listOf("low", "high", "max") }
        _thinkingLevelIndex.value = 0
    }

    /** Effort token for the current level, e.g. "high". */
    fun currentThinkingEffort(): String = thinkingLevels.getOrElse(_thinkingLevelIndex.value) { thinkingLevels.last() }

    private fun escalateThinking() {
        _thinkingLevelIndex.value =
            (_thinkingLevelIndex.value + 1).coerceAtMost(thinkingLevels.lastIndex)
    }

    // Raw bitmaps: not observed reactively by UI, read directly when needed
    var capturedBitmap: Bitmap? = null
    var croppedBitmap: Bitmap? = null

    /** The last completed translation. Survives a dismiss, so the FAB can hand it back. */
    var lastTranslation: LastTranslation? = null
        private set

    // Result data: observed reactively by UI components
    private val _ocrResult = MutableStateFlow<OcrResult?>(null)
    val ocrResult: StateFlow<OcrResult?> = _ocrResult.asStateFlow()

    private val _translationResult = MutableStateFlow<TranslationResult?>(null)
    val translationResult: StateFlow<TranslationResult?> = _translationResult.asStateFlow()

    private val _errorText = MutableStateFlow("")
    val errorText: StateFlow<String> = _errorText.asStateFlow()

    // Data setters — call before transition() so UI sees consistent state on phase change
    fun setOcrResult(result: OcrResult) {
        _ocrResult.value = result
    }

    fun setTranslationResult(result: TranslationResult) {
        _translationResult.value = result
    }

    fun setErrorText(text: String) {
        _errorText.value = text
    }

    fun setCapturedContentVisible(visible: Boolean) {
        _capturedContentVisible.value = visible
    }

    /**
     * Called with every streamed output delta of the in-flight request. Only the first one matters:
     * it flips the step row from the waiting spinner to the streaming arrow.
     */
    fun onOutputDelta(delta: String) {
        _firstTokenReceived.value = true
    }

    /** Called with every streamed reasoning delta of the in-flight request. */
    fun onReasoningDelta(delta: String) {
        _reasoning.value += delta
        _firstTokenReceived.value = true
    }

    /** Back to "nothing received yet" — called whenever a new streaming phase starts. */
    private fun resetStreamProgress() {
        _firstTokenReceived.value = false
        _reasoning.value = ""
    }

    /**
     * The single entry point for all state transitions.
     * Applies data-clearing rules appropriate to the reason, then updates phase.
     */
    fun transition(
        to: AppPhase,
        reason: TransitionReason,
    ) {
        val from = _phase.value
        AppLog.i("TranslatorState", "$from → $to [$reason]")
        when (reason) {
            TransitionReason.FabClicked -> {
                // A new capture session starts: reset to the base thinking level
                _thinkingLevelIndex.value = 0
            }

            TransitionReason.FabLongPressed -> {
                // Show the previous result again without re-running anything.
                lastTranslation?.let {
                    _ocrResult.value = it.ocr
                    _translationResult.value = it.translation
                }
                _errorText.value = ""
            }

            TransitionReason.TranslateSuccess -> {
                // Keep the finished pair so a later long press can show it again.
                val ocr = _ocrResult.value
                val translation = _translationResult.value
                if (ocr != null && translation != null) {
                    lastTranslation = LastTranslation(ocr, translation)
                }
            }

            TransitionReason.ThinkHarder -> {
                // Re-run OCR with same crop: clear both results and think harder
                _ocrResult.value = null
                _translationResult.value = null
                _errorText.value = ""
                escalateThinking()
            }

            TransitionReason.Retry -> {
                _errorText.value = ""
                _translationResult.value = null
                // OcrError retry re-runs OCR: also clear ocrResult
                if (from == AppPhase.OcrError) _ocrResult.value = null
                // TranslateError retry preserves ocrResult (only re-runs TranslateModel)
            }

            TransitionReason.Reset -> {
                capturedBitmap = null
                croppedBitmap = null
                _ocrResult.value = null
                _translationResult.value = null
                _errorText.value = ""
                _thinkingLevelIndex.value = 0
                lastTranslation = null
            }

            TransitionReason.Dismiss,
            TransitionReason.CropCancelled,
            TransitionReason.CaptureError,
            -> {
                capturedBitmap = null
                croppedBitmap = null
                _ocrResult.value = null
                _translationResult.value = null
                _errorText.value = ""
                _thinkingLevelIndex.value = 0
            }

            else -> {}
        }
        // Entering a streaming phase means a new request is starting: nothing has arrived yet.
        if (to == AppPhase.Recognizing || to == AppPhase.Parsing) {
            resetStreamProgress()
        }
        _phase.value = to
        _lastTransition.tryEmit(Transition(from, to, reason))
    }

    /** Resets all state to Idle. Called from MainActivity on fresh start. */
    fun reset() {
        val from = _phase.value
        capturedBitmap = null
        croppedBitmap = null
        lastTranslation = null
        _ocrResult.value = null
        _translationResult.value = null
        _errorText.value = ""
        _thinkingLevelIndex.value = 0
        _capturedContentVisible.value = false
        resetStreamProgress()
        _phase.value = AppPhase.Idle
        _lastTransition.tryEmit(Transition(from, AppPhase.Idle, TransitionReason.Reset))
    }
}
