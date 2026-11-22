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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.R
import com.music.bitchord.feature.localmusic.data.LocalFolderRootResolver
import com.music.bitchord.feature.localmusic.data.LocalFolderGrantStore

@Composable
internal fun rememberMusicFolderRootPicker(
    onFolderSelected: (String) -> Unit,
): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            val persistedGrant = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (persistedGrant) LocalFolderGrantStore.add(context, treeUri)
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
internal fun LocalMusicFolderRootsSettingsRows(
    paths: List<String>,
    allFilesAccessEnabled: Boolean = false,
    onChoose: () -> Unit,
    onRemove: (String) -> Unit,
) {
    if (allFilesAccessEnabled) {
        SettingsRow(
            icon = Icons.Rounded.Folder,
            title = stringResource(R.string.music_folder_root),
            subtitle = stringResource(R.string.music_folder_root_all_files_access),
            trailing = {
                Text(
                    text = stringResource(R.string.music_folder_root_auto),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        return
    }
    SettingsRow(
        icon = Icons.Rounded.Folder,
        title = stringResource(R.string.music_folder_root),
        subtitle = stringResource(R.string.music_folder_root_subtitle),
        trailing = { Chevron() },
        onClick = onChoose,
    )
    if (paths.isEmpty()) {
        SettingsRow(
            icon = Icons.Rounded.Folder,
            title = stringResource(R.string.music_folder_root_auto),
            subtitle = stringResource(R.string.music_folder_root_auto_subtitle),
            onClick = onChoose,
        )
    } else {
        paths.forEach { path ->
            SettingsRow(
                icon = Icons.Rounded.Folder,
                title = path.substringAfterLast('/').ifBlank { path },
                subtitle = path,
                trailing = {
                    androidx.compose.material3.IconButton(onClick = { onRemove(path) }) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.Clear,
                            contentDescription = stringResource(R.string.remove),
                        )
                    }
                },
            )
        }
    }
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
