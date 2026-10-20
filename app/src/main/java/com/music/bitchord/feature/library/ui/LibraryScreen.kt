package com.music.bitchord.feature.library.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.sortedByAlbumTrackOrder
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.feature.library.ui.components.LibraryFilter
import com.music.bitchord.feature.library.ui.components.LibraryGridContent
import com.music.bitchord.feature.library.ui.components.LibraryHeaderBar
import com.music.bitchord.feature.library.ui.components.LibraryListContent
import com.music.bitchord.feature.localmusic.data.LocalFavoritesStore
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.feature.localmusic.data.resolvePlaylistSongs
import com.music.bitchord.feature.localsongactions.data.LocalPlayStatsStore
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import com.music.bitchord.feature.artistimage.util.ArtistSplitter
import com.music.bitchord.feature.localmusic.ui.components.CreatePlaylistDialog
import com.music.bitchord.feature.localsongactions.ui.components.LocalAddToPlaylistSheet
import com.music.bitchord.feature.localsongactions.ui.components.LocalSongDropdownMenu
import com.music.bitchord.feature.localsongactions.ui.LocalSongActionsHelper
import com.music.bitchord.feature.tageditor.data.TagLibWriter
import kotlinx.coroutines.launch
import com.music.bitchord.feature.localmusic.ui.components.DrillDownSongList
import com.music.bitchord.feature.localmusic.ui.components.rememberPlaylistCoverPicker
import com.music.bitchord.ui.components.FastScroller
import com.music.bitchord.ui.components.SectionIndexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun PlaylistSelectionToolbar(
    count: Int,
    contentColor: Color,
    onAddToPlaylist: () -> Unit,
    onEditTags: () -> Unit,
    onShare: () -> Unit,
    onDeleteFromPlaylist: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    reorderEnabled: Boolean,
    onToggleReorder: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$count ${stringResource(R.string.selected)}",
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            modifier = Modifier.padding(end = 4.dp),
            maxLines = 1,
        )
        IconButton(onClick = onAddToPlaylist) {
            Icon(Icons.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.add_to_playlist), tint = contentColor)
        }
        IconButton(onClick = onEditTags) {
            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.selection_edit_tags), tint = contentColor)
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.selection_share), tint = contentColor)
        }
        IconButton(onClick = onDeleteFromPlaylist) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.remove_from_playlist),
                tint = contentColor,
            )
        }
        IconButton(onClick = onToggleReorder) {
            Icon(
                Icons.Rounded.Reorder,
                contentDescription = stringResource(R.string.toggle_playlist_reorder),
                tint = contentColor,
            )
        }
        IconButton(onClick = onMoveUp) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.selection_move_up), tint = contentColor)
        }
        IconButton(onClick = onMoveDown) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.selection_move_down), tint = contentColor)
        }
        TextButton(onClick = onMoveToTop) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = contentColor)
            Text(stringResource(R.string.selection_move_to_top), color = contentColor)
        }
        TextButton(onClick = onMoveToBottom) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = contentColor)
            Text(stringResource(R.string.selection_move_to_bottom), color = contentColor)
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel), tint = contentColor)
        }
    }
}

/**
 * Modern Library screen unifying user Favorites and Playlists with list/grid toggle.
 */
