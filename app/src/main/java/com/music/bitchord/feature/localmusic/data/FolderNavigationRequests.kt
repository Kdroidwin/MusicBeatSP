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

    fun folderPathFor(song: Song): String? = song.localFolderPath
        ?.takeIf(String::isNotBlank)
        ?.let(::normalizeFolderPath)
        ?: localPath(song)?.let(::parentFolderPath)

    /**
     * Playlist rows can retain an older MediaStore URI than the library row
     * for the same file. Resolve by URI and path first, then a unique
     * MediaStore row identity or exact saved tags. This keeps folder navigation
     * working when scoped storage hides DATA and an old playlist URI no longer
     * equals the current library URI, without guessing between duplicate files.
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
        candidates.firstOrNull { candidate ->
            candidate.videoId == song.videoId &&
                (!candidate.localUri.isNullOrBlank() || !candidate.localPath.isNullOrBlank())
        }?.let { return it }

        val requestedMediaIdentity = listOfNotNull(song.localUri, song.videoId)
            .firstNotNullOfOrNull(::mediaStoreAudioIdentity)
        if (requestedMediaIdentity != null) {
            val identityMatches = candidates.mapNotNull { candidate ->
                val candidateIdentity = listOfNotNull(candidate.localUri, candidate.videoId)
                    .firstNotNullOfOrNull(::mediaStoreAudioIdentity)
                    ?: return@mapNotNull null
                if (!candidateIdentity.matches(requestedMediaIdentity)) return@mapNotNull null
                candidate to candidateIdentity
            }
            identityMatches.singleOrNull { (_, identity) ->
                identity.volume == requestedMediaIdentity.volume
            }?.first?.let { return it }
            exactMetadataMatch(song, identityMatches.map { it.first })?.let { return it }
        }

        // Older/imported playlists may have no stable row ID or path hint.
        // A local URI plus a unique exact title/artist match is safe to use;
        // if duplicate copies make it ambiguous, keep the action disabled.
        return exactMetadataMatch(song, candidates)
    }

    private data class MediaStoreAudioIdentity(val volume: String?, val rowId: Long) {
        fun matches(other: MediaStoreAudioIdentity): Boolean {
            if (rowId != other.rowId) return false
            if (volume == other.volume || volume == null || other.volume == null) return true
            // EXTERNAL_CONTENT_URI is an aggregate alias for external volumes.
            return volume == "external" || other.volume == "external"
        }
    }

    private fun mediaStoreAudioIdentity(rawUri: String): MediaStoreAudioIdentity? = runCatching {
        val uri = Uri.parse(rawUri)
        if (uri.scheme != "content" || uri.authority != "media") return null
        val segments = uri.pathSegments
        if (segments.size < 3 || segments.takeLast(3).let { it[0] != "audio" || it[1] != "media" }) return null
        val rowId = segments.last().toLongOrNull() ?: return null
        val volume = segments.getOrNull(segments.lastIndex - 3)
            ?.takeIf(String::isNotBlank)
        MediaStoreAudioIdentity(volume, rowId)
    }.getOrNull()

    private fun exactMetadataMatch(song: Song, candidates: List<Song>): Song? {
        val title = song.title.normalizedForFolderMatch().takeIf(String::isNotBlank) ?: return null
        val artist = song.artist.normalizedForFolderMatch()
            .takeUnless { it.isBlank() || it == "unknown artist" }
        val album = song.albumName?.normalizedForFolderMatch()?.takeIf(String::isNotBlank)
        val matches = candidates.filter { candidate ->
            candidate.title.normalizedForFolderMatch() == title &&
                (artist == null || candidate.artist.normalizedForFolderMatch() == artist) &&
                (album == null || candidate.albumName?.normalizedForFolderMatch() == album)
        }
        return matches.singleOrNull()
    }

    private fun String.normalizedForFolderMatch(): String =
        trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

    private fun localPath(song: Song): String? {
        val explicitPath = song.localPath?.takeIf(String::isNotBlank)
        val path = explicitPath ?: song.localUri
            ?.takeIf { it.startsWith("file://", ignoreCase = true) }
            ?.let { runCatching { Uri.decode(Uri.parse(it).path.orEmpty()) }.getOrNull() }
        return path?.replace('\\', '/')?.trimEnd('/')?.takeIf(String::isNotBlank)
    }

    private fun parentFolderPath(path: String): String? {
        val normalized = path.replace('\\', '/').trim()
        val separator = normalized.lastIndexOf('/')
        return when {
            separator < 0 -> null
            separator == 0 -> "/"
            else -> normalized.substring(0, separator).trimEnd('/').takeIf(String::isNotBlank)
        }
    }

    private fun normalizeFolderPath(path: String): String = path
        .replace('\\', '/')
        .trim()
        .trimEnd('/')
        .ifBlank { "/" }

    fun request(path: String) {
        val normalized = path.replace('\\', '/').trimEnd('/').ifBlank { "/" }
        _pending.value = Request(ids.incrementAndGet(), normalized)
    }

    @Synchronized
    fun handled(request: Request) {
        if (_pending.value?.id == request.id) _pending.value = null
    }
}
