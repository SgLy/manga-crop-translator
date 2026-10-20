package me.sgly.translator.ui.theme.jp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The appearance switches, read straight from the settings file so that every screen picks up a change
 * without the caller passing anything down. The keys live here because they are the contract between
 * the settings page and the font layer; [me.sgly.translator.settings.SettingsManager] writes them through this.
 */
object JapaneseFontPreferences {
    const val MIUI_ADAPTATION = "japaneseFontMiuiAdaptation"
    const val VARIABLE_FONT = "japaneseFontVariable"

    data class Flags(
        val miuiAdaptation: Boolean,
        val variableFont: Boolean,
    )

    private const val FILE_NAME = "api_settings"

    private val _flags = MutableStateFlow(Flags(miuiAdaptation = false, variableFont = false))
    val flags: StateFlow<Flags> = _flags.asStateFlow()

    private var sharedPreferences: SharedPreferences? = null

    // SharedPreferences holds its listeners weakly, so this one has to be kept alive by hand.
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            sharedPreferences?.let { prefs ->
                if (key == MIUI_ADAPTATION || key == VARIABLE_FONT) _flags.value = read(prefs)
            }
        }

    /** Idempotent; called from composition when Japanese text is first styled. */
    fun observe(context: Context) {
        if (sharedPreferences != null) return
        val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        sharedPreferences = prefs
        prefs.registerOnSharedPreferenceChangeListener(listener)
        _flags.value = read(prefs)
    }

    private fun read(prefs: SharedPreferences): Flags =
        Flags(
            miuiAdaptation = prefs.getBoolean(MIUI_ADAPTATION, false),
            variableFont = prefs.getBoolean(VARIABLE_FONT, false),
        )
}
