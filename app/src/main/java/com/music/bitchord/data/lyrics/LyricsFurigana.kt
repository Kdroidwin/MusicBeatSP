package com.music.bitchord.data.lyrics

/**
 * Reads inline furigana notation used by lyric sources and local files:
 * `漢字((かんじ))` (and its full-width equivalent). It also accepts a
 * single-parenthesis, hiragana-only reading such as `永遠(とわ)`, which can
 * result when an older lyric formatter removes one pair of ruby markers.
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
        if (source.length < 4) return emptyList()
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
            // Japanese ruby bases often include kana after the kanji:
            // `輝く((かがやく))`, `姉さん((ねえさん))`. Without this suffix,
            // the lyric pipeline mistakes the final `((reading))` for a
            // parenthesized backing vocal before the renderer can see it.
            while (cursor < source.length) {
                codePoint = source.codePointAt(cursor)
                if (!isKanaBase(codePoint)) break
                cursor += Character.charCount(codePoint)
            }
            val baseEnd = cursor
            val delimiters = when {
                source.startsWith("((", baseEnd) -> "((" to "))"
                source.startsWith("（（", baseEnd) -> "（（" to "））"
                source.startsWith("(", baseEnd) -> "(" to ")"
                source.startsWith("（", baseEnd) -> "（" to "）"
                else -> continue
            }
            val readingStart = baseEnd + delimiters.first.length
            val closing = source.indexOf(delimiters.second, readingStart)
            if (closing < 0) continue
            val reading = source.substring(readingStart, closing)
            if (reading.isBlank() || reading.any(::isParenthesis)) continue
            // Single parentheses are ambiguous with asides. A Japanese
            // furigana reading is safe to recover here only when it is all
            // hiragana; preserve katakana annotations like `歌手(ゲスト)`.
            if (delimiters.first.length == 1 && !reading.isHiraganaReading()) continue
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

    private fun isKanaBase(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        else -> codePoint == 0x30FC // ー
    }

    private fun String.isHiraganaReading(): Boolean =
        isNotEmpty() && all { char ->
            Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HIRAGANA ||
                Character.getType(char).toByte() == Character.NON_SPACING_MARK
        }

    private fun isParenthesis(char: Char): Boolean =
        char == '(' || char == ')' || char == '（' || char == '）'
}
