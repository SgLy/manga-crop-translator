package me.sgly.translator

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppPhase {
    None, // No screen capture lease: nothing to translate, no button to show
    Idle, // FAB visible, waiting for user
    Capturing, // FAB hidden, waiting for screen frame
    Cropping, // Crop overlay visible, user selecting region
    Recognizing, // Result sheet open, OCR in progress
    Parsing, // Result sheet open, TranslateModel in progress
    Done, // Result sheet open, both steps complete
    OcrError, // Result sheet open, OCR failed
    TranslateError, // Result sheet open, TranslateModel failed (OCR result preserved)
    ViewingLast, // Result sheet open, showing the previous result
    ShowingLogs, // Logs screen open, the result sheet is hidden
    ;

    val showsSheet: Boolean
        get() =
            when (this) {
                Recognizing, Parsing, Done, OcrError, TranslateError, ViewingLast -> true
                None, Idle, Capturing, Cropping, ShowingLogs -> false
            }
}

enum class TransitionReason {
    FabClicked, // Idle → Capturing
    FabLongPressed, // Idle → ViewingLast
    CaptureGranted, // any → Idle (a fresh lease is ready to capture with)
    CaptureLost, // any → None (the lease is gone, by this app or by the system)
    FrameCaptured, // Capturing → Cropping
    CaptureError, // Capturing → Idle (no frame arrived)
    CropSelected, // Cropping → Recognizing
    CropCancelled, // Cropping → Idle
    OcrSuccess, // Recognizing → Parsing
    OcrFailed, // Recognizing → OcrError
    TranslateSuccess, // Parsing → Done
    TranslateFailed, // Parsing → TranslateError
    ThinkHarder, // Done → Recognizing (re-run everything, same crop)
    Retry, // OcrError → Recognizing; TranslateError → Parsing
    Dismiss, // Done/OcrError/TranslateError → Idle
    Reset, // any → None (a capture flow is starting over)
    ShowLogs, // Done/OcrError/TranslateError/ViewingLast → ShowingLogs
    CloseLogs, // ShowingLogs → the phase it was opened from
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
    val runStats: RequestStats?,
)

/** Thinking strength a model can be asked for, weakest first. */
enum class ThinkingLevel {
    Low,
    Medium,
    High,
    Max,
    ;

    /** The effort token the API expects for this level. */
    val effort: String get() = name.lowercase()

    companion object {
        /** Sequence a fresh install starts with, and the fallback when the stored one is unusable. */
        val defaultSequence = listOf(Low, High, Max)
    }
}

object TranslatorState {
    private val _phase = MutableStateFlow(AppPhase.None)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    /** Whether the result sheet is on screen. Owned by SheetActivity, read by the sheet driver. */
    private val _sheetOnScreen = MutableStateFlow(false)
    val sheetOnScreen: StateFlow<Boolean> = _sheetOnScreen.asStateFlow()

    private val _firstTokenReceived = MutableStateFlow(false)
    val firstTokenReceived: StateFlow<Boolean> = _firstTokenReceived.asStateFlow()

    private val _reasoning = MutableStateFlow("")
    val reasoning: StateFlow<String> = _reasoning.asStateFlow()

    val streamTape = StreamTape()
    private val _runStats = MutableStateFlow<RequestStats?>(null)
    val runStats: StateFlow<RequestStats?> = _runStats.asStateFlow()

    private val _lastTransition = MutableSharedFlow<Transition>(extraBufferCapacity = 1)
    val lastTransition: SharedFlow<Transition> = _lastTransition.asSharedFlow()

    private var thinkingSequence: List<ThinkingLevel> = ThinkingLevel.defaultSequence
    private val _thinkingLevelIndex = MutableStateFlow(0)
    val thinkingLevelIndex: StateFlow<Int> = _thinkingLevelIndex.asStateFlow()

    fun setThinkingSequence(sequence: List<ThinkingLevel>) {
        thinkingSequence = sequence.ifEmpty { ThinkingLevel.defaultSequence }
        _thinkingLevelIndex.value = 0
    }

    fun currentThinkingEffort(): String = thinkingSequence.getOrElse(_thinkingLevelIndex.value) { thinkingSequence.last() }.effort

    private fun escalateThinking() {
        _thinkingLevelIndex.value =
            (_thinkingLevelIndex.value + 1).coerceAtMost(thinkingSequence.lastIndex)
    }

    var capturedBitmap: Bitmap? = null
    var croppedBitmap: Bitmap? = null

    var lastTranslation: LastTranslation? = null
        private set

    /** Why the current round of inference started, or null while none is in flight. */
    var runReason: TransitionReason? = null
        private set

    /** Stats row of the translation on screen, so 「记一下」 can record what it saved. */
    var translationId: Long? = null

