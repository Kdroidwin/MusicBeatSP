package com.music.bitchord.feature.localmusic.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localmusic.data.M3uPlaylistExporter
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import kotlinx.coroutines.launch

@Composable
fun rememberM3uExportAction(playlist: LocalPlaylist, songs: List<Song>): () -> Unit {
    val context = LocalContext.current.applicationContext
    val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { target ->
        if (target != null) {
            lifecycleScope.launch {
                runCatching { M3uPlaylistExporter.write(context, target, playlist, songs) }
                    .onSuccess { result ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.m3u_export_result, result.exported, result.skipped),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure {
                        Toast.makeText(context, R.string.m3u_export_failed, Toast.LENGTH_LONG).show()
                    }
            }
        }
    }
    return remember(launcher, playlist.name) {
        {
            val safeName = playlist.name.trim().ifBlank { "Playlist" }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            launcher.launch("$safeName.m3u")
        }
    }
}
