package com.music.bitchord.data.lyrics.providers

import com.music.bitchord.data.lyrics.LyricLine

/**
 * Generates estimated LRC timestamps for unsynced plain-text lyrics using a
 * character-weighted acoustic duration distribution model (ported from LyricsForTryptify).
 *
 * When only plain lyrics are available (e.g. from Uta-Net, UtaTen, J-Lyric, Genius)
 * and CTC auto-alignment is enabled, this assigns natural line timestamps proportional
 * to the spoken/sung syllable weight of each line.
 */
object CtcLrcGenerator {

    private val TIMESTAMP_CHECK = Regex("""\[\d{2}:\d{2}[.:]\d{2,3}]""")
    private val FURIGANA_STRIP = Regex("""(?:\(\([ぁ-ゖァ-ヺー]+\)\)|（（[ぁ-ゖァ-ヺー]+））)""")

    fun generateAutoSyncedLrc(plainText: String, durationSeconds: Int): String {
        if (plainText.isBlank()) return ""
        if (TIMESTAMP_CHECK.containsMatchIn(plainText)) return plainText

        val rawLines = plainText.lines().map { it.trim() }
        val nonEmptyCount = rawLines.count { it.isNotEmpty() }
        if (nonEmptyCount == 0) return plainText

        val totalSec = if (durationSeconds > 20) durationSeconds.toDouble() else 210.0
        val startOffsetSec = (totalSec * 0.06).coerceIn(3.0, 14.0)
        val endOffsetSec = (totalSec - (totalSec * 0.07).coerceIn(5.0, 18.0)).coerceAtLeast(startOffsetSec + 10.0)
        val usableWindowSec = (endOffsetSec - startOffsetSec).coerceAtLeast(15.0)

        val weights = rawLines.map { line ->
            if (line.isEmpty()) {
                0.65 // Instrumental breathing gap between stanzas
            } else {
                lineAcousticWeight(line)
            }
        }

        val totalWeight = weights.sum().coerceAtLeast(1.0)
        var currentSec = startOffsetSec
        val sb = StringBuilder()

        for (i in rawLines.indices) {
            val line = rawLines[i]
            val stepSec = (weights[i] / totalWeight) * usableWindowSec
            if (line.isNotEmpty()) {
                val totalMs = (currentSec * 1000.0).toLong().coerceAtLeast(0L)
                val min = (totalMs / 60000)
                val sec = (totalMs % 60000) / 1000
                val cs = (totalMs % 1000) / 10
                sb.append('[')
                    .append(min.toString().padStart(2, '0'))
                    .append(':')
                    .append(sec.toString().padStart(2, '0'))
                    .append('.')
                    .append(cs.toString().padStart(2, '0'))
                    .append(']')
                    .append(line)
                    .append('\n')
            }
            currentSec += stepSec
        }

        return sb.toString().trimEnd()
    }

    /**
     * Aligns unsynced [LyricLine]s (where all `timeMs == 0L`) across [durationMs].
     */
    fun alignLines(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
        if (lines.isEmpty()) return lines
        if (lines.any { it.timeMs > 0L }) return lines

        val totalSec = if (durationMs > 20_000L) durationMs / 1000.0 else 210.0
        val startOffsetSec = (totalSec * 0.06).coerceIn(3.0, 14.0)
        val endOffsetSec = (totalSec - (totalSec * 0.07).coerceIn(5.0, 18.0)).coerceAtLeast(startOffsetSec + 10.0)
        val usableWindowSec = (endOffsetSec - startOffsetSec).coerceAtLeast(15.0)

        val weights = lines.map { line ->
            if (line.text.isBlank()) 0.65 else lineAcousticWeight(line.text)
        }
        val totalWeight = weights.sum().coerceAtLeast(1.0)
        var currentSec = startOffsetSec

        return lines.mapIndexed { index, line ->
            val timeMs = (currentSec * 1000.0).toLong().coerceAtLeast(100L)
            val stepSec = (weights[index] / totalWeight) * usableWindowSec
            currentSec += stepSec
            val endMs = (currentSec * 1000.0).toLong().coerceAtLeast(timeMs + 500L)
            line.copy(timeMs = timeMs, sungUntilMs = endMs)
        }
    }

    private fun lineAcousticWeight(line: String): Double {
        // Strip inline furigana annotations so kanji + reading isn't double-counted
        val surface = FURIGANA_STRIP.replace(line, "")
        val cjkCount = surface.count { ch ->
            Character.UnicodeScript.of(ch.code) in setOf(
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
            )
        }
        val latinWords = surface.split(Regex("""\s+""")).count { it.any { c -> c.isLetterOrDigit() } }
        val effectiveUnits = cjkCount * 0.45 + latinWords * 0.55
        return (1.0 + effectiveUnits * 0.18).coerceIn(1.0, 4.5)
    }
}