    var roundVisionRequests = 0
        private set

    /** Translation requests the round in flight has made. */
    var roundParseRequests = 0
        private set

    /** The result phase a logs screen was opened from, so closing it returns there. */
    var phaseBeforeLogs: AppPhase? = null
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

    fun setSheetOnScreen(onScreen: Boolean) {
        _sheetOnScreen.value = onScreen
    }

    /**
     * Called with every streamed output delta of the in-flight request: content as well as tool
     * arguments. The first one flips the step row from the waiting spinner to the streaming arrow.
     */
    fun onOutputDelta(delta: String) {
        streamTape.append(delta.length)
        _firstTokenReceived.value = true
    }

    fun onReasoningDelta(delta: String) {
        _reasoning.value += delta
        streamTape.append(delta.length)
        _firstTokenReceived.value = true
    }

    /** Called once per request with what that request cost. */
    fun onStats(stats: RequestStats) {
        _runStats.value = _runStats.value?.plus(stats) ?: stats
    }

    fun onVisionRequest() {
        roundVisionRequests++
    }

    fun onParseRequest() {
        roundParseRequests++
    }

    private fun resetStreamProgress() {
        _firstTokenReceived.value = false
        _reasoning.value = ""
        streamTape.clear()
    }

    private fun resetRunStats() {
        _runStats.value = null
        roundVisionRequests = 0
        roundParseRequests = 0
    }

    /** The single entry point for all state transitions. */
    fun transition(
        to: AppPhase,
        reason: TransitionReason,
    ) {
        val from = _phase.value
        AppLog.i("TranslatorState", "$from → $to [$reason]")
        when (reason) {
            TransitionReason.FabClicked -> {
                _thinkingLevelIndex.value = 0
                resetRunStats()
                runReason = reason
            }

            TransitionReason.FabLongPressed -> {
                lastTranslation?.let {
                    _ocrResult.value = it.ocr
                    _translationResult.value = it.translation
                    _runStats.value = it.runStats
                }
                _errorText.value = ""
            }

            TransitionReason.ShowLogs -> {
                phaseBeforeLogs = from
            }

            TransitionReason.CloseLogs -> {
                phaseBeforeLogs = null
            }

            TransitionReason.TranslateSuccess -> {
                val ocr = _ocrResult.value
                val translation = _translationResult.value
                if (ocr != null && translation != null) {
                    lastTranslation = LastTranslation(ocr, translation, _runStats.value)
                }
            }

            TransitionReason.ThinkHarder -> {
                _ocrResult.value = null
                _translationResult.value = null
                _errorText.value = ""
                resetRunStats()
                escalateThinking()
                runReason = reason
            }

            TransitionReason.Retry -> {
                _errorText.value = ""
                _translationResult.value = null
                resetRunStats()
                runReason = reason
                if (from == AppPhase.OcrError) _ocrResult.value = null
                // TranslateError retry preserves ocrResult (only re-runs TranslateModel)
            }

            TransitionReason.CaptureLost,
            TransitionReason.Reset,
            -> {
                capturedBitmap = null
                croppedBitmap = null
                _ocrResult.value = null
                _translationResult.value = null
                _errorText.value = ""
                _thinkingLevelIndex.value = 0
                lastTranslation = null
                resetRunStats()
                resetStreamProgress()
                runReason = null
                translationId = null
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
                resetRunStats()
            }

            else -> {}
        }
        if (to == AppPhase.Recognizing || to == AppPhase.Parsing) {
            resetStreamProgress()
        }
        _phase.value = to
        _lastTransition.tryEmit(Transition(from, to, reason))
    }

    fun closeLogsIfOpen() {
        if (_phase.value != AppPhase.ShowingLogs) return
        transition(phaseBeforeLogs ?: AppPhase.Done, TransitionReason.CloseLogs)
    }

    fun reset() {
        transition(AppPhase.None, TransitionReason.Reset)
    }
}

/**
 * The streaming output as a tape of delta lengths, newest last. Reasoning and content both land
 * here, so the tape keeps moving while the model writes its tool arguments. Only the newest
 * [capacity] deltas are kept, which is more than the strip can draw.
 */
class StreamTape(
    private val capacity: Int = 256,
) {
    private val lengths = IntArray(capacity)

    /** Deltas appended since the tape was created, including those that have scrolled off. */
    var total: Int = 0
        private set

    val available: Int
        get() = minOf(total, capacity)

    fun append(length: Int) {
        lengths[total % capacity] = length
        total++
    }

    /** The length of the delta [age] slots back from the newest, or null once it has scrolled off. */
    fun lengthAt(age: Int): Int? = if (age in 0 until available) lengths[(total - 1 - age) % capacity] else null

    fun clear() {
        total = 0
    }
}
