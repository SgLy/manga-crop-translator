package me.sgly.translator

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class StepStatus {
    IDLE,       // Not started
    LOADING,    // In progress (show spinner)
    DONE,       // Completed (show checkmark)
    ERROR       // Failed
}

object TranslatorState {
    private val _ocrStatus = MutableStateFlow(StepStatus.IDLE)
    val ocrStatus: StateFlow<StepStatus> = _ocrStatus

    private val _llmStatus = MutableStateFlow(StepStatus.IDLE)
    val llmStatus: StateFlow<StepStatus> = _llmStatus

    private val _ocrResult = MutableStateFlow<OcrResult?>(null)
    val ocrResult: StateFlow<OcrResult?> = _ocrResult

    private val _translationResult = MutableStateFlow<TranslationResult?>(null)
    val translationResult: StateFlow<TranslationResult?> = _translationResult

    private val _errorText = MutableStateFlow("")
    val errorText: StateFlow<String> = _errorText

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible

    fun startOcr() {
        _ocrStatus.value = StepStatus.LOADING
        _llmStatus.value = StepStatus.IDLE
        _ocrResult.value = null
        _translationResult.value = null
        _errorText.value = ""
        _isVisible.value = true
    }

    fun ocrDone(result: OcrResult) {
        _ocrStatus.value = StepStatus.DONE
        _ocrResult.value = result
    }

    fun ocrError(message: String) {
        _ocrStatus.value = StepStatus.ERROR
        _errorText.value = message
    }

    fun startLlm() {
        _llmStatus.value = StepStatus.LOADING
    }

    fun llmDone(result: TranslationResult) {
        _llmStatus.value = StepStatus.DONE
        _translationResult.value = result
    }

    fun llmError(message: String) {
        _llmStatus.value = StepStatus.ERROR
        _errorText.value = message
    }

    fun dismiss() {
        _isVisible.value = false
        _ocrStatus.value = StepStatus.IDLE
        _llmStatus.value = StepStatus.IDLE
        _ocrResult.value = null
        _translationResult.value = null
        _errorText.value = ""
    }
}

class ResultOverlayManager(private val context: Context) {

    fun startOcr() {
        if (!TranslatorState.isVisible.value) {
            showOverlay()
        }
        TranslatorState.startOcr()
    }

    fun ocrDone(result: OcrResult) {
        TranslatorState.ocrDone(result)
    }

    fun ocrError(message: String) {
        TranslatorState.ocrError(message)
    }

    fun startLlm() {
        TranslatorState.startLlm()
    }

    fun llmDone(result: TranslationResult) {
        TranslatorState.llmDone(result)
    }

    fun llmError(message: String) {
        TranslatorState.llmError(message)
    }

    fun dismiss() {
        TranslatorState.dismiss()
    }

    private fun showOverlay() {
        val intent = Intent(context, ResultActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    fun release() {
        dismiss()
    }
}