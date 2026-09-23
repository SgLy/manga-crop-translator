package me.sgly.translator

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsManager(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)

    var visualModelApiBase: String
        get() = prefs.getString("visualModelApiBase", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit { putString("visualModelApiBase", value) }

    var visualModelApiKey: String
        get() = prefs.getString("visualModelApiKey", "") ?: ""
        set(value) = prefs.edit { putString("visualModelApiKey", value) }

    var visualModel: String
        get() = prefs.getString("visualModel", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit { putString("visualModel", value) }

    var translateModelApiBase: String
        get() = prefs.getString("translateModelApiBase", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit { putString("translateModelApiBase", value) }

    var translateModelApiKey: String
        get() = prefs.getString("translateModelApiKey", "") ?: ""
        set(value) = prefs.edit { putString("translateModelApiKey", value) }

    var translateModel: String
        get() = prefs.getString("translateModel", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit { putString("translateModel", value) }

    /** When true, the translation step reuses the VisualModel endpoint/model instead of the TranslateModel one. */
    var useVisualModelForTranslation: Boolean
        get() = prefs.getBoolean("useVisualModelForTranslation", true)
        set(value) = prefs.edit { putBoolean("useVisualModelForTranslation", value) }

    /** When true, [AppLog] also forwards lines to logcat; otherwise they stay in memory only. */
    var forwardLogsToLogcat: Boolean
        get() = prefs.getBoolean("forwardLogsToLogcat", false)
        set(value) = prefs.edit { putBoolean("forwardLogsToLogcat", value) }

    /** When true, the result sheet shows the model's streamed reasoning in a strip above the actions. */
    var showReasoning: Boolean
        get() = prefs.getBoolean("showReasoning", false)
        set(value) = prefs.edit { putBoolean("showReasoning", value) }

    /** The thinking sequence, first level tried first; each "think harder" steps up one. */
    var thinkingSequence: List<ThinkingLevel>
        get() = parseThinkingSequence(prefs.getString("thinkingLevels", null))
        set(value) = prefs.edit { putString("thinkingLevels", value.joinToString(",") { it.effort }) }

    /** Last floating-button position (TOP|START gravity). -1 means "not set yet". */
    var fabX: Int
        get() = prefs.getInt("fabX", -1)
        set(value) = prefs.edit { putInt("fabX", value) }

    var fabY: Int
        get() = prefs.getInt("fabY", -1)
        set(value) = prefs.edit { putInt("fabY", value) }
}

private val thinkingLevelSeparator = Regex("[^a-zA-Z]+")

/** Tolerant of what the old free-text field let through, e.g. "low, high" or "low/high/max". */
private fun parseThinkingSequence(raw: String?): List<ThinkingLevel> =
    raw
        .orEmpty()
        .split(thinkingLevelSeparator)
        .mapNotNull { token -> ThinkingLevel.entries.firstOrNull { it.effort == token } }
        .ifEmpty { ThinkingLevel.defaultSequence }
