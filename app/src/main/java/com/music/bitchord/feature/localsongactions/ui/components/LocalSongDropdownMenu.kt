package com.music.bitchord.feature.localsongactions.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.feature.artistimage.util.ArtistSplitter
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * Material 3 DropdownMenu displaying the 8 local song options matching the reference player.
 */
@Composable
fun LocalSongDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    song: Song,
    onQueueNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToAlbum: ((String) -> Unit)? = null,
    onGoToArtist: ((String) -> Unit)? = null,
    onGoToFolder: ((String) -> Unit)? = null,
    onTagEditor: () -> Unit,
    onShare: () -> Unit,
    onDeleteFromDevice: () -> Unit,
    onDetails: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    artworkUrlForPalette: String? = null,
    disableArtworkPalette: Boolean = false,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
) {
    var showingSubMenu by remember { mutableStateOf(false) }
    // Keep the usual track-specific palette. Playlist drill-downs may pass the
    // collection cover so their menu matches the full-screen collection menu.
    val paletteArtworkUrl = artworkUrlForPalette?.takeIf(String::isNotBlank) ?: song.thumbnailUrl
    val palette = if (expanded && !disableArtworkPalette) {
        rememberArtworkPalette(paletteArtworkUrl, artPx = CARD_ART_PX)
    } else {
        null
    }
    val menuForeground = palette?.onBackground ?: MaterialTheme.colorScheme.onSurface

    CompositionLocalProvider(LocalContentColor provides menuForeground) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                showingSubMenu = false
                onDismissRequest()
            },
            offset = offset,
            shape = RoundedCornerShape(16.dp),
            containerColor = palette?.background ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier,
        ) {
        if (!showingSubMenu) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.queue_next)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.PlaylistPlay,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismissRequest()
                    onQueueNext()
                },
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_to_queue)) },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismissRequest()
                    onAddToQueue()
                },
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_to_playlist)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismissRequest()
                    onAddToPlaylist()
                },
            )

            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_from_playlist)) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.PlaylistRemove,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        onDismissRequest()
                        onRemoveFromPlaylist()
                    },
                )
            }

            val hasGoTo = onGoToAlbum != null || onGoToArtist != null || onGoToFolder != null
            if (hasGoTo) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.go_to)) },
                    trailingIcon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        showingSubMenu = true
                    },
                )
            }

            DropdownMenuItem(
                text = { Text(stringResource(R.string.tag_editor)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismissRequest()
                    onTagEditor()
                },
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismissRequest()
                    onShare()
                },
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_from_device)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismissRequest()
                    onDeleteFromDevice()
                },
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.details)) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismissRequest()
                    onDetails()
                },
            )
        } else {
            // "Go to" submenu
            DropdownMenuItem(
                text = { Text(stringResource(R.string.go_to), style = MaterialTheme.typography.titleSmall) },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    showingSubMenu = false
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (onGoToAlbum != null && !song.albumName.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text(song.albumName) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Album,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        showingSubMenu = false
                        onDismissRequest()
                        onGoToAlbum(song.albumName)
                    },
                )
            }

            if (onGoToArtist != null && song.artist.isNotBlank()) {
                val artists = ArtistSplitter.split(song.artist)
                if (artists.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text(song.artist) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            showingSubMenu = false
                            onDismissRequest()
                            onGoToArtist(song.artist)
                        },
                    )
                } else {
                    artists.forEach { artistName ->
                        DropdownMenuItem(
                            text = { Text(artistName) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            onClick = {
                                showingSubMenu = false
                                onDismissRequest()
                                onGoToArtist(artistName)
                            },
                        )
                    }
                }
            }

            if (onGoToFolder != null && !song.localPath.isNullOrBlank()) {
                val folderPath = song.localPath.substringBeforeLast('/')
                val folderName = folderPath.substringAfterLast('/')
                DropdownMenuItem(
                    text = { Text(folderName) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        showingSubMenu = false
                        onDismissRequest()
                        onGoToFolder(folderPath)
                    },
                )
            }
        }
        }
    }
}
