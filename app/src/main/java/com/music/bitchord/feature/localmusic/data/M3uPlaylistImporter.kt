package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylistSongMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

data class M3uImportTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
)

data class PreparedM3uImport(
    val suggestedName: String,
    val tracks: List<M3uImportTrack>,
    val skippedCount: Int,
) {
    val metadata: Map<String, LocalPlaylistSongMetadata>
        get() = tracks.associate { track ->
            track.id to LocalPlaylistSongMetadata(track.title, track.artist)
        }

    val artworkUrl: String? get() = tracks.firstNotNullOfOrNull(M3uImportTrack::artworkUrl)
}

enum class M3uImportFailure { CANNOT_OPEN, EMPTY_FILE, INVALID_FILE, NO_ENTRIES }

class M3uImportException(val reason: M3uImportFailure) : Exception()

/** SAF based M3U/M3U8 import. Only references the app can resolve or open are retained. */
object M3uPlaylistImporter {
    private val supportedAudioExtensions = setOf(
        "mp3", "m4a", "m4b", "flac", "alac", "wav", "ogg", "opus", "aac", "webm", "mp4",
    )

    suspend fun prepare(context: Context, playlistUri: Uri, localSongs: List<Song>): PreparedM3uImport =
        withContext(Dispatchers.IO) {
            val fileName = displayName(context, playlistUri)
            val bytes = context.contentResolver.openInputStream(playlistUri)?.use { it.readBytes() }
                ?: throw M3uImportException(M3uImportFailure.CANNOT_OPEN)
            if (bytes.isEmpty()) throw M3uImportException(M3uImportFailure.EMPTY_FILE)
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            val text = if ('\uFFFD' in utf8) String(bytes, Charset.forName("windows-1252")) else utf8
            if (text.any { it == '\u0000' }) throw M3uImportException(M3uImportFailure.INVALID_FILE)
            val parsed = M3uParser.parse(text, fileName)
            if (parsed.entries.isEmpty()) throw M3uImportException(M3uImportFailure.NO_ENTRIES)

            val baseDirectory = playlistDirectory(context, playlistUri)
            val tracks = mutableListOf<M3uImportTrack>()
            val seen = mutableSetOf<String>()
            var skipped = 0
            parsed.entries.forEach { entry ->
                val resolved = resolveEntry(context, entry, baseDirectory, localSongs)
                if (resolved == null || !seen.add(resolved.id)) {
                    skipped++
                } else {
                    tracks += resolved
                }
            }
            PreparedM3uImport(parsed.suggestedName, tracks, skipped)
        }

    private fun resolveEntry(
        context: Context,
        entry: M3uEntry,
        baseDirectory: String?,
        localSongs: List<Song>,
    ): M3uImportTrack? {
        val raw = entry.location.trim().trim('"')
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (scheme == "content") {
            localSongs.firstOrNull { it.localUri == raw || it.videoId == raw }?.let { return it.toImportTrack(entry) }
            if (!canReadContentUri(context, uri)) return null
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val name = contentDisplayName(context, uri) ?: uri.lastPathSegment.orEmpty()
            if (!hasSupportedAudioExtension(name) && context.contentResolver.getType(uri)?.startsWith("audio/") != true) return null
            val id = uri.toString()
            return M3uImportTrack(
                id = id,
                title = entry.title ?: name.substringBeforeLast('.', name).ifBlank { "Unknown track" },
                artist = entry.artist.orEmpty(),
            )
        }
        if (scheme != null && scheme != "file") return null

        val directPath = if (scheme == "file") uri?.path else raw.takeIf(::isAbsolutePath)
        val resolvedPath = directPath?.let(M3uParser::resolveAbsolutePath)
            ?: M3uParser.resolveRelativePath(baseDirectory, raw)
        val candidatePath = resolvedPath?.let(::canonicalPath)

        val matchedByPath = candidatePath?.let { path ->
            localSongs.firstOrNull { song -> canonicalPath(song.localPath) == path }
        }
        if (matchedByPath != null) return matchedByPath.toImportTrack(entry)

        val relative = if (directPath == null) raw.replace('\\', '/').trimStart('/') else null
        val matchedBySuffix = relative?.let { suffix ->
            localSongs.filter { song ->
                val path = song.localPath?.replace('\\', '/') ?: return@filter false
                path.endsWith("/$suffix", ignoreCase = true)
            }.singleOrNull()
        }
        if (matchedBySuffix != null) return matchedBySuffix.toImportTrack(entry)

        val file = candidatePath?.let(::File)
        if (file?.isFile == true && hasSupportedAudioExtension(file.name)) {
            val id = Uri.fromFile(file).toString()
            return M3uImportTrack(
                id = id,
                title = entry.title ?: file.nameWithoutExtension,
                artist = entry.artist.orEmpty(),
            )
        }

        // SAF providers sometimes hide their backing path. Use EXTINF tags only
        // when they identify one unambiguous row with the same file name.
        val expectedName = raw.substringAfterLast('/').substringAfterLast('\\')
            .substringBefore('?').substringBefore('#')
        if (expectedName.isNotBlank()) {
            val byName = localSongs.filter { song ->
                val localName = song.localPath?.substringAfterLast('/')
                    ?: song.localUri?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
                    ?: song.title
                localName.equals(expectedName, ignoreCase = true) ||
                    localName.substringBeforeLast('.', localName).equals(
                        entry.title ?: expectedName.substringBeforeLast('.', expectedName),
                        ignoreCase = true,
                    )
            }.filter { song ->
                entry.artist.isNullOrBlank() || song.artist.equals(entry.artist, ignoreCase = true)
            }
            byName.singleOrNull()?.let { return it.toImportTrack(entry) }
        }
        return null
    }

    private fun Song.toImportTrack(entry: M3uEntry) = M3uImportTrack(
        id = localUri ?: videoId,
        title = entry.title?.takeIf(String::isNotBlank) ?: title,
        artist = entry.artist?.takeIf(String::isNotBlank) ?: artist,
        artworkUrl = thumbnailUrl,
    )

    private fun canReadContentUri(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String =
        contentDisplayName(context, uri) ?: uri.lastPathSegment.orEmpty().ifBlank { "Imported Playlist.m3u" }

    private fun contentDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()

    private fun playlistDirectory(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.parent
        val mediaStorePath = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        if (!mediaStorePath.isNullOrBlank()) return File(mediaStorePath).parent
        return runCatching {
            if (!DocumentsContract.isDocumentUri(context, uri)) return@runCatching null
            val documentId = DocumentsContract.getDocumentId(uri)
            val volume = documentId.substringBefore(':', "primary")
            val relative = documentId.substringAfter(':', "").substringBeforeLast('/', "")
            if (relative.isBlank()) return@runCatching null
            val root = if (volume.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$volume"
            }
            File(root, relative).canonicalPath
        }.getOrNull()
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith('/') || Regex("^[A-Za-z]:[/\\\\].*").matches(path)

    private fun canonicalPath(path: String?): String? = path?.takeIf(String::isNotBlank)?.let {
        runCatching { File(it).canonicalPath }.getOrNull() ?: File(it).absolutePath
    }

    private fun hasSupportedAudioExtension(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in supportedAudioExtensions
}
