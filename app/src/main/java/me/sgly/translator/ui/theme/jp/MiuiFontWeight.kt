package me.sgly.translator.ui.theme.jp

import android.content.Context
import android.database.ContentObserver
import android.graphics.fonts.SystemFonts
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * MIUI's font weight slider, mirrored so Japanese text can be rendered at the weight the framework
 * would have picked. Every constant here is imposed from outside the app:
 *
 * - The slider is a MIUI/HyperOS private setting, `Settings.Global.key_miui_font_weight_scale`, 0..100
 *   with 50 neutral. The AOSP setting that replaced it, `font_weight_adjustment`, is null on this ROM.
 * - At 50 the framework renders the installed presets of the Japanese font, which are not the nominal
 *   weights: `hyper_fonts.xml` — what `/system/etc/fonts.xml` links to, itself a symlink into the
 *   theme overlay directory, so a theme replaces the whole table — maps nominal weight to axis value
 *   for MiSansJapaneseVF. [ladder] reads that table off the installed fonts rather than hardcoding it,
 *   so a ROM update moves the ladder along with it.
 * - Away from 50 the axis moves linearly at a rate the ROM does not publish: [measuredSliderSlopes],
 *   fitted from 35 slider positions with the weight sweep screen, residual 0.06-0.35 axis units. Every
 *   fitted value lands within 0.02 of a tenth, so the table is kept on that grid instead of carrying
 *   digits the fit cannot support.
 * - This ROM renders Bold exactly like Medium, ladder value included; [weightAliases] records that.
 *
 * The result is a *nominal* weight, so each Japanese face can map it onto its own coordinates: the
 * downloaded serif takes it as an axis value, a static face quantises it.
 */
object MiuiFontWeight {
    private const val SETTINGS_KEY = "key_miui_font_weight_scale"
    private const val NEUTRAL_SCALE = 50
    private const val JAPANESE_FONT_NAME = "MiSansJapaneseVF.ttf"
    private const val AXIS_MIN = 150f
    private const val AXIS_MAX = 700f

    private val weightAliases = mapOf(700 to 500)

    private val measuredSliderSlopes =
        mapOf(
            200 to (1.0f to 3.6f),
            300 to (1.0f to 4.0f),
            400 to (1.6f to 3.8f),
            500 to (1.5f to 5.0f),
            600 to (2.4f to 3.6f),
            700 to (1.5f to 5.0f),
            900 to (3.6f to 3.6f),
        )

    private val _scale = MutableStateFlow<Int?>(null)
    val scale: StateFlow<Int?> = _scale.asStateFlow()

    private val ladder: List<Pair<Int, Float>> by lazy { readLadder() }

    private var appContext: Context? = null

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                appContext?.let { _scale.value = readScale(it) }
            }
        }

    /** True when the slider can be read at all; there is no public API that reports the MIUI version. */
    fun isSupported(context: Context): Boolean {
        val stored = runCatching { Settings.Global.getString(context.contentResolver, SETTINGS_KEY) }
        if (stored.getOrNull() != null) return true
        // The key is only written once the slider has been touched, so its absence says nothing on a
        // device that never adjusted the weight.
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")
    }

    /** Registers the observer; idempotent, and called from composition. */
    fun observe(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        if (!isSupported(app)) return
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor(SETTINGS_KEY), false, observer)
        _scale.value = readScale(app)
    }

    /**
     * The slider position, or null when the system has no such slider. Reading the configuration makes
     * the caller recompose when the system announces a font change, which covers writes that arrive
     * without the observer firing.
     */
    @Composable
    fun rememberScale(): Int? {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        observe(context)
        LaunchedEffect(configuration) { appContext?.let { _scale.value = readScale(it) } }
        return scale.collectAsState().value
    }

    /** The weight the framework would render at, expressed as a nominal weight. */
    fun nominalWeight(
        targetWeight: Int,
        scale: Int,
    ): Int =
        nominalForAxis(axisFor(targetWeight, scale))
            .roundToInt()
            .coerceIn(1, 1000)

    private fun readScale(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, SETTINGS_KEY, NEUTRAL_SCALE) }
            .getOrDefault(NEUTRAL_SCALE)
            .coerceIn(0, 100)

    private fun axisFor(
        targetWeight: Int,
        scale: Int,
    ): Float {
        val weight = weightAliases[targetWeight] ?: targetWeight
        val neutral = neutralAxis(weight)
        val slopes = measuredSliderSlopes[weight] ?: return neutral
        val (below, above) = slopes
        val delta = (scale - NEUTRAL_SCALE) * (if (scale < NEUTRAL_SCALE) below else above)
        return (neutral + delta).coerceIn(AXIS_MIN, AXIS_MAX)
    }

    private fun neutralAxis(targetWeight: Int): Float =
        ladder
            .minByOrNull { abs(it.first - targetWeight) }
            ?.second
            ?: targetWeight.toFloat()

    /** Inverts the ladder: what nominal weight the framework reaches at [axis]. */
    private fun nominalForAxis(axis: Float): Float {
        if (ladder.isEmpty()) return axis
        var lower: Pair<Int, Float>? = null
        for (point in ladder) {
            val previous = lower
            if (axis <= point.second) {
                if (previous == null) return point.first.toFloat()
                val span = point.second - previous.second
                if (span <= 0f) return point.first.toFloat()
                val ratio = (axis - previous.second) / span
                return previous.first + (point.first - previous.first) * ratio
            }
            lower = point
        }
        return ladder.last().first.toFloat()
    }

    private fun readLadder(): List<Pair<Int, Float>> =
        runCatching {
            SystemFonts
                .getAvailableFonts()
                .mapNotNull { font ->
                    val axis = font.axes?.firstOrNull { it.tag == "wght" }
                    if (font.file?.name == JAPANESE_FONT_NAME && axis != null) {
                        font.style.weight to axis.styleValue
                    } else {
                        null
                    }
                }.distinct()
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
}
