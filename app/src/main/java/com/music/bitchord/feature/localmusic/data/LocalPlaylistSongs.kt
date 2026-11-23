package com.music.bitchord.feature.localmusic.data

import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import com.music.bitchord.feature.localsongactions.domain.model.LocalPlayStats
import java.io.File

/** Resolves stored playlist IDs and recreates playable SAF/file references when needed. */
fun resolvePlaylistSongs(
    playlist: LocalPlaylist,
    librarySongs: List<Song>,
    playStats: Map<String, LocalPlayStats> = emptyMap(),
    favoriteIds: Set<String> = emptySet(),
): List<Song> {
    // Build one lookup for this resolution instead of scanning the entire
    // MediaStore list once for every persisted playlist entry. A playlist
    // with hundreds of tracks otherwise made opening or refreshing it
    // quadratic in the size of the local library. Smart playlists inspect
    // the library directly and do not need the URI/path indexes.
    val resolver = if (playlist.smartDefinition == null) LocalPlaylistSongResolver(librarySongs) else null
    return resolvePlaylistSongsInternal(
        playlist = playlist,
        librarySongs = librarySongs,
        playStats = playStats,
        favoriteIds = favoriteIds,
        resolver = resolver,
    )
}

/**
 * Resolves several playlists against one library snapshot without rescanning
 * the whole library for every saved track ID. Artist pages use this when
 * checking which local playlists contain the selected artist.
 */
class LocalPlaylistSongResolver(private val librarySongs: List<Song>) {
    private val byLocalUri = HashMap<String, Song>(librarySongs.size)
    private val byVideoId = HashMap<String, Song>(librarySongs.size)
    private val byPath = HashMap<String, Song>(librarySongs.size)

    init {
        for (song in librarySongs) {
            song.localUri?.let { if (it !in byLocalUri) byLocalUri[it] = song }
            if (song.videoId !in byVideoId) byVideoId[song.videoId] = song
            val path = normalizeLocalPath(song.localPath) ?: normalizeLocalPath(filePathFromUri(song.localUri))
            if (path != null && path !in byPath) byPath[path] = song
        }
    }

    fun resolve(
        playlist: LocalPlaylist,
        playStats: Map<String, LocalPlayStats> = emptyMap(),
        favoriteIds: Set<String> = emptySet(),
    ): List<Song> = resolvePlaylistSongsInternal(
        playlist = playlist,
        librarySongs = librarySongs,
        playStats = playStats,
        favoriteIds = favoriteIds,
        resolver = this,
    )

    internal fun byLocalUri(uri: String): Song? = byLocalUri[uri]
    internal fun byVideoId(id: String): Song? = byVideoId[id]
    internal fun byPath(path: String): Song? = byPath[path]
}

private fun resolvePlaylistSongsInternal(
    playlist: LocalPlaylist,
    librarySongs: List<Song>,
    playStats: Map<String, LocalPlayStats>,
    favoriteIds: Set<String>,
    resolver: LocalPlaylistSongResolver?,
): List<Song> {
    playlist.smartDefinition?.let { definition ->
        return resolveSmartPlaylistSongs(definition, librarySongs, playStats, favoriteIds)
    }
    return playlist.songIds.mapNotNull { id ->
        val editedMetadata = playlist.editedSongMetadata[id]
        val storedMetadata = editedMetadata ?: playlist.songMetadata[id]
        val pathHint = normalizeLocalPath(storedMetadata?.localPath)
            ?: normalizeLocalPath(filePathFromUri(id))
        // MediaStore can issue a new content URI after a rescan or tag edit.
        // The playlist keeps the old URI, so recover its current library row by
        // the stable file path recorded when the playlist entry was saved.
        val pathMatch = if (resolver == null) {
            pathHint?.let { expectedPath ->
                librarySongs.firstOrNull { song ->
                    (normalizeLocalPath(song.localPath) ?: normalizeLocalPath(filePathFromUri(song.localUri))) == expectedPath
                }
            }
        } else {
            pathHint?.let(resolver::byPath)
        }
        val localUriMatch = if (resolver == null) {
            librarySongs.firstOrNull { song -> song.localUri == id }
        } else {
            resolver.byLocalUri(id)
        }
        val videoIdMatch = if (resolver == null) {
            librarySongs.firstOrNull { song -> song.videoId == id }
        } else {
            resolver.byVideoId(id)
        }
        val resolved = localUriMatch
            ?: videoIdMatch
            ?: pathMatch
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
                val metadata = editedMetadata ?: playlist.songMetadata[id]
                Song(
                    videoId = id,
                    title = metadata?.title?.takeIf(String::isNotBlank) ?: fallbackName,
                    artist = metadata?.artist?.takeIf(String::isNotBlank) ?: "Unknown artist",
                    thumbnailUrl = metadata?.thumbnailUrl,
                    durationText = metadata?.durationText,
                    artistId = metadata?.artistId,
                    albumId = metadata?.albumId,
                    albumName = metadata?.albumName,
                    albumArtist = metadata?.albumArtist,
                    isVideo = metadata?.isVideo ?: false,
                    isVideoOrigin = metadata?.isVideoOrigin ?: false,
                    localUri = id,
                    localPath = uri?.takeIf { it.scheme == "file" }?.path,
                    localFolderPath = metadata?.localFolderPath,
                )
            }
            // Remote catalogue rows (including YouTube Music) are not in the
            // device MediaStore list. Preserve their playable source ID and
            // saved display metadata so a local playlist does not silently
            // lose them when opened without a signed-in YouTube account.
            ?: (editedMetadata ?: playlist.songMetadata[id])?.let { metadata ->
                Song(
                    videoId = id,
                    title = metadata.title.takeIf(String::isNotBlank) ?: "Unknown track",
                    artist = metadata.artist.takeIf(String::isNotBlank) ?: "Unknown artist",
                    thumbnailUrl = metadata.thumbnailUrl,
                    durationText = metadata.durationText,
                    artistId = metadata.artistId,
                    albumId = metadata.albumId,
                    albumName = metadata.albumName,
                    albumArtist = metadata.albumArtist,
                    isVideo = metadata.isVideo,
                    isVideoOrigin = metadata.isVideoOrigin,
                )
            }
        val refreshed = if (editedMetadata == null || resolved == null) {
            resolved
        } else {
            resolved.copy(
                title = editedMetadata.title.takeIf(String::isNotBlank) ?: resolved.title,
                artist = editedMetadata.artist.takeIf(String::isNotBlank) ?: resolved.artist,
                albumName = editedMetadata.albumName,
                albumArtist = editedMetadata.albumArtist,
            )
        }
        refreshed?.takeUnless { song ->
            val localUri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (localUri?.scheme != "file") return@takeUnless false
            val file = localUri.path?.let(::File)
            file?.isFile != true || !file.canRead()
        }
    }
}

private fun normalizeLocalPath(path: String?): String? = path
    ?.replace('\\', '/')
    ?.trim()
    ?.trimEnd('/')
    ?.takeIf(String::isNotBlank)

private fun filePathFromUri(uriString: String?): String? = uriString
    ?.takeIf { it.startsWith("file://") }
    ?.let { runCatching { Uri.parse(it).path }.getOrNull() }