@Composable
fun LibraryScreen(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onPlayNext: ((Song) -> Unit)? = null,
    onAddToQueue: ((Song) -> Unit)? = null,
    onDeleteSong: ((Song) -> Unit)? = null,
    onSongTagsOrLyricsSaved: ((Song) -> Unit)? = null,
    songDropdownMenu: (@Composable (Song) -> Unit)? = null,
    initialFilterRequest: LibraryFilter? = null,
    onInitialFilterApplied: () -> Unit = {},
    onArtworkPaletteChanged: ((ArtworkPalette?) -> Unit)? = null,
    onCollectionNavigationChanged: ((Boolean, (() -> Unit)?) -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val playlists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val favoriteIds by LocalFavoritesStore.favoriteIds.collectAsStateWithLifecycle()
    val libraryViewType by AppSettings.libraryPlaylistsViewType.collectAsStateWithLifecycle()
    val songsViewType by AppSettings.librarySongsViewType.collectAsStateWithLifecycle()
    val showPlaylistSongArtwork by AppSettings.showPlaylistSongArtwork.collectAsStateWithLifecycle()
    val fullScreenCollectionArtwork by AppSettings.fullScreenCollectionArtwork.collectAsStateWithLifecycle()
    val keepCollectionArtworkAspectRatio by AppSettings.keepCollectionArtworkAspectRatio.collectAsStateWithLifecycle()
    val playlistTrackMenuUsesArtworkColor by AppSettings.playlistTrackMenuUsesArtworkColor.collectAsStateWithLifecycle()
    val playStatsRevision by LocalPlayStatsStore.revision.collectAsStateWithLifecycle()
    var recentlyPlayedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var mostPlayedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    val choosePlaylistCover = rememberPlaylistCoverPicker { playlistId, uri ->
        LocalPlaylistStore.setPlaylistCover(playlistId, uri)
    }

    LaunchedEffect(songs, playStatsRevision) {
        val ranked = withContext(Dispatchers.IO) {
            songs.map { song ->
                song to LocalPlayStatsStore.getStats(context, song.localUri ?: song.videoId)
            }.filter { (_, stats) -> stats.playedCount > 0 }
        }
        recentlyPlayedSongs = ranked
            .sortedByDescending { it.second.lastPlayedTimestamp }
            .map { it.first }
        mostPlayedSongs = ranked
            .sortedWith(compareByDescending<Pair<Song, com.music.bitchord.feature.localsongactions.domain.model.LocalPlayStats>> { it.second.playedCount }
                .thenByDescending { it.second.lastPlayedTimestamp })
            .map { it.first }
    }

    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    val collectionListState = rememberLazyListState()
    val collectionGridState = rememberLazyGridState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<LocalPlaylist?>(null) }

    // Drill down navigation inside Library (e.g. for Favorites or specific Playlist)
    var drillDownLabel by remember { mutableStateOf<String?>(null) }
    var drillDownSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var drillDownArt by remember { mutableStateOf<String?>(null) }
    var drillDownPlaylistId by remember { mutableStateOf<String?>(null) }
    var playlistReorderEnabled by rememberSaveable { mutableStateOf(false) }
    var playlistSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedPlaylistSongIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var activePlaylistMenuSong by remember { mutableStateOf<Song?>(null) }
    var showDeleteFromPlaylistDialog by remember { mutableStateOf(false) }
    var showAddSelectedToPlaylist by remember { mutableStateOf(false) }
    var showBulkTagEditor by remember { mutableStateOf(false) }
    var bulkArtist by remember { mutableStateOf("") }
    var bulkAlbumArtist by remember { mutableStateOf("") }
    var bulkAlbum by remember { mutableStateOf("") }
    var bulkGenre by remember { mutableStateOf("") }
    var bulkYear by remember { mutableStateOf("") }
    var bulkComposer by remember { mutableStateOf("") }
    var pendingBulkTagSongs by remember { mutableStateOf<List<Song>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun writeBulkTags(targets: List<Song>) {
        val updated = withContext(Dispatchers.IO) {
            targets.count { song ->
                runCatching {
                    val current = TagLibWriter.readTags(context, song)
                    TagLibWriter.writeTags(
                        context,
                        song,
                        current.copy(
                            artist = bulkArtist.trim().ifBlank { current.artist },
                            albumArtist = bulkAlbumArtist.trim().ifBlank { current.albumArtist },
                            album = bulkAlbum.trim().ifBlank { current.album },
                            genre = bulkGenre.trim().ifBlank { current.genre },
                            year = bulkYear.trim().ifBlank { current.year },
                            composer = bulkComposer.trim().ifBlank { current.composer },
                        ),
                    )
                }.getOrDefault(false)
            }
        }
        Toast.makeText(context, context.getString(R.string.bulk_tag_saved, updated, targets.size), Toast.LENGTH_LONG).show()
        targets.forEach { onSongTagsOrLyricsSaved?.invoke(it) }
        showBulkTagEditor = false
        bulkArtist = ""
        bulkAlbumArtist = ""
        bulkAlbum = ""
        bulkGenre = ""
        bulkYear = ""
        bulkComposer = ""
    }

    val bulkTagWritePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val targets = pendingBulkTagSongs.orEmpty()
        pendingBulkTagSongs = null
        if (result.resultCode == Activity.RESULT_OK && targets.isNotEmpty()) {
            scope.launch { writeBulkTags(targets) }
        } else if (targets.isNotEmpty()) {
            Toast.makeText(context, context.getString(R.string.bulk_tag_permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    fun saveBulkTags(targets: List<Song>) {
        val contentUris = targets.mapNotNull { song ->
            (song.localUri ?: song.videoId)
                .takeIf { it.startsWith("content://") }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        }.distinct()
        val needsApproval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            contentUris.filter { uri ->
                context.checkUriPermission(
                    uri,
                    Process.myPid(),
                    Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        } else emptyList()
        // Keep the API guard next to the API 30 call as well as around the
        // query above. This avoids invoking createWriteRequest on API 26–29.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && needsApproval.isNotEmpty()) {
            val request = runCatching { MediaStore.createWriteRequest(context.contentResolver, needsApproval) }.getOrNull()
            if (request != null) {
                pendingBulkTagSongs = targets
                bulkTagWritePermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
                return
            }
        }
        scope.launch { writeBulkTags(targets) }
    }

    LaunchedEffect(drillDownPlaylistId) {
        playlistSelectionMode = false
        selectedPlaylistSongIds = emptySet()
        activePlaylistMenuSong = null
        showDeleteFromPlaylistDialog = false
    }

    LaunchedEffect(initialFilterRequest) {
        val requestedFilter = initialFilterRequest ?: return@LaunchedEffect
        selectedFilter = requestedFilter
        onInitialFilterApplied()
    }

    val inDrillDown = drillDownLabel != null
    SideEffect {
        if (!inDrillDown || !fullScreenCollectionArtwork) {
            onArtworkPaletteChanged?.invoke(null)
        }
    }
    val inHistoryView = selectedFilter == LibraryFilter.RECENTLY_PLAYED ||
        selectedFilter == LibraryFilter.MOST_PLAYED
    val selectedPlaylistSongs = remember(drillDownSongs, selectedPlaylistSongIds) {
        drillDownSongs.filter { (it.localUri ?: it.videoId) in selectedPlaylistSongIds }
    }
    fun togglePlaylistSongSelection(song: Song) {
        val key = song.localUri ?: song.videoId
        selectedPlaylistSongIds = if (key in selectedPlaylistSongIds) {
            selectedPlaylistSongIds - key
        } else {
            selectedPlaylistSongIds + key
        }
        if (selectedPlaylistSongIds.isEmpty()) playlistSelectionMode = false
    }
    fun isPlaylistSongSelected(song: Song): Boolean =
        (song.localUri ?: song.videoId) in selectedPlaylistSongIds

    fun persistPlaylistSongOrder(playlistId: String, orderedSongs: List<Song>) {
        val storedIds = LocalPlaylistStore.getPlaylist(playlistId)?.songIds ?: return
        val usedIndices = mutableSetOf<Int>()
        val reorderedResolvedIds = orderedSongs.mapNotNull { song ->
            val storedIndex = storedIds.indices.firstOrNull { index ->
                index !in usedIndices && storedIds[index] in setOf(song.localUri, song.videoId)
            } ?: return@mapNotNull null
            usedIndices += storedIndex
            storedIds[storedIndex]
        }
        if (reorderedResolvedIds.isNotEmpty()) {
            // Keep entries that could not be resolved in their existing slots;
            // reordering playable tracks must never delete inaccessible playlist entries.
            val resolvedSlots = usedIndices.sorted()
            val persistedOrder = storedIds.toMutableList()
            resolvedSlots.forEachIndexed { order, slot ->
                persistedOrder[slot] = reorderedResolvedIds[order]
            }
            if (persistedOrder != storedIds) LocalPlaylistStore.setSongOrder(playlistId, persistedOrder)
        }
    }
    fun persistSelectedOrder(toTop: Boolean) {
        val playlistId = drillDownPlaylistId ?: return
        val selected = drillDownSongs.filter(::isPlaylistSongSelected)
        if (selected.isEmpty()) return
        val remaining = drillDownSongs.filterNot(::isPlaylistSongSelected)
        val reordered = if (toTop) selected + remaining else remaining + selected
        if (reordered == drillDownSongs) return
        drillDownSongs = reordered
        persistPlaylistSongOrder(playlistId, reordered)
    }
    fun moveSelectedSongsByOne(direction: Int) {
        if (direction != -1 && direction != 1) return
        val playlistId = drillDownPlaylistId ?: return
        if (selectedPlaylistSongIds.isEmpty()) return
        val reordered = drillDownSongs.toMutableList()
        if (direction < 0) {
            for (index in 1..reordered.lastIndex) {
                if (isPlaylistSongSelected(reordered[index]) && !isPlaylistSongSelected(reordered[index - 1])) {
                    val moving = reordered[index]
                    reordered[index] = reordered[index - 1]
                    reordered[index - 1] = moving
                }
            }
        } else {
            for (index in reordered.lastIndex - 1 downTo 0) {
                if (isPlaylistSongSelected(reordered[index]) && !isPlaylistSongSelected(reordered[index + 1])) {
                    val moving = reordered[index]
                    reordered[index] = reordered[index + 1]
                    reordered[index + 1] = moving
                }
            }
        }
        if (reordered == drillDownSongs) return
        drillDownSongs = reordered
        persistPlaylistSongOrder(playlistId, reordered)
    }
    val playlistSongDropdownMenu: @Composable (Song) -> Unit = { song ->
        val songKey = song.localUri ?: song.videoId
        LocalSongDropdownMenu(
            expanded = activePlaylistMenuSong?.let { it.localUri ?: it.videoId } == songKey,
            onDismissRequest = { activePlaylistMenuSong = null },
            song = song,
            onQueueNext = {
                activePlaylistMenuSong = null
                onPlayNext?.invoke(song) ?: onSongSwipe(song)
            },
            onAddToQueue = {
                activePlaylistMenuSong = null
                onAddToQueue?.invoke(song) ?: onSongSwipe(song)
            },
            onAddToPlaylist = {
                activePlaylistMenuSong = null
                selectedPlaylistSongIds = setOf(songKey)
                showAddSelectedToPlaylist = true
            },
            artworkUrlForPalette = drillDownArt.takeIf { playlistTrackMenuUsesArtworkColor },
            onRemoveFromPlaylist = if (drillDownPlaylistId != null) {
                {
                    activePlaylistMenuSong = null
                    val playlistId = drillDownPlaylistId ?: return@LocalSongDropdownMenu
                    LocalPlaylistStore.removeSongFromPlaylist(playlistId, songKey)
                    drillDownSongs = drillDownSongs.filterNot { (it.localUri ?: it.videoId) == songKey }
                    Toast.makeText(context, R.string.remove_from_playlist, Toast.LENGTH_SHORT).show()
                }
            } else null,
            onGoToAlbum = song.albumName?.takeIf(String::isNotBlank)?.let {
                { album ->
                    activePlaylistMenuSong = null
                    drillDownPlaylistId = null
                    playlistSelectionMode = false
                    selectedPlaylistSongIds = emptySet()
                    drillDownLabel = album
                    drillDownSongs = songs
                        .filter { it.albumName.equals(album, ignoreCase = true) }
                        .sortedByAlbumTrackOrder()
                    drillDownArt = drillDownSongs.firstNotNullOfOrNull { it.thumbnailUrl }
                }
            },
            onGoToArtist = song.artist.takeIf(String::isNotBlank)?.let {
                { artist ->
                    activePlaylistMenuSong = null
                    drillDownPlaylistId = null
                    playlistSelectionMode = false
                    selectedPlaylistSongIds = emptySet()
                    drillDownLabel = artist
                    drillDownSongs = songs.filter { ArtistSplitter.matchesArtist(it.artist, artist) }
                    drillDownArt = null
                }
            },
            onGoToFolder = song.localPath?.takeIf(String::isNotBlank)?.let {
                { folder ->
                    activePlaylistMenuSong = null
                    drillDownPlaylistId = null
                    playlistSelectionMode = false
                    selectedPlaylistSongIds = emptySet()
                    drillDownLabel = folder.substringAfterLast('/')
                    drillDownSongs = songs.filter { (it.localPath ?: "").substringBeforeLast('/') == folder }
                    drillDownArt = null
                }
            },
            onTagEditor = {
                activePlaylistMenuSong = null
                onSongLongPress(song)
            },
            onShare = {
                activePlaylistMenuSong = null
                LocalSongActionsHelper.shareSong(context, song)
            },
            onDeleteFromDevice = {
                activePlaylistMenuSong = null
                onDeleteSong?.invoke(song)
            },
            onDetails = {
                activePlaylistMenuSong = null
                onSongLongPress(song)
            },
        )
    }
    val leaveDrillDown = {
        activePlaylistMenuSong = null
        showDeleteFromPlaylistDialog = false
        drillDownLabel = null
        drillDownSongs = emptyList()
        drillDownArt = null
        drillDownPlaylistId = null
        playlistReorderEnabled = false
        playlistSelectionMode = false
        selectedPlaylistSongIds = emptySet()
    }

    val collectionNavigationCallback = rememberUpdatedState(onCollectionNavigationChanged)
    val currentCollectionBackHandler = rememberUpdatedState(leaveDrillDown)
    val collectionBackHandler = remember { { currentCollectionBackHandler.value() } }
    LaunchedEffect(inDrillDown) {
        collectionNavigationCallback.value?.invoke(
            inDrillDown,
            collectionBackHandler.takeIf { inDrillDown },
        )
    }
    DisposableEffect(Unit) {
        onDispose { collectionNavigationCallback.value?.invoke(false, null) }
    }

    BackHandler(enabled = inDrillDown || inHistoryView) {
        if (inDrillDown) leaveDrillDown() else selectedFilter = LibraryFilter.ALL
    }

    // Filter favorite songs matching persisted favorite IDs
    val favoriteSongs = remember(songs, favoriteIds) {
        songs.filter { s ->
            val localKey = s.localUri ?: s.videoId
            localKey in favoriteIds || s.videoId in favoriteIds
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = if (inDrillDown) "drill:$drillDownLabel" else when (selectedFilter) {
                LibraryFilter.RECENTLY_PLAYED -> "history:recent"
                LibraryFilter.MOST_PLAYED -> "history:most"
                else -> "library_main"
            },
            transitionSpec = {
                if (targetState.startsWith("drill:")) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "library_content_transition",
            modifier = Modifier.fillMaxSize(),
        ) { key ->
            if (key.startsWith("drill:")) {
                DrillDownSongList(
                    label = drillDownLabel.orEmpty(),
                    artworkUrl = drillDownArt,
                    songs = drillDownSongs,
                    isArtist = false,
                    isPlaylist = drillDownPlaylistId != null,
                    showArtworkInList = showPlaylistSongArtwork,
                    fullScreenArtwork = fullScreenCollectionArtwork,
                    preserveArtworkAspectRatioInCollections = keepCollectionArtworkAspectRatio,
                    viewType = songsViewType,
                    onViewTypeToggle = {
                        playlistReorderEnabled = false
                        val next = if (songsViewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                        AppSettings.setLibrarySongsViewType(next)
                    },
                    selectionMode = playlistSelectionMode && drillDownPlaylistId != null,
                    onSelectionToggle = ::togglePlaylistSongSelection,
                    selectionToolbar = if (playlistSelectionMode && drillDownPlaylistId != null) {
                        { contentColor ->
                            PlaylistSelectionToolbar(
                                count = selectedPlaylistSongIds.size,
                                contentColor = contentColor,
                                onAddToPlaylist = { showAddSelectedToPlaylist = true },
                                onEditTags = { showBulkTagEditor = true },
                                onShare = { LocalSongActionsHelper.shareSongs(context, selectedPlaylistSongs) },
                                onDeleteFromPlaylist = { showDeleteFromPlaylistDialog = true },
                                onMoveUp = { moveSelectedSongsByOne(-1) },
                                onMoveDown = { moveSelectedSongsByOne(1) },
                                onMoveToTop = { persistSelectedOrder(toTop = true) },
                                onMoveToBottom = { persistSelectedOrder(toTop = false) },
                                reorderEnabled = playlistReorderEnabled,
                                onToggleReorder = {
                                    if (playlistReorderEnabled) {
                                        playlistReorderEnabled = false
                                    } else {
                                        if (songsViewType != LibraryViewType.LIST) {
                                            AppSettings.setLibrarySongsViewType(LibraryViewType.LIST)
                                        }
                                        playlistReorderEnabled = true
                                    }
                                },
                                onCancel = {
                                    playlistSelectionMode = false
                                    selectedPlaylistSongIds = emptySet()
                                },
                            )
                        }
                    } else null,
                    selectedIds = selectedPlaylistSongIds,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = { list, idx -> onSongClick(list, idx) },
                    onSongLongPress = { song ->
                        if (drillDownPlaylistId != null) {
                            val key = song.localUri ?: song.videoId
                            if (!playlistSelectionMode) {
                                playlistReorderEnabled = false
                                playlistSelectionMode = true
                                selectedPlaylistSongIds = setOf(key)
                            } else {
                                togglePlaylistSongSelection(song)
                            }
                        } else {
                            onSongLongPress(song)
                        }
                    },
                    // Selection changes the row tap and long-press behavior,
                    // but the trailing details/actions button stays available.
                    onSongMore = if (drillDownPlaylistId != null) {
                        { song -> activePlaylistMenuSong = song }
                    } else onSongLongPress,
                    onSongSwipe = onSongSwipe,
                    onShuffle = onShuffle,
                    onMore = null,
                    reorderEnabled = playlistReorderEnabled && drillDownPlaylistId != null,
                    onReorderToggle = if (drillDownPlaylistId != null) {
                        {
                            if (playlistReorderEnabled) {
                                playlistReorderEnabled = false
                            } else {
                                if (songsViewType != LibraryViewType.LIST) {
                                    AppSettings.setLibrarySongsViewType(LibraryViewType.LIST)
                                }
                                playlistReorderEnabled = true
                            }
                        }
                    } else null,
                    onReorderComplete = { reorderedSongs ->
                        val playlistId = drillDownPlaylistId
                        if (playlistId != null) {
                            drillDownSongs = reorderedSongs
                            persistPlaylistSongOrder(playlistId, reorderedSongs)
                        }
                    },
                    onBack = leaveDrillDown,
                    contentPadding = contentPadding,
                    songDropdownMenu = if (drillDownPlaylistId != null) playlistSongDropdownMenu else songDropdownMenu,
                    onArtworkPaletteChanged = onArtworkPaletteChanged,
                )
            } else if (key.startsWith("history:")) {
                val isRecent = key == "history:recent"
                val historySongs = if (isRecent) recentlyPlayedSongs else mostPlayedSongs
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentPadding.calculateTopPadding()),
                ) {
                    LibraryHeaderBar(
                        selectedFilter = selectedFilter,
                        onFilterSelect = { selectedFilter = it },
                        viewType = libraryViewType,
                        onToggleViewType = {
                            val next = if (libraryViewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                            AppSettings.setLibraryPlaylistsViewType(next)
                        },
                        onCreatePlaylist = { showCreateDialog = true },
                    )
                    if (historySongs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(
                                text = stringResource(R.string.no_play_history),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        DrillDownSongList(
                            label = stringResource(if (isRecent) R.string.recently_played else R.string.most_played),
                            artworkUrl = null,
                            songs = historySongs,
                            fullScreenArtwork = false,
                            viewType = songsViewType,
                            onViewTypeToggle = {
                                val next = if (songsViewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                                AppSettings.setLibrarySongsViewType(next)
                            },
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            onSongClick = onSongClick,
                            onSongLongPress = onSongLongPress,
                            onSongMore = onSongLongPress,
                            onSongSwipe = onSongSwipe,
                            onShuffle = onShuffle,
                            showArtworkInList = showPlaylistSongArtwork,
                            onBack = { selectedFilter = LibraryFilter.ALL },
                            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                            songDropdownMenu = songDropdownMenu,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = contentPadding.calculateTopPadding()),
                ) {
                    LibraryHeaderBar(
                        selectedFilter = selectedFilter,
                        onFilterSelect = { selectedFilter = it },
                        viewType = libraryViewType,
                        onToggleViewType = {
                            val next = if (libraryViewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                            AppSettings.setLibraryPlaylistsViewType(next)
                        },
                        onCreatePlaylist = { showCreateDialog = true },
                    )

                    val onFavoritesClick = {
                        drillDownPlaylistId = null
                        playlistReorderEnabled = false
                        drillDownLabel = "Favorites"
                        drillDownSongs = favoriteSongs
                        drillDownArt = "favorites"
                    }

                    val onFavoritesPlay = {
                        if (favoriteSongs.isNotEmpty()) {
                            onSongClick(favoriteSongs, 0)
                        }
                    }

                    val onPlaylistClick: (LocalPlaylist) -> Unit = { playlist ->
                        drillDownPlaylistId = playlist.id
                        playlistReorderEnabled = false
                        drillDownLabel = playlist.name
                        val pSongs = resolvePlaylistSongs(playlist, songs)
                        drillDownSongs = pSongs
                        drillDownArt = playlist.customCoverUrl ?: playlist.coverUrl ?: pSongs.firstNotNullOfOrNull { it.thumbnailUrl }
                    }

                    val onPlaylistPlay: (LocalPlaylist) -> Unit = { playlist ->
                        val pSongs = resolvePlaylistSongs(playlist, songs)
                        if (pSongs.isNotEmpty()) {
                            onSongClick(pSongs, 0)
                        }
                    }

                    if (libraryViewType == LibraryViewType.GRID) {
                        LibraryGridContent(
                            filter = selectedFilter,
                            favoriteSongCount = favoriteSongs.size,
                            playlists = playlists,
                            onFavoritesClick = onFavoritesClick,
                            onFavoritesPlay = onFavoritesPlay,
                            onPlaylistClick = onPlaylistClick,
                            onPlaylistPlay = onPlaylistPlay,
                            onRenamePlaylist = { playlistToRename = it },
                            onDeletePlaylist = { LocalPlaylistStore.deletePlaylist(it.id) },
                            onChangePlaylistCover = { choosePlaylistCover(it.id) },
                            onResetPlaylistCover = { LocalPlaylistStore.setPlaylistCover(it.id, null) },
                            onMovePlaylist = { playlist, offset -> LocalPlaylistStore.movePlaylist(playlist.id, offset) },
                            onCreatePlaylist = { showCreateDialog = true },
                            librarySongs = songs,
                            contentPadding = PaddingValues(
                                top = 0.dp,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                            state = collectionGridState,
                        )
                    } else {
                        LibraryListContent(
                            filter = selectedFilter,
                            favoriteSongCount = favoriteSongs.size,
                            playlists = playlists,
                            onFavoritesClick = onFavoritesClick,
                            onFavoritesPlay = onFavoritesPlay,
                            onPlaylistClick = onPlaylistClick,
                            onPlaylistPlay = onPlaylistPlay,
                            onRenamePlaylist = { playlistToRename = it },
                            onDeletePlaylist = { LocalPlaylistStore.deletePlaylist(it.id) },
                            onChangePlaylistCover = { choosePlaylistCover(it.id) },
                            onResetPlaylistCover = { LocalPlaylistStore.setPlaylistCover(it.id, null) },
                            onMovePlaylist = { playlist, offset -> LocalPlaylistStore.movePlaylist(playlist.id, offset) },
                            onCreatePlaylist = { showCreateDialog = true },
                            librarySongs = songs,
                            contentPadding = PaddingValues(
                                top = 0.dp,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                            state = collectionListState,
                        )
                    }
                }
            }
        }
        val showFavoriteCollection = selectedFilter == LibraryFilter.ALL || selectedFilter == LibraryFilter.FAVORITES
        val showPlaylistCollections = selectedFilter == LibraryFilter.ALL || selectedFilter == LibraryFilter.PLAYLISTS
        val favoriteLabel = stringResource(R.string.favorites)
        val createPlaylistLabel = stringResource(R.string.new_playlist)
        val listCollectionLabels = buildList {
            if (showFavoriteCollection) add(favoriteLabel)
            if (showPlaylistCollections) addAll(playlists.map { it.name })
        }
        val gridCollectionLabels = buildList {
            addAll(listCollectionLabels)
            if (showPlaylistCollections) add(createPlaylistLabel)
        }
        if (!inDrillDown && !inHistoryView && listCollectionLabels.isNotEmpty()) {
            if (libraryViewType == LibraryViewType.GRID) {
                FastScroller(
                    gridState = collectionGridState,
                    itemCount = gridCollectionLabels.size,
                    sectionNameForIndex = { index ->
                        SectionIndexer.getSectionName(gridCollectionLabels.getOrNull(index))
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd),
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 80.dp,
                    ),
                )
            } else {
                FastScroller(
                    listState = collectionListState,
                    itemCount = listCollectionLabels.size,
                    headerCount = 1,
                    sectionNameForIndex = { index ->
                        SectionIndexer.getSectionName(listCollectionLabels.getOrNull(index))
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd),
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 80.dp,
                    ),
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            title = "New Playlist",
            confirmText = "Create",
            onConfirm = { name ->
                LocalPlaylistStore.createPlaylist(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    playlistToRename?.let { target ->
        CreatePlaylistDialog(
            initialName = target.name,
            title = "Rename Playlist",
            confirmText = "Save",
            onConfirm = { newName ->
                LocalPlaylistStore.renamePlaylist(target.id, newName)
                playlistToRename = null
            },
            onDismiss = { playlistToRename = null },
        )
    }

    if (showAddSelectedToPlaylist && selectedPlaylistSongs.isNotEmpty()) {
        LocalAddToPlaylistSheet(
            songs = selectedPlaylistSongs,
            onDismissRequest = {
                showAddSelectedToPlaylist = false
                playlistSelectionMode = false
                selectedPlaylistSongIds = emptySet()
            },
        )
    }

    if (showDeleteFromPlaylistDialog && drillDownPlaylistId != null) {
        val playlistId = drillDownPlaylistId!!
        val selectedIds = selectedPlaylistSongIds
        AlertDialog(
            onDismissRequest = { showDeleteFromPlaylistDialog = false },
            title = { Text(stringResource(R.string.remove_from_playlist)) },
            text = {
                Text(stringResource(R.string.remove_playlist_tracks_confirmation, selectedIds.size))
            },
            confirmButton = {
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = {
                        LocalPlaylistStore.removeSongsFromPlaylist(playlistId, selectedIds)
                        drillDownSongs = drillDownSongs.filterNot(::isPlaylistSongSelected)
                        selectedPlaylistSongIds = emptySet()
                        playlistSelectionMode = false
                        showDeleteFromPlaylistDialog = false
                        Toast.makeText(context, R.string.remove_from_playlist, Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteFromPlaylistDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showBulkTagEditor) {
        AlertDialog(
            onDismissRequest = { showBulkTagEditor = false },
            title = { Text(stringResource(R.string.bulk_tag_edit)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.bulk_tag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(bulkArtist, { bulkArtist = it }, label = { Text(stringResource(R.string.bulk_tag_artist)) }, singleLine = true)
                    OutlinedTextField(bulkAlbumArtist, { bulkAlbumArtist = it }, label = { Text(stringResource(R.string.bulk_tag_album_artist)) }, singleLine = true)
                    OutlinedTextField(bulkAlbum, { bulkAlbum = it }, label = { Text(stringResource(R.string.bulk_tag_album)) }, singleLine = true)
                    OutlinedTextField(bulkGenre, { bulkGenre = it }, label = { Text(stringResource(R.string.bulk_tag_genre)) }, singleLine = true)
                    OutlinedTextField(bulkYear, { bulkYear = it }, label = { Text(stringResource(R.string.bulk_tag_year)) }, singleLine = true)
                    OutlinedTextField(bulkComposer, { bulkComposer = it }, label = { Text(stringResource(R.string.bulk_tag_composer)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = listOf(bulkArtist, bulkAlbumArtist, bulkAlbum, bulkGenre, bulkYear, bulkComposer).any { it.isNotBlank() } && selectedPlaylistSongs.isNotEmpty(),
                    onClick = { saveBulkTags(selectedPlaylistSongs) },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkTagEditor = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
