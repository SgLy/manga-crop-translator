package me.sgly.translator

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)

    var vlmApiBase: String
        get() = prefs.getString("vlmApiBase", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("vlmApiBase", value).apply()

    var vlmApiKey: String
        get() = prefs.getString("vlmApiKey", "") ?: ""
        set(value) = prefs.edit().putString("vlmApiKey", value).apply()

    var vlmModel: String
        get() = prefs.getString("vlmModel", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("vlmModel", value).apply()

    var llmApiBase: String
        get() = prefs.getString("llmApiBase", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("llmApiBase", value).apply()

    var llmApiKey: String
        get() = prefs.getString("llmApiKey", "") ?: ""
        set(value) = prefs.edit().putString("llmApiKey", value).apply()

    var llmModel: String
        get() = prefs.getString("llmModel", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("llmModel", value).apply()

    /** When true, the translation step reuses the VLM endpoint/model instead of the LLM one. */
    var useVlmForTranslation: Boolean
        get() = prefs.getBoolean("useVlmForTranslation", true)
        set(value) = prefs.edit().putBoolean("useVlmForTranslation", value).apply()

    /** Raw thinking-strength string, split on non-letters. */
    var thinkingLevels: String
        get() = prefs.getString("thinkingLevels", "low,high,max") ?: "low,high,max"
        set(value) = prefs.edit().putString("thinkingLevels", value).apply()

    /** Parsed thinking ladder, e.g. "low,high,max" → [low, high, max]. */
    val thinkingLevelList: List<String>
        get() = thinkingLevels.split(Regex("[^a-zA-Z]+"))
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("low", "high", "max") }

    /** Last floating-button position (TOP|START gravity). -1 means "not set yet". */
    var fabX: Int
        get() = prefs.getInt("fabX", -1)
        set(value) = prefs.edit().putInt("fabX", value).apply()

    var fabY: Int
        get() = prefs.getInt("fabY", -1)
        set(value) = prefs.edit().putInt("fabY", value).apply()
}