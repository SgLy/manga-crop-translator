package me.sgly.translator.ui.theme.jp

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import kotlin.math.roundToInt

/**
 * Japanese text asks for the ja family, or the system answers kanji with Chinese glyph variants: the
 * `serif` family is Latin-only, so its kanji come from the `fallbackFor="serif"` entries, and which of
 * those is picked follows the locale. That is also why the standard family works for serif and not for
 * sans-serif: the primary sans family covers every kanji, so the locale is never consulted.
 */
private val JapaneseLocale = LocaleList("ja")

/**
 * The one entry point for Japanese text: the ambient style with a serif family, Japanese glyphs and the
 * weight to render at. Callers hand it to `style` and never pick a family, a locale or a weight
 * themselves:
 *
 * ```
 * Text(text = lemma, style = japaneseTextStyle(FontWeight.Normal))
 * ```
 *
 * The weight comes from the MIUI slider while that is switched on, and from the caller otherwise. The
 * face is the downloaded variable serif while it is present and switched on, else the framework's own
 * `serif`.
 */
@Composable
fun japaneseTextStyle(weight: FontWeight): TextStyle {
    val context = LocalContext.current
    JapaneseFontPreferences.observe(context)
    LaunchedEffect(context) { NotoSerifJp.refresh(context) }

    val flags by JapaneseFontPreferences.flags.collectAsState()
    val downloaded by NotoSerifJp.state.collectAsState()
    val scale = if (flags.miuiAdaptation) MiuiFontWeight.rememberScale() else null

    val target = scale?.let { MiuiFontWeight.nominalWeight(weight.weight, it) } ?: weight.weight
    val face = remember(target, flags, downloaded) { resolveFace(target, flags, downloaded) }
    return LocalTextStyle.current.merge(
        TextStyle(fontFamily = face.family, fontWeight = face.weight, localeList = JapaneseLocale),
    )
}

private data class JapaneseFace(
    val family: FontFamily,
    val weight: FontWeight,
)

private fun resolveFace(
    target: Int,
    flags: JapaneseFontPreferences.Flags,
    downloaded: NotoSerifJp.State,
): JapaneseFace {
    if (flags.miuiAdaptation && flags.variableFont && downloaded is NotoSerifJp.State.Ready) {
        NotoSerifJp.fontFamily(target)?.let { return JapaneseFace(it, FontWeight(target)) }
    }
    if (flags.miuiAdaptation) return JapaneseFace(FontFamily.Serif, serifStep(target))
    return JapaneseFace(FontFamily.Serif, FontWeight(target))
}

/**
 * A system family is resolved by the platform, and that is the only path that renders a heavier CJK
 * fallback: the platform synthesises it there. A typeface built from a font file cannot — relabelling
 * its weight leaves the outlines alone.
 *
 * The curve's nominal weight is quantised to a hundred because that is the granularity the platform
 * answers at: the serif fallback holds one weight, so everything above the platform's bold threshold
 * renders as the same synthesised bold and everything below it as regular.
 */
private fun serifStep(nominal: Int): FontWeight = FontWeight(((nominal / 100f).roundToInt() * 100).coerceIn(100, 1000))
