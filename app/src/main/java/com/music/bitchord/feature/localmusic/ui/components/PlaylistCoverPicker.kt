package com.music.bitchord.feature.localmusic.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Opens the system photo/document picker and grants the app durable read access. */
@Composable
fun rememberPlaylistCoverPicker(
    onCoverSelected: (playlistId: String, uri: String) -> Unit,
): (String) -> Unit {
    val context = LocalContext.current
    var pendingPlaylistId by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val playlistId = pendingPlaylistId
        pendingPlaylistId = null
        if (uri != null && playlistId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onCoverSelected(playlistId, uri.toString())
        }
    }
    return remember(launcher) {
        { playlistId ->
            pendingPlaylistId = playlistId
            launcher.launch(arrayOf("image/*"))
        }
    }
}
