package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

data class PreparedCueSheet(
    val songs: List<Song>,
    val skippedCount: Int,
)

/** Resolves CUE references only to audio the app can already access. */
object CueSheetImporter {
    suspend fun prepare(context: Context, cueUri: Uri, librarySongs: List<Song>): PreparedCueSheet =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(cueUri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_CUE_BYTES) { "CUE sheet is too large" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: error("Cannot open CUE sheet")
            require(bytes.isNotEmpty()) { "CUE sheet is empty" }
            val cue = CueSheetParser.parse(String(bytes, StandardCharsets.UTF_8))
            val basePath = parentPath(context, cueUri)
            val songs = mutableListOf<Song>()
            var skipped = 0

            cue.tracks.forEachIndexed { index, track ->
                val resolvedPath = resolvePath(basePath, track.file)
                val source = matchSong(librarySongs, resolvedPath, track.file)
                if (source == null) {
                    skipped++
                    return@forEachIndexed
                }
                val next = cue.tracks.drop(index + 1).firstOrNull { sameFile(it.file, track.file) }
                val sourceDuration = source.durationMillis()
                    .takeIf { it > 0L }
                val endMs = next?.startMs ?: sourceDuration
                if (endMs != null && endMs <= track.startMs) {
                    skipped++
                    return@forEachIndexed
                }
                val trackTitle = track.title?.takeIf(String::isNotBlank)
                    ?: source.title
                val trackArtist = track.performer?.takeIf(String::isNotBlank)
                    ?: cue.performer?.takeIf(String::isNotBlank)
                    ?: source.artist
                val lengthMs = endMs?.minus(track.startMs)
                songs += source.copy(
                    videoId = "cue:${source.localUri ?: source.videoId}:${track.number}",
                    title = trackTitle,
                    artist = trackArtist,
                    albumArtist = cue.performer ?: source.albumArtist,
                    trackNumber = track.number,
                    albumName = cue.title ?: source.albumName,
                    durationText = lengthMs?.let(::formatDuration),
                    localStartMs = track.startMs,
                    localEndMs = endMs,
                )
            }
            require(songs.isNotEmpty()) { "No CUE tracks could be matched to accessible audio files" }
            PreparedCueSheet(songs, skipped)
        }

    private fun matchSong(songs: List<Song>, resolvedPath: String?, fileReference: String): Song? {
        if (resolvedPath != null) {
            songs.firstOrNull { canonical(it.localPath) == resolvedPath }?.let { return it }
        }
        val name = fileReference.substringAfterLast('/').substringAfterLast('\\')
        return songs.filter { song ->
            val songName = song.localPath?.substringAfterLast('/')
                ?: song.localUri?.let { Uri.parse(it).lastPathSegment }
                ?: song.title
            songName.equals(name, ignoreCase = true)
        }.singleOrNull()
    }

    private fun resolvePath(basePath: String?, reference: String): String? {
        val uri = runCatching { Uri.parse(reference) }.getOrNull()
        val direct = when (uri?.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path
            null -> if (File(reference).isAbsolute) reference else null
            else -> null
        }
        return when {
            direct != null -> canonical(direct)
            basePath != null -> canonical(File(basePath, reference.replace('\\', File.separatorChar)).path)
            else -> null
        }
    }

    private fun parentPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.parent
        val providerPath = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        if (!providerPath.isNullOrBlank()) return File(providerPath).parent
        return runCatching {
            if (!DocumentsContract.isDocumentUri(context, uri)) return@runCatching null
            val docId = DocumentsContract.getDocumentId(uri)
            val volume = docId.substringBefore(':', "primary")
            val relative = docId.substringAfter(':', "").substringBeforeLast('/', "")
            if (relative.isBlank()) return@runCatching null
            val root = if (volume.equals("primary", true)) {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            } else "/storage/$volume"
            File(root, relative).canonicalPath
        }.getOrNull()
    }

    private fun canonical(path: String?): String? = path?.takeIf(String::isNotBlank)?.let {
        runCatching { File(it).canonicalPath }.getOrNull() ?: File(it).absolutePath
    }

    private fun sameFile(left: String, right: String): Boolean =
        left.replace('\\', '/').equals(right.replace('\\', '/'), ignoreCase = true)

    private fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0L) + 500L) / 1_000L
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val remainder = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
        else "%d:%02d".format(minutes, remainder)
    }

    private const val MAX_CUE_BYTES = 4 * 1024 * 1024
}
