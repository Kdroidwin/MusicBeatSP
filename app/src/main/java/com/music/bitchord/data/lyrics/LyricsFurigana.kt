package com.music.bitchord.data.lyrics

/**
 * Reads the inline furigana notation used by lyric sources and local files:
 * `漢字((かんじ))` (and the equivalent full-width `漢字（（かんじ））`).
 *
 * This parser is shared by the lyric renderer and the background-vocal
 * splitter. Keeping the notation intact until it reaches the renderer avoids
 * interpreting its closing parentheses as a backing-vocal annotation.
 */
internal object LyricsFurigana {
    internal data class Annotation(
        val sourceStart: Int,
        val sourceEndExclusive: Int,
        val base: String,
        val reading: String,
    )

    fun annotations(source: String): List<Annotation> {
        if (source.length < 6) return emptyList()
        val found = mutableListOf<Annotation>()
        var cursor = 0
        while (cursor < source.length) {
            val baseStart = cursor
            var codePoint = source.codePointAt(cursor)
            if (!isKanjiBase(codePoint)) {
                cursor += Character.charCount(codePoint)
                continue
            }

            cursor += Character.charCount(codePoint)
            while (cursor < source.length) {
                codePoint = source.codePointAt(cursor)
                if (!isKanjiBase(codePoint)) break
                cursor += Character.charCount(codePoint)
            }
            val baseEnd = cursor
            val delimiters = when {
                source.startsWith("((", baseEnd) -> "((" to "))"
                source.startsWith("（（", baseEnd) -> "（（" to "））"
                else -> continue
            }
            val readingStart = baseEnd + delimiters.first.length
            val closing = source.indexOf(delimiters.second, readingStart)
            if (closing < 0) continue
            val reading = source.substring(readingStart, closing)
            if (reading.isBlank() || reading.any(::isParenthesis)) continue

            found += Annotation(
                sourceStart = baseStart,
                sourceEndExclusive = closing + delimiters.second.length,
                base = source.substring(baseStart, baseEnd),
                reading = reading,
            )
            cursor = closing + delimiters.second.length
        }
        return found
    }

    fun hasTrailingAnnotation(source: String): Boolean {
        val trimmedEnd = source.trimEnd().length
        return annotations(source).lastOrNull()?.sourceEndExclusive == trimmedEnd
    }

    private fun isKanjiBase(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ||
            codePoint == 0x3005 || // 々
            codePoint == 0x30F5 || // ヵ
            codePoint == 0x30F6    // ヶ

    private fun isParenthesis(char: Char): Boolean =
        char == '(' || char == ')' || char == '（' || char == '）'
}
