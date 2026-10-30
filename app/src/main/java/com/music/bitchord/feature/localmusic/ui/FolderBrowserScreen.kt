package com.music.bitchord.feature.localmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.localmusic.domain.FolderNode
import com.music.bitchord.feature.localmusic.domain.FolderTreeBuilder
import com.music.bitchord.feature.localsongactions.ui.components.LocalAddToPlaylistSheet
import com.music.bitchord.ui.components.SongRow

private val FolderPathStackSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** Hierarchical browser for the device's indexed local music folders. */
@Composable
fun FolderBrowserScreen(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    contentPadding: PaddingValues,
    customRootPath: String? = null,
    onArtworkPaletteChanged: ((com.music.bitchord.ui.theme.ArtworkPalette?) -> Unit)? = null,
    onNavigationChanged: ((Boolean, (() -> Unit)?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tree = remember(songs, customRootPath) { FolderTreeBuilder.build(songs, customRootPath) }
    val rootKey = customRootPath ?: tree.path
    val pathStack = rememberSaveable(rootKey, saver = FolderPathStackSaver) {
        mutableStateListOf<String>()
    }
    val stackSnapshot = pathStack.toList()
    val currentNode by remember(tree, stackSnapshot) {
        mutableStateOf(stackSnapshot.lastOrNull()?.let(tree::findByPath) ?: tree)
    }
    var selectedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFolderPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addToPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }
    val selectingSongs = selectedSongIds.isNotEmpty() || selectedFolderPaths.isNotEmpty()
    val selectedSongs = remember(tree, currentNode.directSongs, selectedSongIds, selectedFolderPaths) {
        val folderTracks = selectedFolderPaths
            .asSequence()
            .mapNotNull(tree::findByPath)
            .flatMap { it.allSongs.asSequence() }
        (currentNode.directSongs.filter { (it.localUri ?: it.videoId) in selectedSongIds } + folderTracks)
            .distinctBy { it.localUri ?: it.localPath ?: it.videoId }
    }
    fun toggleSongSelection(song: Song) {
        val key = song.localUri ?: song.videoId
        selectedSongIds = if (key in selectedSongIds) selectedSongIds - key else selectedSongIds + key
    }
    fun toggleFolderSelection(folder: FolderNode) {
        selectedFolderPaths = if (folder.path in selectedFolderPaths) {
            selectedFolderPaths - folder.path
        } else {
            selectedFolderPaths + folder.path
        }
    }
    LaunchedEffect(tree, stackSnapshot) {
        if (stackSnapshot.isNotEmpty() && tree.findByPath(stackSnapshot.last()) == null) {
            pathStack.clear()
        }
    }
    LaunchedEffect(currentNode.path) {
        selectedSongIds = emptySet()
        selectedFolderPaths = emptySet()
    }

    val canGoBack = pathStack.isNotEmpty()
    val latestPathStack = rememberUpdatedState(pathStack)
    val popBack = remember {
        {
            val stack = latestPathStack.value
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
    }
    val latestNavigationCallback = rememberUpdatedState(onNavigationChanged)
    LaunchedEffect(canGoBack) {
        latestNavigationCallback.value?.invoke(canGoBack, popBack.takeIf { canGoBack })
    }
    DisposableEffect(Unit) {
        onDispose { latestNavigationCallback.value?.invoke(false, null) }
    }
    BackHandler(enabled = canGoBack, onBack = popBack)
    BackHandler(enabled = selectingSongs) {
        selectedSongIds = emptySet()
        selectedFolderPaths = emptySet()
    }
    SideEffect { onArtworkPaletteChanged?.invoke(null) }

    val scrollState = rememberLazyListState()
    LazyColumn(
        state = scrollState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectingSongs) {
            item(key = "folder-selection-toolbar:${currentNode.path}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectedFolderPaths.isNotEmpty()) {
                            stringResource(
                                R.string.folder_selection_summary,
                                selectedFolderPaths.size,
                                selectedSongs.size,
                            )
                        } else {
                            "${selectedSongs.size} ${stringResource(R.string.selected)}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(
                        enabled = selectedSongs.isNotEmpty(),
                        onClick = {
                            addToPlaylistSongs = selectedSongs
                            selectedSongIds = emptySet()
                            selectedFolderPaths = emptySet()
                        },
                    ) {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add_to_playlist))
                    }
                    IconButton(onClick = {
                        selectedSongIds = emptySet()
                        selectedFolderPaths = emptySet()
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            }
        }
        item(key = "folder-header:${currentNode.path}") {
            FolderHeader(
                node = currentNode,
                rootName = tree.name,
                onPlay = { if (currentNode.allSongs.isNotEmpty()) onSongClick(currentNode.allSongs, 0) },
                onShuffle = { if (currentNode.allSongs.isNotEmpty()) onShuffle(currentNode.allSongs) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }

        if (currentNode.subfolders.isEmpty() && currentNode.directSongs.isEmpty()) {
            item(key = "folder-empty:${currentNode.path}") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.folder_no_tracks),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(currentNode.subfolders, key = { "folder:${it.path}" }) { folder ->
            FolderEntry(
                folder = folder,
                selected = folder.path in selectedFolderPaths,
                onClick = {
                    if (selectingSongs) toggleFolderSelection(folder) else pathStack.add(folder.path)
                },
                onLongPress = { toggleFolderSelection(folder) },
            )
        }

        itemsIndexed(currentNode.directSongs, key = { _, song -> song.localPath ?: song.localUri ?: song.videoId }) { index, song ->
            SongRow(
                song = song,
                isCurrent = currentSong?.isSameFolderTrack(song) == true,
                isPlaying = isPlaying && currentSong?.isSameFolderTrack(song) == true,
                onClick = {
                    if (selectingSongs) toggleSongSelection(song)
                    else onSongClick(currentNode.directSongs, index)
                },
                onLongPress = { toggleSongSelection(song) },
                // Long press selects tracks on this screen; the explicit
                // overflow menu keeps its existing single-track actions.
                onMore = { onSongLongPress(song) },
                selected = (song.localUri ?: song.videoId) in selectedSongIds,
                onSwipeToQueue = if (selectingSongs) null else { { onSongSwipe(song) } },
            )
        }
    }

    addToPlaylistSongs?.let { selected ->
        LocalAddToPlaylistSheet(
            songs = selected,
            onDismissRequest = { addToPlaylistSongs = null },
        )
    }
}

@Composable
private fun FolderHeader(
    node: FolderNode,
    rootName: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackCount = pluralStringResource(R.plurals.track_count_plural, node.totalSongCount, node.totalSongCount)
    val folderCount = if (node.subfolders.isNotEmpty()) stringResource(R.string.folder_count, node.subfolders.size) else null
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = if (node.relativePath.isBlank()) rootName else node.relativePath.replace('/', '›'),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOfNotNull(trackCount, folderCount).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (node.totalSongCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(onClick = onShuffle, enabled = node.totalSongCount > 1) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.shuffle))
                }
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.play))
                }
            }
        }
    }
}

@Composable
private fun FolderEntry(
    folder: FolderNode,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val trackCount = pluralStringResource(R.plurals.track_count_plural, folder.totalSongCount, folder.totalSongCount)
    val subfolderCount = if (folder.subfolders.isNotEmpty()) {
        stringResource(R.string.folder_subfolder_count, folder.subfolders.size)
    } else null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                folder.coverSongs.firstOrNull()?.thumbnailUrl?.let { artwork ->
                    AsyncImage(
                        model = artwork.artworkAt(ROW_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(trackCount, subfolderCount).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun Song.isSameFolderTrack(other: Song): Boolean =
    if (!localPath.isNullOrBlank() && !other.localPath.isNullOrBlank()) {
        localPath.equals(other.localPath, ignoreCase = true)
    } else if (!localUri.isNullOrBlank() && !other.localUri.isNullOrBlank()) {
        localUri == other.localUri
    } else {
        videoId == other.videoId
    }
