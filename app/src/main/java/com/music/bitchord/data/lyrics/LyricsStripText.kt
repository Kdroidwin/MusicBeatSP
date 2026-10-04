package com.music.bitchord.data.lyrics

/** Helpers for choosing actual lyric text for the compact player strip. */
object LyricsStripText {
    private val metadataLine = Regex(
        """(?i)^\s*(?:\[(?:ar|ti|al|au|by|re|ve|length|offset|kana|la|tool|id)\s*:.*]|(?:artist|title|album|lyrics by)\s*:.*)\s*$""",
    )

    fun isLyricLine(line: LyricLine): Boolean =
        !line.isGap && line.text.isNotBlank() &&
            !LrcParser.isSectionHeader(line.text) && !metadataLine.matches(line.text)

    fun firstLyricLine(lines: List<LyricLine>): LyricLine? = lines.firstOrNull(::isLyricLine)

    fun firstLyricIndex(lines: List<LyricLine>): Int = lines.indexOfFirst(::isLyricLine)

    fun shouldPreviewFirstLyric(currentIndex: Int, firstLyricIndex: Int): Boolean =
        firstLyricIndex >= 0 && currentIndex < firstLyricIndex

    /** Returns null for a generated status when the user has hidden status copy. */
    fun statusTextOrNull(text: String, hideStatusText: Boolean): String? =
        text.takeUnless { hideStatusText }
}
