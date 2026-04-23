package me.sgly.translator

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TranslatorState {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible

    fun appendText(newText: String, clearFirst: Boolean = false) {
        if (clearFirst) {
            _text.value = newText
        } else {
            _text.value += newText
        }
        _isVisible.value = true
    }

    fun dismiss() {
        _isVisible.value = false
    }
}

class ResultOverlayManager(private val context: Context) {

    fun appendText(text: String, clearFirst: Boolean = false) {
        if (!TranslatorState.isVisible.value) {
            showOverlay()
        }
        TranslatorState.appendText(text, clearFirst)
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
