package me.sgly.translator.sheet

import me.sgly.translator.api.TranslationWord

internal sealed interface SourceToken {
    data class Plain(
        val text: String,
    ) : SourceToken

    data class Annotated(
        val word: TranslationWord,
        val surface: String,
    ) : SourceToken
}

/**
 * Splits [segment] around [words]: each word is located at its first occurrence after the
 * previous match, so the annotated pieces keep the original reading order.
 */
internal fun tokenizeSegment(
    segment: String,
    words: List<TranslationWord>,
): List<SourceToken> {
    val tokens = mutableListOf<SourceToken>()
    var cursor = 0
    for (word in words) {
        if (word.word.isEmpty()) continue
        val index = segment.indexOf(word.word, cursor)
        if (index < 0) continue
        if (index > cursor) tokens += SourceToken.Plain(segment.substring(cursor, index))
        tokens += SourceToken.Annotated(word, segment.substring(index, index + word.word.length))
        cursor = index + word.word.length
    }
    if (cursor < segment.length) tokens += SourceToken.Plain(segment.substring(cursor))
    return tokens
}

/**
 * Breaks plain text into pieces small enough to wrap inside a [FlowRow]: runs of ASCII
 * letters/digits stay glued together, everything else goes character by character (which is how
 * Japanese breaks anyway). Trailing whitespace sticks to the piece before it.
 */
internal fun chunkPlainText(text: String): List<String> =
    buildList {
        var i = 0
        while (i < text.length) {
            val start = i
            if (text[i].isAsciiWordChar()) {
                while (i < text.length && text[i].isAsciiWordChar()) i++
            } else {
                i++
            }
            while (i < text.length && text[i].isWhitespace()) i++
            add(text.substring(start, i))
        }
    }

internal fun Char.isAsciiWordChar(): Boolean = isLetterOrDigit() && code < 0x80
