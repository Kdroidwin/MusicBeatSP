package com.music.bitchord.data.lyrics

/**
 * Reads the app's explicit inline furigana notation: `漢字((かんじ))`.
 * Parentheses that are not this exact ASCII double pair remain lyric text.
 */
internal object LyricsFurigana {
    internal data class Annotation(
        val sourceStart: Int,
        val sourceEndExclusive: Int,
        val base: String,
        val reading: String,
    )

    fun annotations(source: String): List<Annotation> {
        if (source.length < 5) return emptyList()
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
            // Japanese bases commonly include kana suffixes: 輝く((かがやく)).
            while (cursor < source.length) {
                codePoint = source.codePointAt(cursor)
                if (!isKanaBase(codePoint)) break
                cursor += Character.charCount(codePoint)
            }
            val baseEnd = cursor
            if (!source.startsWith("((", baseEnd)) continue
            val readingStart = baseEnd + 2
            val closing = source.indexOf("))", readingStart)
            if (closing < 0) continue
            val reading = source.substring(readingStart, closing)
            if (reading.isBlank() || reading.any(::isParenthesis)) continue

            found += Annotation(
                sourceStart = baseStart,
                sourceEndExclusive = closing + 2,
                base = source.substring(baseStart, baseEnd),
                reading = reading,
            )
            cursor = closing + 2
        }
        return found
    }

    /**
     * A single pair after Japanese text is valid lyric punctuation, not a
     * backing-vocal marker. Keep it inline so it remains an ordinary bracket.
     */
    fun hasPlainJapaneseParenthetical(source: String): Boolean {
        var cursor = 0
        while (cursor < source.length) {
            var codePoint = source.codePointAt(cursor)
            if (!isKanjiBase(codePoint)) {
                cursor += Character.charCount(codePoint)
                continue
            }
            cursor += Character.charCount(codePoint)
            while (cursor < source.length) {
                codePoint = source.codePointAt(cursor)
                if (!isKanjiBase(codePoint) && !isKanaBase(codePoint)) break
                cursor += Character.charCount(codePoint)
            }
            val baseEnd = cursor
            if (source.startsWith("((", baseEnd)) {
                // The double pair is reserved for furigana, even if its
                // contents are malformed. Advance past the opener so an
                // invalid annotation cannot trap this scan in a loop.
                cursor = baseEnd + 2
                continue
            }
            val (open, close) = when {
                source.startsWith("(", baseEnd) -> '(' to ')'
                source.startsWith("（", baseEnd) -> '（' to '）'
                else -> continue
            }
            val closeIndex = source.indexOf(close, baseEnd + 1)
            if (closeIndex > baseEnd + 1) {
                val reading = source.substring(baseEnd + 1, closeIndex)
                if (reading.isHiraganaReading() && !reading.any(::isParenthesis)) return true
            }
            // Do not treat a second opener as evidence of single-pair ruby.
            if (source.getOrNull(baseEnd) == open && source.getOrNull(baseEnd + 1) == open) {
                cursor = baseEnd + 2
            }
        }
        return false
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
