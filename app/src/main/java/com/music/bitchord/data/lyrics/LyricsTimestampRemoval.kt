package com.music.bitchord.data.lyrics

/** Removes only LRC/inline timing tokens, leaving lyric text and metadata intact. */
object LyricsTimestampRemoval {
    private val timestampToken = Regex(
        """\[(?:\d{1,3}:)?\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]|<(?:\d{1,3}:)?\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""",
    )

    fun remove(text: String): Result {
        var count = 0
        val cleaned = timestampToken.replace(text) {
            count++
            ""
        }
        return Result(cleaned, count)
    }

    data class Result(val text: String, val removedCount: Int)
}
