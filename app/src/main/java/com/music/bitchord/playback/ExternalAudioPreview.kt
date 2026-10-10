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
import java.util.Locale

/** Reads just enough metadata to show a file-manager item in the existing player. */
object ExternalAudioPreview {

    suspend fun readSong(context: Context, uriString: String): Song = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val uri = Uri.parse(uriString)
        val retriever = MediaMetadataRetriever()
        var hasDataSource = false
        try {
            when (uri.scheme) {
                "content" -> retriever.setDataSource(appContext, uri).also { hasDataSource = true }
                "file" -> uri.path?.let { path ->
                    if (File(path).isFile) retriever.setDataSource(path).also { hasDataSource = true }
                }
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
                localPath = uri.path?.takeIf { uri.scheme == "file" },
                isExternalPreview = true,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
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
