package me.sgly.translator

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)

    var visualModelApiBase: String
        get() = prefs.getString("visualModelApiBase", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("visualModelApiBase", value).apply()

    var visualModelApiKey: String
        get() = prefs.getString("visualModelApiKey", "") ?: ""
        set(value) = prefs.edit().putString("visualModelApiKey", value).apply()

    var visualModel: String
        get() = prefs.getString("visualModel", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("visualModel", value).apply()

    var translateModelApiBase: String
        get() = prefs.getString("translateModelApiBase", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("translateModelApiBase", value).apply()

    var translateModelApiKey: String
        get() = prefs.getString("translateModelApiKey", "") ?: ""
        set(value) = prefs.edit().putString("translateModelApiKey", value).apply()

    var translateModel: String
        get() = prefs.getString("translateModel", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("translateModel", value).apply()

    /** When true, the translation step reuses the VisualModel endpoint/model instead of the TranslateModel one. */
    var useVisualModelForTranslation: Boolean
        get() = prefs.getBoolean("useVisualModelForTranslation", true)
        set(value) = prefs.edit().putBoolean("useVisualModelForTranslation", value).apply()

    /** When true, [AppLog] also forwards lines to logcat; otherwise they stay in memory only. */
    var forwardLogsToLogcat: Boolean
        get() = prefs.getBoolean("forwardLogsToLogcat", false)
        set(value) = prefs.edit().putBoolean("forwardLogsToLogcat", value).apply()

    /** Raw thinking-strength string, split on non-letters. */
    var thinkingLevels: String
        get() = prefs.getString("thinkingLevels", "low,high,max") ?: "low,high,max"
        set(value) = prefs.edit().putString("thinkingLevels", value).apply()

    /** Parsed thinking ladder, e.g. "low,high,max" → [low, high, max]. */
    val thinkingLevelList: List<String>
        get() =
            thinkingLevels
                .split(Regex("[^a-zA-Z]+"))
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
