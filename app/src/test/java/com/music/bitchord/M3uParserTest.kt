package com.music.bitchord

import com.music.bitchord.feature.localmusic.data.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    @Test
    fun `parses extended M3U names artists and entries`() {
        val parsed = M3uParser.parse(
            """#EXTM3U
#EXTINF:214,Artist One - A Song
Music/song.flac
#EXTINF:-1,Another Song
../Other/track.mp3
""".trimIndent(),
            "Road Trip.m3u",
        )

        assertEquals("Road Trip", parsed.suggestedName)
        assertEquals(2, parsed.entries.size)
        assertEquals(214L, parsed.entries[0].durationSeconds)
        assertEquals("A Song", parsed.entries[0].title)
        assertEquals("Artist One", parsed.entries[0].artist)
        assertEquals("Music/song.flac", parsed.entries[0].location)
        assertEquals("Another Song", parsed.entries[1].title)
        assertNull(parsed.entries[1].artist)
        assertNull(parsed.entries[1].durationSeconds)
    }

    @Test
    fun `parses UTF 8 M3U8 content and ignores comments and blank lines`() {
        val parsed = M3uParser.parse(
            "\uFEFF#EXTM3U\r\n#EXTINF:0,宇多田ヒカル - First Love\r\n\r\n音楽/First Love.flac\r\n# comment\r\n",
            "日本語.m3u8",
        )

        assertEquals("日本語", parsed.suggestedName)
        assertEquals(1, parsed.entries.size)
        assertEquals("First Love", parsed.entries.single().title)
        assertEquals("宇多田ヒカル", parsed.entries.single().artist)
        assertEquals("音楽/First Love.flac", parsed.entries.single().location)
    }

    @Test
    fun `empty and metadata only files have no entries`() {
        assertTrue(M3uParser.parse("", "empty.m3u").entries.isEmpty())
        assertTrue(M3uParser.parse("#EXTM3U\n#EXTINF:1,Title\n", "empty.m3u8").entries.isEmpty())
    }

    @Test
    fun `resolves relative paths against playlist directory`() {
        assertEquals(
            "/storage/emulated/0/Music/song.flac",
            M3uParser.resolveRelativePath("/storage/emulated/0/Playlists", "../Music/song.flac"),
        )
        assertEquals(
            "/storage/emulated/0/Music/song.flac",
            M3uParser.resolveRelativePath("/storage/emulated/0/Playlists", "/storage/emulated/0/Music/song.flac"),
        )
        assertNull(M3uParser.resolveRelativePath(null, "relative/song.flac"))
    }
}
