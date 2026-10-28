package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.R
import com.music.bitchord.feature.localmusic.data.LocalFolderRootResolver

@Composable
internal fun rememberMusicFolderRootPicker(
    onFolderSelected: (String) -> Unit,
): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val path = LocalFolderRootResolver.resolve(context, treeUri)
            if (path != null) {
                onFolderSelected(path)
            } else {
                Toast.makeText(context, R.string.music_folder_root_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}


@Composable
internal fun LocalMusicFolderRootSettingsRow(
    path: String,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsRow(
        icon = Icons.Rounded.Folder,
        title = stringResource(R.string.music_folder_root),
        subtitle = path.ifBlank { stringResource(R.string.music_folder_root_subtitle) },
        trailing = {
            if (path.isBlank()) {
                Chevron()
            } else {
                androidx.compose.material3.IconButton(onClick = onClear) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Clear,
                        contentDescription = stringResource(R.string.clear_search),
                    )
                }
            }
        },
        onClick = onChoose,
    )
}

@Composable
internal fun ChannelSwapSettingsRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(
        icon = Icons.Rounded.SwapHoriz,
        title = stringResource(R.string.channel_swap),
        subtitle = stringResource(R.string.channel_swap_subtitle),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
        onClick = { onCheckedChange(!checked) },
    )
}
