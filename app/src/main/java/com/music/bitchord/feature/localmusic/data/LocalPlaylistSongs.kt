package com.music.bitchord.feature.localmusic.data

import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import java.io.File

/** Resolves stored playlist IDs and recreates playable SAF/file references when needed. */
fun resolvePlaylistSongs(playlist: LocalPlaylist, librarySongs: List<Song>): List<Song> =
    playlist.songIds.mapNotNull { id ->
        val resolved = librarySongs.firstOrNull { song -> song.localUri == id || song.videoId == id }
            ?: id.takeIf { it.startsWith("content://") || it.startsWith("file://") }?.let { uriString ->
                val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                if (uri?.scheme == "file") {
                    val file = uri.path?.let(::File)
                    if (file?.isFile != true || !file.canRead()) return@let null
                }
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
        resolved?.takeUnless { song ->
            val localUri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (localUri?.scheme != "file") return@takeUnless false
            val file = localUri.path?.let(::File)
            file?.isFile != true || !file.canRead()
        }
    }
