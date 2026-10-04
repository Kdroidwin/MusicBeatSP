package com.music.bitchord.feature.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.feature.library.ui.components.LibraryFilter
import com.music.bitchord.feature.library.ui.components.LibraryGridContent
import com.music.bitchord.feature.library.ui.components.LibraryHeaderBar
import com.music.bitchord.feature.library.ui.components.LibraryListContent
import com.music.bitchord.feature.localmusic.data.LocalFavoritesStore
import com.music.bitchord.feature.localmusic.data.LocalPlaylistStore
import com.music.bitchord.feature.localmusic.data.resolvePlaylistSongs
import com.music.bitchord.feature.localsongactions.data.LocalPlayStatsStore
import com.music.bitchord.feature.localmusic.domain.model.LocalPlaylist
import com.music.bitchord.feature.localmusic.ui.components.CreatePlaylistDialog
import com.music.bitchord.feature.localmusic.ui.components.DrillDownSongList
import com.music.bitchord.feature.localmusic.ui.components.rememberPlaylistCoverPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
) {
    val context = LocalContext.current.applicationContext
    val playlists by LocalPlaylistStore.playlists.collectAsStateWithLifecycle()
    val favoriteIds by LocalFavoritesStore.favoriteIds.collectAsStateWithLifecycle()
    val libraryViewType by AppSettings.libraryPlaylistsViewType.collectAsStateWithLifecycle()
    val songsViewType by AppSettings.librarySongsViewType.collectAsStateWithLifecycle()
    val showPlaylistSongArtwork by AppSettings.showPlaylistSongArtwork.collectAsStateWithLifecycle()
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
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<LocalPlaylist?>(null) }

    // Drill down navigation inside Library (e.g. for Favorites or specific Playlist)
    var drillDownLabel by remember { mutableStateOf<String?>(null) }
    var drillDownSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var drillDownArt by remember { mutableStateOf<String?>(null) }
    var drillDownPlaylistId by remember { mutableStateOf<String?>(null) }
    var playlistReorderEnabled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(initialFilterRequest) {
        val requestedFilter = initialFilterRequest ?: return@LaunchedEffect
        selectedFilter = requestedFilter
        onInitialFilterApplied()
    }

    val inDrillDown = drillDownLabel != null
    val inHistoryView = selectedFilter == LibraryFilter.RECENTLY_PLAYED ||
        selectedFilter == LibraryFilter.MOST_PLAYED
    val leaveDrillDown = {
        drillDownLabel = null
        drillDownSongs = emptyList()
        drillDownArt = null
        drillDownPlaylistId = null
        playlistReorderEnabled = false
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
                    viewType = songsViewType,
                    onViewTypeToggle = {
                        playlistReorderEnabled = false
                        val next = if (songsViewType == LibraryViewType.GRID) LibraryViewType.LIST else LibraryViewType.GRID
                        AppSettings.setLibrarySongsViewType(next)
                    },
                    selectedIds = emptySet(),
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = { list, idx -> onSongClick(list, idx) },
                    onSongLongPress = onSongLongPress,
                    onSongMore = onSongLongPress,
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
                            LocalPlaylistStore.setSongOrder(
                                playlistId,
                                reorderedSongs.map { it.localUri ?: it.videoId },
                            )
                        }
                    },
                    onBack = leaveDrillDown,
                    contentPadding = contentPadding,
                    songDropdownMenu = songDropdownMenu,
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
                            contentPadding = PaddingValues(
                                top = 0.dp,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
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
                            contentPadding = PaddingValues(
                                top = 0.dp,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        )
                    }
                }
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
}
