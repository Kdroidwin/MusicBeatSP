package com.music.bitchord.feature.localsongactions.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.music.bitchord.data.model.Song

/**
 * Utility functions for local song actions like sharing.
 */
object LocalSongActionsHelper {

    fun shareSong(context: Context, song: Song) {
        try {
            val uri = song.localUri?.let { Uri.parse(it) }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                if (uri != null) {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                putExtra(Intent.EXTRA_TEXT, "${song.title} - ${song.artist}")
            }
            val chooser = Intent.createChooser(intent, "Share")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Throwable) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareSongs(context: Context, songs: List<Song>) {
        try {
            val distinct = songs.distinctBy { it.localUri ?: it.videoId }
            val uris = distinct.mapNotNull { song ->
                val raw = song.localUri ?: song.videoId
                runCatching { Uri.parse(raw) }.getOrNull()
                    ?.takeIf { it.scheme == "content" }
            }.distinct()
            if (uris.isEmpty()) {
                Toast.makeText(context, "No shareable audio files", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(
                    Intent.EXTRA_TEXT,
                    distinct.joinToString("\n") { "${it.title} - ${it.artist}" },
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(context.contentResolver, "MusicBeatSP audio", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                }
            }
            context.startActivity(Intent.createChooser(intent, "Share songs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            Toast.makeText(context, "Could not share files", Toast.LENGTH_SHORT).show()
        }
    }
}
