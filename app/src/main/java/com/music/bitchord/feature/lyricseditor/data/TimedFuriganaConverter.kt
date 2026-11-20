package com.music.bitchord.feature.lyricseditor.data

/** Converts timestamped kana readings after kanji into the editor's ((reading)) form. */
object TimedFuriganaConverter {
    data class Result(val text: String, val convertedCount: Int)

    private const val TIME_TAG = "<\\d{1,3}:\\d{2}\\.\\d{1,3}>"
    private val timedParentheticalReading = Regex(
        "($TIME_TAG)\\((?<body>(?:$TIME_TAG[^<>()\\r\\n]+)+)($TIME_TAG)\\)",
    )
    private val timeTag = Regex(TIME_TAG)

    fun convert(text: String): Result {
        var convertedCount = 0
        val converted = timedParentheticalReading.replace(text) { match ->
            val body = match.groups["body"]?.value.orEmpty()
            val startTag = timeTag.find(body)?.value
            val endTag = match.groupValues[3]
            val openingTagIndex = match.range.first

            if (startTag == null || !isKanjiImmediatelyBefore(text, openingTagIndex) || !isKanaReading(body)) {
                match.value
            } else {
                convertedCount++
                "${match.groupValues[1]}($startTag($body$endTag)$endTag)"
            }
        }
        return Result(converted, convertedCount)
    }

    private fun isKanjiImmediatelyBefore(text: String, beforeIndex: Int): Boolean {
        if (beforeIndex <= 0) return false
        val codePoint = Character.codePointBefore(text, beforeIndex)
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
    }

    private fun isKanaReading(timestampedReading: String): Boolean {
        val plainText = timeTag.replace(timestampedReading, "")
        if (plainText.isEmpty()) return false

        var index = 0
        var hasKana = false
        var previousWasKana = false
        while (index < plainText.length) {
            val codePoint = plainText.codePointAt(index)
            val script = Character.UnicodeScript.of(codePoint)
            val isKana = script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA ||
                codePoint == 0x30FC // Japanese prolonged sound mark.
            val isCombiningMark = (Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(codePoint) == Character.COMBINING_SPACING_MARK.toInt()) && previousWasKana

            if (!isKana && !isCombiningMark) return false
            if (isKana) hasKana = true
            previousWasKana = isKana || isCombiningMark
            index += Character.charCount(codePoint)
        }
        return hasKana
    }
}
