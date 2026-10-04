package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylistSongMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

data class PreparedMusicoletPlaylist(
    val id: String,
    val name: String,
    val tracks: List<M3uImportTrack>,
    val skippedCount: Int,
) {
    val metadata: Map<String, LocalPlaylistSongMetadata>
        get() = tracks.associate { it.id to LocalPlaylistSongMetadata(it.title, it.artist) }
    val artworkUrl: String? get() = tracks.firstNotNullOfOrNull(M3uImportTrack::artworkUrl)
}

data class PreparedMusicoletBackup(
    val sourceName: String,
    val playlists: List<PreparedMusicoletPlaylist>,
)

enum class MusicoletImportFailure { CANNOT_OPEN, EMPTY_FILE, INVALID_BACKUP, NO_PLAYLISTS }

class MusicoletImportException(val reason: MusicoletImportFailure) : Exception()

/** Imports only .mpl playlist members from a Musicolet backup ZIP. */
object MusicoletBackupImporter {
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L
    private const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
    private const val MAX_PLAYLIST_COUNT = 500

    suspend fun prepare(context: Context, backupUri: Uri, localSongs: List<Song>): PreparedMusicoletBackup =
        withContext(Dispatchers.IO) {
            val sourceName = displayName(context, backupUri)
            val source = context.contentResolver.openInputStream(backupUri)
                ?: throw MusicoletImportException(MusicoletImportFailure.CANNOT_OPEN)
            val archiveEntries = mutableListOf<Pair<String, ByteArray>>()
            var totalRead = 0L
            try {
                ZipInputStream(source).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val isPlaylist = entry.name.substringAfterLast('/').endsWith(".mpl", ignoreCase = true)
                        val buffer = ByteArray(8192)
                        val output = if (isPlaylist && archiveEntries.size < MAX_PLAYLIST_COUNT) {
                            ByteArrayOutputStream(minOf(entry.size.takeIf { it in 0..MAX_PLAYLIST_BYTES.toLong() }?.toInt() ?: 4096, MAX_PLAYLIST_BYTES))
                        } else null
                        var entryBytes = 0L
                        var tooLarge = false
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalRead += count
                            entryBytes += count
                            if (totalRead > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                throw MusicoletImportException(MusicoletImportFailure.INVALID_BACKUP)
                            }
                            if (output != null) {
                                if (entryBytes <= MAX_PLAYLIST_BYTES) output.write(buffer, 0, count)
                                else tooLarge = true
                            }
                        }
                        if (output != null && !tooLarge && entryBytes > 0L) {
                            archiveEntries += entry.name to output.toByteArray()
                        }
                        zip.closeEntry()
                    }
                }
            } catch (error: MusicoletImportException) {
                throw error
            } catch (_: Throwable) {
                throw MusicoletImportException(MusicoletImportFailure.INVALID_BACKUP)
            }

            if (totalRead == 0L) throw MusicoletImportException(MusicoletImportFailure.EMPTY_FILE)
            val parsedPlaylists = archiveEntries.mapNotNull { (name, bytes) ->
                MusicoletBackupParser.parsePlaylist(name, bytes)
            }
            if (parsedPlaylists.isEmpty()) throw MusicoletImportException(MusicoletImportFailure.NO_PLAYLISTS)

            PreparedMusicoletBackup(
                sourceName = sourceName.substringBeforeLast('.', sourceName),
                playlists = parsedPlaylists.mapIndexed { index, parsed ->
                    val imported = mutableListOf<M3uImportTrack>()
                    val seen = mutableSetOf<String>()
                    var skipped = 0
                    parsed.entries.forEach { entry ->
                        val track = resolveTrack(context, entry, localSongs)
                        if (track == null || !seen.add(track.id)) skipped++ else imported += track
                    }
                    PreparedMusicoletPlaylist(
                        id = "$index:${parsed.name}",
                        name = parsed.name,
                        tracks = imported,
                        skippedCount = skipped,
                    )
                },
            )
        }

    private fun resolveTrack(context: Context, entry: MusicoletPlaylistEntry, localSongs: List<Song>): M3uImportTrack? {
        val raw = entry.location.trim()
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)

        localSongs.firstOrNull { it.localUri == raw || it.videoId == raw }?.let { return it.toTrack(entry) }

        val relativeDocumentPath = if (scheme == "content") documentRelativePath(context, uri) else null
        val absolutePath = when {
            scheme == "file" -> uri?.path
            scheme == null && raw.startsWith('/') -> raw
            scheme == "content" -> relativeDocumentPath?.let { documentPathToAbsolute(context, uri, it) }
            else -> null
        }?.let(::canonicalPath)

        if (absolutePath != null) {
            localSongs.firstOrNull { canonicalPath(it.localPath) == absolutePath }?.let { return it.toTrack(entry) }
        }

        val documentSuffix = relativeDocumentPath?.trimStart('/')?.replace('\\', '/')
        if (!documentSuffix.isNullOrBlank()) {
            localSongs.filter { song -> song.localPath?.replace('\\', '/')?.endsWith("/$documentSuffix", true) == true }
                .singleOrNull()?.let { return it.toTrack(entry) }
        }

        if (scheme == "content" && canRead(context, uri) && isAudioUri(context, uri, raw)) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.let(Uri::decode).orEmpty()
            return M3uImportTrack(
                id = raw,
                title = entry.title ?: fileName.substringBeforeLast('.', fileName).ifBlank { "Unknown track" },
                artist = entry.artist.orEmpty(),
            )
        }

        val file = absolutePath?.let(::File)
        if (file?.isFile == true && isSupportedAudio(file.name)) {
            return M3uImportTrack(
                id = Uri.fromFile(file).toString(),
                title = entry.title ?: file.nameWithoutExtension,
                artist = entry.artist.orEmpty(),
            )
        }

        val expectedFileName = when {
            scheme == "content" -> uri?.lastPathSegment?.substringAfterLast('/')?.let(Uri::decode)
            scheme == "file" -> uri?.lastPathSegment?.let(Uri::decode)
            else -> raw.substringAfterLast('/').substringAfterLast('\\')
        }?.substringBefore('?')?.substringBefore('#')
        if (!expectedFileName.isNullOrBlank()) {
            localSongs.filter { song ->
                val localName = song.localPath?.substringAfterLast('/')
                    ?: song.localUri?.let { value -> runCatching { Uri.parse(value).lastPathSegment?.let(Uri::decode) }.getOrNull() }
                localName.equals(expectedFileName, ignoreCase = true)
            }.singleOrNull()?.let { return it.toTrack(entry) }
        }

        val title = entry.title?.takeIf(String::isNotBlank) ?: return null
        val matchingByTags = localSongs.filter { song ->
            song.title.equals(title, ignoreCase = true) &&
                (entry.artist.isNullOrBlank() || song.artist.equals(entry.artist, ignoreCase = true)) &&
                (entry.album.isNullOrBlank() || song.albumName.isNullOrBlank() || song.albumName.equals(entry.album, ignoreCase = true))
        }
        return matchingByTags.singleOrNull()?.toTrack(entry)
    }

    private fun Song.toTrack(entry: MusicoletPlaylistEntry) = M3uImportTrack(
        id = localUri ?: videoId,
        title = entry.title?.takeIf(String::isNotBlank) ?: title,
        artist = entry.artist?.takeIf(String::isNotBlank) ?: artist,
        artworkUrl = thumbnailUrl,
    )

    private fun documentRelativePath(context: Context, uri: Uri?): String? {
        uri ?: return null
        val documentId = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null
        }.getOrNull()
        if (!documentId.isNullOrBlank()) return documentId.substringAfter(':', documentId).replace('\\', '/')

        val decoded = runCatching { Uri.decode(uri.toString()) }.getOrDefault(uri.toString())
        val marker = "/document/"
        return decoded.substringAfter(marker, "").takeIf(String::isNotBlank)
            ?.substringAfter(':', decoded.substringAfter(marker))
            ?.replace('\\', '/')
    }

    private fun documentPathToAbsolute(context: Context, uri: Uri?, relative: String): String? {
        uri ?: return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
        val volume = documentId.substringBefore(':', "primary")
        val root = if (volume.equals("primary", ignoreCase = true)) {
            android.os.Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return File(root, relative).path
    }

    private fun canonicalPath(path: String?): String? = path?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { File(value).canonicalPath }.getOrElse { File(value).absolutePath }
    }

    private fun canRead(context: Context, uri: Uri?): Boolean = uri != null && runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun isAudioUri(context: Context, uri: Uri?, raw: String): Boolean {
        val name = uri?.lastPathSegment.orEmpty()
        return isSupportedAudio(name) || context.contentResolver.getType(uri!!)?.startsWith("audio/") == true ||
            raw.startsWith("content://media/", ignoreCase = true)
    }

    private fun isSupportedAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
        "mp3", "m4a", "m4b", "flac", "alac", "wav", "ogg", "opus", "aac", "webm", "mp4",
    )

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().ifBlank { "Musicolet backup.zip" }
}
