package com.music.bitchord.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** Reads just enough metadata to show a file-manager item in the existing player. */
object ExternalAudioPreview {

    suspend fun readSong(context: Context, uriString: String): Song = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (uriString.length > MAX_EXTERNAL_URI_LENGTH) throw IOException("Audio URI is too long")
        val uri = runCatching { Uri.parse(uriString) }.getOrElse {
            throw IOException("Invalid audio URI", it)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val filePath = when (scheme) {
            "file" -> resolveExternalFilePath(appContext, uri)
            "content" -> null
            else -> throw IOException("Unsupported audio URI")
        }
        val retriever = MediaMetadataRetriever()
        var hasDataSource = false
        try {
            when (scheme) {
                "content" -> retriever.setDataSource(appContext, uri).also { hasDataSource = true }
                "file" -> retriever.setDataSource(filePath!!).also { hasDataSource = true }
            }
        } catch (_: Exception) {
            // A bad tag/container should not prevent fallback to the file name.
        }

        try {
            val fileName = displayName(appContext, uri)
            val title = hasDataSource
                .let { if (it) retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) else null }
                ?.takeIf(String::isNotBlank)
                ?: fileName.substringBeforeLast('.', fileName).ifBlank { "Audio" }
            val artist = if (hasDataSource) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf(String::isNotBlank)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?.takeIf(String::isNotBlank)
            } else null
            val album = if (hasDataSource) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf(String::isNotBlank)
            } else null
            val durationText = if (hasDataSource) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let(::formatDuration)
            } else null

            Song(
                videoId = uri.toString(),
                title = title,
                artist = artist ?: appContext.getString(R.string.unknown_artist),
                thumbnailUrl = uri.toString(),
                durationText = durationText,
                albumName = album,
                localUri = uri.toString(),
                localPath = filePath,
                isExternalPreview = true,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.let(::File)?.name.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) cursor.getString(column) else null
                    } else null
                }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty()
    }

    /**
     * File-manager intents may name shared media, but must not turn this
     * exported activity into a reader for MusicBeat's private app data. Resolve
     * symlinks before checking so a public-looking path cannot alias a private
     * file. Android still applies its normal access checks to shared storage.
     */
    private fun resolveExternalFilePath(context: Context, uri: Uri): String {
        if (!uri.encodedAuthority.isNullOrBlank() && !uri.authority.equals("localhost", ignoreCase = true)) {
            throw IOException("Unsupported file URI authority")
        }
        val path = uri.path?.takeIf(String::isNotBlank)
            ?: throw IOException("File URI has no path")
        val file = runCatching { File(path).canonicalFile }.getOrElse {
            throw IOException("Invalid file URI path", it)
        }
        if (!file.isFile) throw IOException("Audio file is unavailable")
        val privateRoots = buildList<String> {
            add(context.applicationInfo.dataDir)
            add(context.filesDir.absolutePath)
            add(context.cacheDir.absolutePath)
            add(context.noBackupFilesDir.absolutePath)
            runCatching { context.codeCacheDir.absolutePath }.getOrNull()?.let(::add)
            runCatching { context.getExternalFilesDirs(null).filterNotNull().map(File::getCanonicalPath) }
                .getOrNull()?.let(::addAll)
            runCatching { context.externalCacheDirs.filterNotNull().map(File::getCanonicalPath) }
                .getOrNull()?.let(::addAll)
        }.mapNotNull { root -> runCatching { File(root).canonicalPath }.getOrNull() }
        val canonicalPath = file.path
        val isPrivate = privateRoots.any { root ->
            canonicalPath == root || canonicalPath.startsWith(root + File.separator)
        }
        if (isPrivate) throw IOException("Private app files cannot be opened externally")
        return canonicalPath
    }

    private const val MAX_EXTERNAL_URI_LENGTH = 8_192

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return if (minutes >= 60) {
            String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
