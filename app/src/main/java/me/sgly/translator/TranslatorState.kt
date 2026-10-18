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
    Translating, // Result sheet open, translateText() in progress
    Done, // Result sheet open, both steps complete
    OcrError, // Result sheet open, OCR failed
    TranslateError, // Result sheet open, the translation failed (OCR result preserved)
    ViewingLast, // Result sheet open, showing the previous result
    ShowingLogs, // Logs screen open, the result sheet is hidden
    ;

    val showsSheet: Boolean
        get() =
            when (this) {
                Recognizing, Translating, Done, OcrError, TranslateError, ViewingLast -> true
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
    OcrSuccess, // Recognizing → Translating
    OcrFailed, // Recognizing → OcrError
    TranslateSuccess, // Translating → Done
    TranslateFailed, // Translating → TranslateError
    ThinkHarder, // Done → Recognizing (re-run everything, same crop)
    Retry, // OcrError → Recognizing; TranslateError → Translating
    Dismiss, // Done/OcrError/TranslateError → Idle
    Reset, // any → None (a capture flow is starting over)
    ShowLogs, // Done/OcrError/TranslateError/ViewingLast → ShowingLogs
    CloseLogs, // ShowingLogs → the phase it was opened from
}

/** What one run through the pipeline produced, filled in as the run progresses. */
data class RunResults(
    val ocr: OcrResult? = null,
    val translation: TranslationResult? = null,
    val error: String = "",
    val requests: List<RequestStats> = emptyList(),
) {
    /** This run as a finished translation, or null while a step is still missing. */
    fun completedOrNull(): RunResults? = takeIf { ocr != null && translation != null }?.copy(error = "")
}

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

    private val _reportedRequests = MutableSharedFlow<RequestStats>(extraBufferCapacity = 16)
    val reportedRequests: SharedFlow<RequestStats> = _reportedRequests.asSharedFlow()

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

    /** The last completed run, kept so a long press on the FAB can bring it back. */
    var lastTranslation: RunResults? = null
        private set

    /** Why the current round of inference started, or null while none is in flight. */
    var runReason: TransitionReason? = null
        private set

    /** Stats row of the translation on screen, so 「记一下」 can record what it saved. */
    var translationId: Long? = null

    /** Stats row of the round in flight, so each request can be attached as it finishes. */
    var roundId: Long? = null

    /** The result phase a logs screen was opened from, so closing it returns there. */
    var phaseBeforeLogs: AppPhase? = null
        private set

    // The run in flight, observed by the UI one field at a time
    private val _ocrResult = MutableStateFlow<OcrResult?>(null)
    val ocrResult: StateFlow<OcrResult?> = _ocrResult.asStateFlow()

    private val _translationResult = MutableStateFlow<TranslationResult?>(null)
    val translationResult: StateFlow<TranslationResult?> = _translationResult.asStateFlow()

    private val _errorText = MutableStateFlow("")
    val errorText: StateFlow<String> = _errorText.asStateFlow()

    private val _runRequests = MutableStateFlow<List<RequestStats>>(emptyList())
    val runRequests: StateFlow<List<RequestStats>> = _runRequests.asStateFlow()

    private var results: RunResults
        get() = RunResults(_ocrResult.value, _translationResult.value, _errorText.value, _runRequests.value)
        set(value) {
            _ocrResult.value = value.ocr
            _translationResult.value = value.translation
            _errorText.value = value.error
            _runRequests.value = value.requests
        }

    // Data setters — call before transition() so UI sees consistent state on phase change
    fun setOcrResult(result: OcrResult) {
        results = results.copy(ocr = result)
    }

    fun setTranslationResult(result: TranslationResult) {
        results = results.copy(translation = result)
    }

    fun setErrorText(text: String) {
        results = results.copy(error = text)
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
        results = results.copy(requests = results.requests + stats)
        _reportedRequests.tryEmit(stats)
    }

    private fun resetStreamProgress() {
        _firstTokenReceived.value = false
        _reasoning.value = ""
        streamTape.clear()
    }

    /** Opens the next round of inference, whose stats row is created with its first request. */
    private fun beginRound(next: RunResults) {
        results = next.copy(requests = emptyList())
        roundId = null
    }

    /** Everything the run in flight produced, gone; the lease and the remembered translation stay. */
    private fun clearRun() {
        beginRound(RunResults())
        capturedBitmap = null
        croppedBitmap = null
        _thinkingLevelIndex.value = 0
        resetStreamProgress()
    }

    /** Everything the capture lease produced, gone; only the lease itself lives on. */
    private fun clearSession() {
        clearRun()
        lastTranslation = null
        translationId = null
        runReason = null
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
                beginRound(results)
                runReason = reason
            }

            TransitionReason.ThinkHarder -> {
                escalateThinking()
                beginRound(RunResults())
                runReason = reason
            }

            TransitionReason.Retry -> {
                beginRound(RunResults(ocr = results.ocr.takeIf { from == AppPhase.TranslateError }))
                runReason = reason
            }

            TransitionReason.FabLongPressed -> {
                lastTranslation?.let { results = it }
            }

            TransitionReason.TranslateSuccess -> {
                results.completedOrNull()?.let { lastTranslation = it }
            }

            TransitionReason.ShowLogs -> {
                phaseBeforeLogs = from
            }

            TransitionReason.CloseLogs -> {
                phaseBeforeLogs = null
            }

            TransitionReason.Dismiss,
            TransitionReason.CropCancelled,
            TransitionReason.CaptureError,
            -> {
                clearRun()
            }

            TransitionReason.CaptureLost,
            TransitionReason.Reset,
            -> {
                clearSession()
            }

            else -> {}
        }
        if (to == AppPhase.Recognizing || to == AppPhase.Translating) {
            resetStreamProgress()
        }
        _phase.value = to
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
