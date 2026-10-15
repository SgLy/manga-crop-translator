package me.sgly.translator.ui.theme.jp

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList

/**
 * Japanese text asks for the ja family, or the system answers kanji with Chinese glyph variants: the
 * `serif` family is Latin-only, so its kanji come from the `fallbackFor="serif"` entries, and which of
 * those is picked follows the locale. That is also why the standard family works for serif and not for
 * sans-serif: the primary sans family covers every kanji, so the locale is never consulted.
 */
private val JapaneseLocale = LocaleList("ja")

/**
 * The one entry point for Japanese text: the ambient style with a serif family, Japanese glyphs and the
 * weight the caller asked for. Callers hand it to `style` and never pick a family, a locale or a weight
 * themselves:
 *
 * ```
 * Text(text = lemma, style = japaneseTextStyle(FontWeight.Normal))
 * ```
 */
@Composable
fun japaneseTextStyle(weight: FontWeight): TextStyle =
    LocalTextStyle.current.merge(
        TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = weight,
            localeList = JapaneseLocale,
        ),
    )
