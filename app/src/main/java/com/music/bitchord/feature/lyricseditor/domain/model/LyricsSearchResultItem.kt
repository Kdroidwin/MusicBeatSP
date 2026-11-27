package com.music.bitchord.feature.lyricseditor.domain.model

import java.text.BreakIterator
import java.util.Locale

data class LyricsSearchResultItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int = 0,
    val provider: String,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val matchScorePercent: Int? = null,
) {
    val hasSynced: Boolean get() = !syncedLyrics.isNullOrBlank()
    val hasPlain: Boolean get() = !plainLyrics.isNullOrBlank()

    /** True when the timestamps in the result mostly advance one glyph at a time. */
    val isCharacterSynced: Boolean get() = CharacterSyncDetector.matches(syncedLyrics)

    val formattedDuration: String get() {
        if (durationSeconds <= 0) return ""
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    val formatBadge: String get() = when {
        hasSynced && hasPlain -> "Synced & Plain"
        hasSynced -> "Synced (LRC)"
        hasPlain -> "Plain Text"
        else -> "Lyrics"
    }
}

private object CharacterSyncDetector {
    private val wordRun = Regex("""<\d{1,3}:\d{2}[.:]\d{1,3}>([^<\r\n]*)""")
    private val lineRun = Regex("""^\s*\[\d{1,3}:\d{2}[.:]\d{1,3}](.*)$""")

    fun matches(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false

        val enhancedRuns = wordRun.findAll(raw)
            .map { it.groupValues[1] }
            .filter { it.any { char -> !char.isWhitespace() } }
            .toList()
        if (looksCharacterTimed(enhancedRuns)) return true

        // Some providers store one timestamped glyph per LRC line instead of
        // using enhanced (<mm:ss.xx>) timestamps within each row.
        val lineRuns = raw.lineSequence()
            .mapNotNull { lineRun.matchEntire(it)?.groupValues?.get(1) }
            .filter { it.any { char -> !char.isWhitespace() } }
            .toList()
        return lineRuns.size >= MIN_CHARACTER_RUNS &&
            lineRuns.count { graphemeCount(it) == 1 }.toDouble() / lineRuns.size >= SINGLE_GLYPH_RATIO
    }

    private fun looksCharacterTimed(runs: List<String>): Boolean {
        if (runs.size < MIN_CHARACTER_RUNS) return false
        val counts = runs.map(::graphemeCount)
        val singleGlyphRuns = counts.count { it == 1 }
        val totalGlyphs = counts.sum()
        return totalGlyphs > 0 &&
            singleGlyphRuns.toDouble() / counts.size >= SINGLE_GLYPH_RATIO &&
            singleGlyphRuns.toDouble() / totalGlyphs >= SINGLE_GLYPH_COVERAGE
    }

    private fun graphemeCount(value: String): Int {
        val visible = value.filterNot(Char::isWhitespace)
        if (visible.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(visible)
        var count = 0
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) break
            count++
            boundary = next
        }
        return count
    }

    private const val MIN_CHARACTER_RUNS = 8
    private const val SINGLE_GLYPH_RATIO = 0.75
    private const val SINGLE_GLYPH_COVERAGE = 0.65
}
