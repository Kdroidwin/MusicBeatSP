package com.music.bitchord.feature.localmusic.data

import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist

/** Collection ordering operations; playlist song IDs and their order are left untouched. */
object PlaylistOrdering {
    fun move(playlists: List<LocalPlaylist>, playlistId: String, offset: Int): List<LocalPlaylist> {
        if (offset == 0) return playlists
        val from = playlists.indexOfFirst { it.id == playlistId }
        if (from < 0) return playlists
        val to = (from + offset).coerceIn(0, playlists.lastIndex)
        if (to == from) return playlists
        return playlists.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Moves a song ID within one playlist while keeping the playlist record intact. */
    fun moveSong(playlist: LocalPlaylist, from: Int, to: Int): LocalPlaylist {
        if (from !in playlist.songIds.indices || to !in playlist.songIds.indices || from == to) return playlist
        return playlist.copy(songIds = playlist.songIds.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Applies visible song order and retains any stored entries that were not resolvable in the UI. */
    fun setSongOrder(playlist: LocalPlaylist, orderedSongIds: List<String>): LocalPlaylist {
        val originalIds = playlist.songIds
        val requestedIds = orderedSongIds.filter { it in originalIds }.distinct()
        val requestedSet = requestedIds.toHashSet()
        val finalIds = requestedIds + originalIds.filterNot(requestedSet::contains)
        return if (finalIds == originalIds) playlist else playlist.copy(songIds = finalIds)
    }
}
