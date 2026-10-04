package com.music.bitchord

import com.music.bitchord.feature.localmusic.data.PlaylistOrdering
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderingTest {
    @Test
    fun `moves playlist records without changing track order`() {
        val original = listOf(
            LocalPlaylist("a", "A", songIds = listOf("a1", "a2")),
            LocalPlaylist("b", "B", songIds = listOf("b2", "b1")),
            LocalPlaylist("c", "C", songIds = listOf("c1")),
        )

        val moved = PlaylistOrdering.move(original, "c", -2)

        assertEquals(listOf("c", "a", "b"), moved.map(LocalPlaylist::id))
        assertEquals(listOf("a1", "a2"), moved[1].songIds)
        assertEquals(listOf("b2", "b1"), moved[2].songIds)
        assertEquals(original, PlaylistOrdering.move(original, "unknown", 1))
        assertEquals(original, PlaylistOrdering.move(original, "a", -1))
    }

    @Test
    fun `moves tracks within one playlist without changing other playlist data`() {
        val playlist = LocalPlaylist(
            id = "mix",
            name = "Mix",
            songIds = listOf("first", "second", "third"),
            coverUrl = "cover",
        )

        val moved = PlaylistOrdering.moveSong(playlist, from = 0, to = 2)

        assertEquals(listOf("second", "third", "first"), moved.songIds)
        assertEquals("mix", moved.id)
        assertEquals("Mix", moved.name)
        assertEquals("cover", moved.coverUrl)
    }

    @Test
    fun `song order update preserves unresolved entries and imported metadata`() {
        val playlist = LocalPlaylist(
            id = "mix",
            name = "Mix",
            songIds = listOf("first", "unavailable", "second"),
            songMetadata = mapOf("unavailable" to com.music.bitchord.feature.localmusic.domain.model.LocalPlaylistSongMetadata("Lost track", "Artist")),
        )

        val reordered = PlaylistOrdering.setSongOrder(playlist, listOf("second", "first"))

        assertEquals(listOf("second", "first", "unavailable"), reordered.songIds)
        assertEquals(playlist.songMetadata, reordered.songMetadata)
        assertEquals(playlist.copy(songIds = listOf("second", "first", "unavailable")), reordered)
        assertEquals(reordered, PlaylistOrdering.setSongOrder(reordered, reordered.songIds))
    }
}
