package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

data class M3uExportSummary(val exported: Int, val skipped: Int)

/** Exports the playlist's stored order and only references local audio. */
object M3uPlaylistExporter {
    fun render(playlist: LocalPlaylist, librarySongs: List<Song>): Pair<String, M3uExportSummary> {
        val songsById = librarySongs.flatMap { song ->
            listOfNotNull(song.localUri, song.videoId).map { it to song }
        }.toMap()
        val lines = mutableListOf("#EXTM3U")
        var exported = 0
        var skipped = 0
        playlist.songIds.forEach { id ->
            val song = songsById[id]
            val uri = song?.let { localReference(it.localPath, it.localUri) }
                ?: localReference(null, id)
            if (uri == null) {
                skipped++
                return@forEach
            }
            val metadata = playlist.songMetadata[id]
            val title = metadata?.title?.takeIf(String::isNotBlank) ?: song?.title ?: uri.substringAfterLast('/').substringBeforeLast('.')
            val artist = metadata?.artist?.takeIf(String::isNotBlank) ?: song?.artist.orEmpty()
            val duration = song?.durationMillis()?.div(1_000L)?.takeIf { it > 0L } ?: -1L
            val display = if (artist.isBlank()) title else "$artist - $title"
            lines += "#EXTINF:$duration,${display.replace('\n', ' ').replace('\r', ' ')}"
            lines += uri
            exported++
        }
        return lines.joinToString("\n", postfix = "\n") to M3uExportSummary(exported, skipped)
    }

    suspend fun write(context: Context, target: Uri, playlist: LocalPlaylist, songs: List<Song>): M3uExportSummary =
        withContext(Dispatchers.IO) {
            val (text, summary) = render(playlist, songs)
            require(summary.exported > 0) { "Playlist has no exportable local audio" }
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: error("Could not open the selected destination")
            output.use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer -> writer.write(text) }
            }
            summary
        }

    private fun localReference(path: String?, rawUri: String?): String? {
        path?.takeIf(String::isNotBlank)?.let { return it }
        val parsed = rawUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        return parsed.toString().takeIf { parsed.scheme == "file" || parsed.scheme == "content" }
    }
}
