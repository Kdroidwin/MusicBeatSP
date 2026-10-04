package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsStripText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsStripTextTest {
    @Test
    fun `first preview skips gaps section headers and metadata`() {
        val lines = listOf(
            LyricLine(0, ""),
            LyricLine(500, "[Chorus]"),
            LyricLine(900, "[ti:Track title]"),
            LyricLine(1_200, "   "),
            LyricLine(2_000, "First sung line"),
        )

        assertEquals("First sung line", LyricsStripText.firstLyricLine(lines)?.text)
        assertEquals(4, LyricsStripText.firstLyricIndex(lines))
    }

    @Test
    fun `first line is previewed before its timestamp and empty lyrics stay empty`() {
        assertTrue(LyricsStripText.shouldPreviewFirstLyric(-1, 0))
        assertTrue(LyricsStripText.shouldPreviewFirstLyric(1, 3))
        assertFalse(LyricsStripText.shouldPreviewFirstLyric(3, 3))
        assertFalse(LyricsStripText.shouldPreviewFirstLyric(0, -1))
        assertNull(LyricsStripText.firstLyricLine(emptyList()))
    }

    @Test
    fun `generated status text can be hidden without changing lyric text`() {
        assertEquals("Instrumental", LyricsStripText.statusTextOrNull("Instrumental", false))
        assertNull(LyricsStripText.statusTextOrNull("Instrumental", true))
        val lyricBody = "A lyric line"
        val shownText = lyricBody.ifBlank {
            LyricsStripText.statusTextOrNull("Instrumental", true).orEmpty()
        }
        assertEquals("A lyric line", shownText)
    }
}
