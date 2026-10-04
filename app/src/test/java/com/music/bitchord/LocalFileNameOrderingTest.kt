package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localsearch.domain.LocalFileNameOrdering
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFileNameOrderingTest {
    @Test
    fun `folder tracks use natural filename order rather than metadata title`() {
        val songs = listOf(
            song("ten", "A title", "/music/10. ten.flac"),
            song("two", "Z title", "/music/2. two.flac"),
            song("one", "M title", "/music/1. one.flac"),
        )

        assertEquals(
            listOf("one", "two", "ten"),
            LocalFileNameOrdering.sort(songs).map(Song::videoId),
        )
    }

    @Test
    fun `multi disc filename numbers sort numerically`() {
        val songs = listOf(
            song("track10", "First", "/music/1-10. track.flac"),
            song("track2", "Second", "/music/1-2. track.flac"),
            song("track1", "Third", "/music/1-1. track.flac"),
        )

        assertEquals(
            listOf("track1", "track2", "track10"),
            LocalFileNameOrdering.sort(songs).map(Song::videoId),
        )
    }

    private fun song(id: String, title: String, path: String) = Song(
        videoId = id,
        title = title,
        artist = "Artist",
        thumbnailUrl = null,
        localPath = path,
    )
}
