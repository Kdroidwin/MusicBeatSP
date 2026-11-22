package com.music.bitchord.feature.localmusic.data

import android.net.Uri
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** One-shot in-app folder navigation requests from places such as Now Playing. */
object FolderNavigationRequests {
    data class Request(val id: Long, val path: String)

    private val ids = AtomicLong(0L)
    private val _pending = MutableStateFlow<Request?>(null)
    val pending = _pending.asStateFlow()

    fun folderPathFor(song: Song): String? = song.localFolderPath?.takeIf(String::isNotBlank)
        ?: localPath(song)?.substringBeforeLast('/')?.takeIf(String::isNotBlank)

    /**
     * Playlist rows can retain an older MediaStore URI than the library row
     * for the same file. Resolve by URI first, then the actual file path, so
     * player actions still have the canonical folder metadata in that case.
     */
    fun resolveLocalSong(song: Song, candidates: List<Song>): Song? {
        val requestedUri = song.localUri?.trim()?.takeIf(String::isNotBlank)
        requestedUri?.let { uri ->
            candidates.firstOrNull { it.localUri?.trim() == uri }?.let { return it }
        }

        val requestedPath = localPath(song)
        if (requestedPath != null) {
            candidates.firstOrNull { localPath(it) == requestedPath }?.let { return it }
        }

        // Some imported local playlists have a content URI but no filesystem
        // path. In that case retain the old local-ID resolution, guarded so a
        // remote-only YouTube row cannot be mistaken for a device file.
        val hasLocalIdentity = !song.localUri.isNullOrBlank() ||
            !song.localPath.isNullOrBlank() || song.videoId.startsWith("local:")
        if (!hasLocalIdentity) return null
        return candidates.firstOrNull { candidate ->
            candidate.videoId == song.videoId &&
                (!candidate.localUri.isNullOrBlank() || !candidate.localPath.isNullOrBlank())
        }
    }

    private fun localPath(song: Song): String? {
        val explicitPath = song.localPath?.takeIf(String::isNotBlank)
        val path = explicitPath ?: song.localUri
            ?.takeIf { it.startsWith("file://", ignoreCase = true) }
            ?.let { runCatching { Uri.decode(Uri.parse(it).path.orEmpty()) }.getOrNull() }
        return path?.replace('\\', '/')?.trimEnd('/')?.takeIf(String::isNotBlank)
    }

    fun request(path: String) {
        val normalized = path.replace('\\', '/').trimEnd('/').ifBlank { "/" }
        _pending.value = Request(ids.incrementAndGet(), normalized)
    }

    @Synchronized
    fun handled(request: Request) {
        if (_pending.value?.id == request.id) _pending.value = null
    }
}
