package com.music.bitchord.feature.localsongactions.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.ui.components.ArtworkBackdrop
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylistSongMetadata
import com.music.bitchord.feature.localmusic.ui.components.CreatePlaylistDialog

/** Add device or catalogue tracks to an app-local playlist, or create one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAddToPlaylistSheet(
    songs: List<Song>,
    onDismissRequest: () -> Unit,
    artworkPalette: ArtworkPalette? = null,
    artworkUrl: String? = null,
    useArtworkColor: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playlists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    val distinctSongs = remember(songs) {
        songs.distinctBy { it.playlistEntryId() }
    }
    val songIds = remember(distinctSongs) { distinctSongs.map { it.playlistEntryId() } }
    val songMetadata = remember(distinctSongs) {
        distinctSongs.associate { song ->
            song.playlistEntryId() to LocalPlaylistSongMetadata(
                title = song.title,
                artist = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                albumName = song.albumName,
                albumArtist = song.albumArtist,
                artistId = song.artistId,
                albumId = song.albumId,
                durationText = song.durationText,
                isVideo = song.isVideo,
                isVideoOrigin = song.isVideoOrigin,
                localPath = song.localPath,
            )
        }
    }
    val coverUrl = distinctSongs.firstNotNullOfOrNull { it.thumbnailUrl }
    val palette = artworkPalette.takeIf { useArtworkColor }
    val baseColors = MaterialTheme.colorScheme
    val sheetColors = if (palette != null) {
        baseColors.copy(
            primary = palette.accent,
            onPrimary = if (palette.accent.luminance() > 0.52f) Color.Black else Color.White,
            primaryContainer = palette.elevated,
            onPrimaryContainer = palette.onBackground,
            background = palette.background,
            onBackground = palette.onBackground,
            surface = palette.background,
            onSurface = palette.onBackground,
            surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.onBackgroundVariant,
            outline = palette.divider,
            outlineVariant = palette.divider,
            surfaceContainerLow = palette.elevated,
            surfaceContainerHigh = palette.elevated,
            surfaceContainerHighest = palette.elevated,
        )
    } else baseColors

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                val newPl = LocalPlaylistStore.createPlaylist(
                    name = name,
                    songIds = songIds,
                    songMetadata = songMetadata,
                    coverUrl = coverUrl,
                )
                val message = context.resources.getQuantityString(
                    R.plurals.local_playlist_songs_added,
                    songIds.size,
                    songIds.size,
                    newPl.name,
                )
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                showCreateDialog = false
                onDismissRequest()
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = palette?.background ?: MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        ) {
            if (palette != null) {
                ArtworkBackdrop(
                    palette = palette,
                    imageUrl = artworkUrl ?: coverUrl,
                    modifier = Modifier.matchParentSize(),
                    washFraction = 0.75f,
                    artPx = ROW_ART_PX,
                )
            }
            MaterialTheme(colorScheme = sheetColors) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.PlaylistAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.add_to_playlist),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            )
                        }

                        IconButton(onClick = onDismissRequest) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCreateDialog = true }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.new_playlist),
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))

                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val addedCount = LocalPlaylistStore.addSongsToPlaylist(
                                            playlistId = playlist.id,
                                            songIds = songIds,
                                            songMetadata = songMetadata,
                                            artworkUrl = coverUrl,
                                        )
                                        val message = context.resources.getQuantityString(
                                            R.plurals.local_playlist_songs_added,
                                            addedCount,
                                            addedCount,
                                            playlist.name,
                                        )
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        onDismissRequest()
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.local_playlist_selected_count,
                                            playlist.songIds.size,
                                            distinctSongs.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Store a file URI for local rows, and the source identity for remote rows. */
private fun Song.playlistEntryId(): String {
    val uri = localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    return if (uri?.scheme.equals("content", ignoreCase = true) || uri?.scheme.equals("file", ignoreCase = true)) {
        localUri.orEmpty()
    } else {
        videoId
    }
}
