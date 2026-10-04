package com.music.bitchord.feature.localmusic.data

import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist

/** Resolves stored playlist IDs and recreates playable SAF/file references when needed. */
fun resolvePlaylistSongs(playlist: LocalPlaylist, librarySongs: List<Song>): List<Song> =
    playlist.songIds.mapNotNull { id ->
        librarySongs.firstOrNull { song -> song.localUri == id || song.videoId == id }
            ?: id.takeIf { it.startsWith("content://") || it.startsWith("file://") }?.let { uriString ->
                val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                val fallbackName = uri?.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.', uri.lastPathSegment.orEmpty())
                    ?.takeIf(String::isNotBlank)
                    ?: "Unknown track"
                val metadata = playlist.songMetadata[id]
                Song(
                    videoId = id,
                    title = metadata?.title?.takeIf(String::isNotBlank) ?: fallbackName,
                    artist = metadata?.artist?.takeIf(String::isNotBlank) ?: "Unknown artist",
                    thumbnailUrl = null,
                    localUri = id,
                    localPath = uri?.takeIf { it.scheme == "file" }?.path,
                )
            }
    }
