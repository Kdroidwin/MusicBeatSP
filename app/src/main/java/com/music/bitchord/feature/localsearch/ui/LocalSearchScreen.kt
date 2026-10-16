package com.music.bitchord.feature.localsearch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.localmusic.ui.components.DrillDownSongList
import com.music.bitchord.feature.localsongactions.ui.components.LocalAddToPlaylistSheet
import com.music.bitchord.feature.localsearch.domain.LocalSearchUseCase
import com.music.bitchord.feature.localsearch.domain.model.LocalSearchFilter
import com.music.bitchord.feature.localsearch.domain.model.LocalSearchResult
import com.music.bitchord.feature.localsearch.ui.components.LocalSearchField
import com.music.bitchord.feature.localsearch.ui.components.LocalSearchFilterChips
import com.music.bitchord.feature.localsearch.ui.components.LocalSearchResultRow
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.FastScroller
import com.music.bitchord.ui.components.SectionIndexer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.ui.components.SongRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LocalSearchScreen(
    songs: List<Song>,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onAlbumClick: ((String, List<Song>, String?) -> Unit)? = null,
    onArtistClick: ((String, List<Song>) -> Unit)? = null,
    onFolderClick: ((String, List<Song>) -> Unit)? = null,
    onShuffle: ((List<Song>) -> Unit)? = null,
    focusTrigger: Int = 0,
    recentSearches: List<String> = emptyList(),
    onRecordSearch: (String) -> Unit = {},
    onRemoveSearch: (String) -> Unit = {},
    onClearSearchHistory: () -> Unit = {},
    youtubeMusicResults: UiState<List<SearchResult>>? = null,
    onYoutubeMusicSearch: ((String) -> Unit)? = null,
    onYoutubeMusicTrackClick: ((Song) -> Unit)? = null,
    onYoutubeMusicBrowseClick: ((com.music.bitchord.data.model.BrowseItem) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val folderHierarchy by AppSettings.localFolderHierarchy.collectAsStateWithLifecycle()
    val youtubeMusicEnabled by AppSettings.youtubeMusicEnabled.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val canSearchYoutubeMusic = youtubeMusicEnabled && !offlineMode
    var query by rememberSaveable { mutableStateOf("") }
    var useYoutubeMusic by rememberSaveable { mutableStateOf(false) }
    var submittedYoutubeQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFilter by rememberSaveable { mutableStateOf(LocalSearchFilter.ALL) }
    val listState = rememberLazyListState()

    LaunchedEffect(canSearchYoutubeMusic) {
        if (!canSearchYoutubeMusic) {
            useYoutubeMusic = false
            submittedYoutubeQuery = null
        }
    }

    var drillDownLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var drillDownSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var drillDownArt by remember { mutableStateOf<String?>(null) }
    var isDrillDownArtist by rememberSaveable { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var songsForPlaylist by remember { mutableStateOf<List<Song>?>(null) }

    val inDrillDown = drillDownLabel != null
    val leaveDrillDown = {
        drillDownLabel = null
        drillDownSongs = emptyList()
        drillDownArt = null
        isDrillDownArtist = false
    }

    BackHandler(enabled = selectedSongIds.isNotEmpty() || inDrillDown) {
        if (selectedSongIds.isNotEmpty()) selectedSongIds = emptySet() else leaveDrillDown()
    }

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            leaveDrillDown()
        }
    }

    LaunchedEffect(useYoutubeMusic, selectedFilter, submittedYoutubeQuery) {
        listState.scrollToItem(0)
    }

    // Searching every local song (and grouping albums, artists and folders for
    // the All filter) used to run synchronously during composition. On large
    // libraries that blocked the main thread for each keystroke, starving the
    // IME and making the field appear frozen. Debounce edits and do the scan off
    // the UI thread; an identity key avoids walking a large Song list on the
    // main thread just to compare Compose effect keys.
    val songsIdentity = System.identityHashCode(songs)
    var searchResults by remember { mutableStateOf<List<LocalSearchResult>>(emptyList()) }
    LaunchedEffect(query, selectedFilter, songsIdentity, folderHierarchy, useYoutubeMusic) {
        if (query.isBlank() || useYoutubeMusic) {
            searchResults = emptyList()
            listState.scrollToItem(0)
        } else {
            delay(140)
            val currentQuery = query
            val currentSongs = songs
            val currentFilter = selectedFilter
            val currentHierarchy = folderHierarchy
            val results = withContext(Dispatchers.Default) {
                LocalSearchUseCase.search(currentQuery, currentFilter, currentSongs, currentHierarchy)
            }
            listState.scrollToItem(0)
            searchResults = results
        }
    }

    val matchedSongs = remember(searchResults) {
        searchResults.filterIsInstance<LocalSearchResult.Track>().map { it.song }
    }
    val showSearchHistory = query.isBlank() ||
        (useYoutubeMusic && submittedYoutubeQuery.isNullOrBlank())

    AnimatedContent(
        targetState = if (inDrillDown) "drill" else "search",
        transitionSpec = {
            if (targetState == "drill") {
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "local_search_content",
        modifier = modifier.fillMaxSize(),
    ) { target ->
        if (target == "drill") {
            Column(Modifier.fillMaxSize()) {
                if (selectedSongIds.isNotEmpty()) {
                    SearchSelectionBar(
                        count = selectedSongIds.size,
                        onAdd = {
                            songsForPlaylist = drillDownSongs.filter { it.videoId in selectedSongIds }
                            selectedSongIds = emptySet()
                        },
                        onCancel = { selectedSongIds = emptySet() },
                    )
                }
                DrillDownSongList(
                label = drillDownLabel ?: "",
                artworkUrl = drillDownArt,
                songs = drillDownSongs,
                isArtist = isDrillDownArtist,
                selectedIds = selectedSongIds,
                currentSong = currentSong,
                isPlaying = isPlaying,
                onSongClick = { list, index ->
                    val song = list.getOrNull(index) ?: return@DrillDownSongList
                    if (selectedSongIds.isNotEmpty()) {
                        selectedSongIds = if (song.videoId in selectedSongIds) selectedSongIds - song.videoId
                        else selectedSongIds + song.videoId
                    } else onSongClick(list, index)
                },
                onSongLongPress = { song ->
                    selectedSongIds = if (song.videoId in selectedSongIds) selectedSongIds - song.videoId
                    else selectedSongIds + song.videoId
                },
                onSongMore = onSongLongPress,
                onSongSwipe = onSongSwipe,
                onShuffle = onShuffle ?: { songsToShuffle ->
                    if (songsToShuffle.isNotEmpty()) {
                        onSongClick(songsToShuffle.shuffled(), 0)
                    }
                },
                onBack = leaveDrillDown,
                contentPadding = contentPadding,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentPadding.calculateTopPadding()),
            ) {
                // Search Input Header
                LocalSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        if (query.isNotBlank()) {
                            val term = query.trim()
                            if (useYoutubeMusic) {
                                submittedYoutubeQuery = term
                                onYoutubeMusicSearch?.invoke(term)
                            } else {
                                onRecordSearch(term)
                            }
                        }
                    },
                    focusTrigger = focusTrigger,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
                )

                if (canSearchYoutubeMusic) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !useYoutubeMusic,
                            onClick = {
                                useYoutubeMusic = false
                                submittedYoutubeQuery = null
                            },
                            label = { Text(stringResource(R.string.search_this_device)) },
                        )
                        FilterChip(
                            selected = useYoutubeMusic,
                            onClick = {
                                useYoutubeMusic = true
                                submittedYoutubeQuery = null
                            },
                            label = { Text(stringResource(R.string.search_youtube_music)) },
                        )
                    }
                }

                if (!useYoutubeMusic) {
                    LocalSearchFilterChips(
                        selectedFilter = selectedFilter,
                        onFilterSelect = { selectedFilter = it },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                if (selectedSongIds.isNotEmpty()) {
                    SearchSelectionBar(
                        count = selectedSongIds.size,
                        onAdd = {
                            songsForPlaylist = songs.filter { it.videoId in selectedSongIds }
                            selectedSongIds = emptySet()
                        },
                        onCancel = { selectedSongIds = emptySet() },
                    )
                }

                // Content
                if (showSearchHistory) {
                    // Search history is shared by local and YouTube Music
                    // searches. Keep it visible after switching providers too.
                    // The selected provider is still used when a row is tapped.
                    // Recent searches or initial search prompt
                    if (recentSearches.isNotEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                        ) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 12.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Recent searches",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    TextButton(onClick = onClearSearchHistory) {
                                        Text(
                                            text = "Clear all",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            items(recentSearches, key = { it }) { term ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            query = term
                                            if (useYoutubeMusic) {
                                                submittedYoutubeQuery = term
                                                onYoutubeMusicSearch?.invoke(term)
                                            } else {
                                                onRecordSearch(term)
                                            }
                                        }
                                        .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = term,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { onRemoveSearch(term) },
                                    )
                                }
                            }
                        }
                        FastScroller(
                            listState = listState,
                            itemCount = recentSearches.size,
                            headerCount = 1,
                            sectionNameForIndex = { index ->
                                SectionIndexer.getSectionName(recentSearches.getOrNull(index))
                            },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = contentPadding.calculateBottomPadding()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(64.dp),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (useYoutubeMusic) "Search YouTube Music" else "Search offline music",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (useYoutubeMusic) "Type a song, artist, album, or playlist" else "Type a title, artist, album, or folder name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                } else if (useYoutubeMusic) {
                    YoutubeMusicSearchResults(
                        query = query,
                        submittedQuery = submittedYoutubeQuery,
                        state = youtubeMusicResults,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        onTrackClick = onYoutubeMusicTrackClick ?: { song -> onSongClick(listOf(song), 0) },
                        onTrackLongPress = onSongLongPress,
                        onTrackSwipe = onSongSwipe,
                        onBrowseClick = { item -> onYoutubeMusicBrowseClick?.invoke(item) },
                        contentPadding = contentPadding,
                        listState = listState,
                        modifier = Modifier.weight(1f),
                    )
                } else if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = contentPadding.calculateBottomPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.SearchOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No results found for \"$query\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                val matchedArtists = remember(searchResults) {
                    searchResults.filterIsInstance<LocalSearchResult.Artist>()
                }
                val matchedAlbums = remember(searchResults) {
                    searchResults.filterIsInstance<LocalSearchResult.Album>()
                }
                val matchedTracks = remember(searchResults) {
                    searchResults.filterIsInstance<LocalSearchResult.Track>()
                }
                val matchedFolders = remember(searchResults) {
                    searchResults.filterIsInstance<LocalSearchResult.Folder>()
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                ) {
                    item {
                        Text(
                            text = "${searchResults.size} results",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                        )
                    }

                    if (selectedFilter == LocalSearchFilter.ALL) {
                        if (matchedArtists.isNotEmpty()) {
                            item(key = "section:artists") {
                                SearchSectionHeader(title = "Artists", count = matchedArtists.size)
                            }
                            items(matchedArtists, key = { "artist:${it.name}" }) { artist ->
                                LocalSearchResultRow(
                                    result = artist,
                                    onSongClick = {},
                                    onSongLongPress = {},
                                    onSongSwipe = {},
                                    onAlbumClick = {},
                                    onArtistClick = {
                                        onRecordSearch(query.trim())
                                        if (onArtistClick != null) {
                                            onArtistClick(artist.name, artist.songs)
                                        } else {
                                            drillDownLabel = artist.name
                                            drillDownSongs = artist.songs
                                            drillDownArt = artist.thumbnailUrl
                                            isDrillDownArtist = true
                                        }
                                    },
                                    onFolderClick = {},
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }

                        if (matchedAlbums.isNotEmpty()) {
                            item(key = "section:albums") {
                                SearchSectionHeader(title = "Albums", count = matchedAlbums.size)
                            }
                            items(matchedAlbums, key = { "album:${it.title}:${it.artist}" }) { album ->
                                LocalSearchResultRow(
                                    result = album,
                                    onSongClick = {},
                                    onSongLongPress = {},
                                    onSongSwipe = {},
                                    onAlbumClick = {
                                        onRecordSearch(query.trim())
                                        if (onAlbumClick != null) {
                                            onAlbumClick(album.title, album.songs, album.thumbnailUrl)
                                        } else {
                                            drillDownLabel = album.title
                                            drillDownSongs = album.songs
                                            drillDownArt = album.thumbnailUrl
                                            isDrillDownArtist = false
                                        }
                                    },
                                    onArtistClick = {},
                                    onFolderClick = {},
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }

                        if (matchedTracks.isNotEmpty()) {
                            item(key = "section:songs") {
                                SearchSectionHeader(title = "Songs", count = matchedTracks.size)
                            }
                            items(matchedTracks, key = { "song:${it.song.videoId}" }) { track ->
                                LocalSearchResultRow(
                                    result = track,
                                    onSongClick = { song ->
                                        if (selectedSongIds.isNotEmpty()) {
                                            selectedSongIds = if (song.videoId in selectedSongIds) selectedSongIds - song.videoId
                                            else selectedSongIds + song.videoId
                                            return@LocalSearchResultRow
                                        }
                                        onRecordSearch(query.trim())
                                        val index = matchedSongs.indexOf(song).coerceAtLeast(0)
                                        onSongClick(matchedSongs, index)
                                    },
                                    onSongLongPress = { song ->
                                        selectedSongIds = if (song.videoId in selectedSongIds) selectedSongIds - song.videoId
                                        else selectedSongIds + song.videoId
                                    },
                                    onSongSwipe = onSongSwipe,
                                    onAlbumClick = {},
                                    onArtistClick = {},
                                    onFolderClick = {},
                                    selected = track.song.videoId in selectedSongIds,
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }

                        if (matchedFolders.isNotEmpty()) {
                            item(key = "section:folders") {
                                SearchSectionHeader(title = "Folders", count = matchedFolders.size)
                            }
                            items(matchedFolders, key = { "folder:${it.path}" }) { folder ->
                                LocalSearchResultRow(
                                    result = folder,
                                    onSongClick = {},
                                    onSongLongPress = {},
                                    onSongSwipe = {},
                                    onAlbumClick = {},
                                    onArtistClick = {},
                                    onFolderClick = {
                                        onRecordSearch(query.trim())
                                        if (onFolderClick != null) {
                                            onFolderClick(folder.name, folder.songs)
                                        } else {
                                            drillDownLabel = folder.name
                                            drillDownSongs = folder.songs
                                            drillDownArt = null
                                            isDrillDownArtist = false
                                        }
                                    },
                                    onFolderLongPress = { songsForPlaylist = it.songs },
                                    currentSong = currentSong,
                                    isPlaying = isPlaying,
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    } else {
                        items(searchResults, key = { result ->
                            when (result) {
                                is LocalSearchResult.Track -> "song:${result.song.videoId}"
                                is LocalSearchResult.Album -> "album:${result.title}:${result.artist}"
                                is LocalSearchResult.Artist -> "artist:${result.name}"
                                is LocalSearchResult.Folder -> "folder:${result.path}"
                            }
                        }) { result ->
                            LocalSearchResultRow(
                                result = result,
                                onSongClick = { song ->
                                    if (selectedSongIds.isNotEmpty()) {
                                        selectedSongIds = if (song.videoId in selectedSongIds) selectedSongIds - song.videoId
                                        else selectedSongIds + song.videoId
                                        return@LocalSearchResultRow
                                    }
                                    onRecordSearch(query.trim())
                                    val index = matchedSongs.indexOf(song).coerceAtLeast(0)
                                    onSongClick(matchedSongs, index)
                                },
                                onSongLongPress = { song ->
                                    selectedSongIds = if (song.videoId in selectedSongIds) selectedSongIds - song.videoId
                                    else selectedSongIds + song.videoId
                                },
                                onSongSwipe = onSongSwipe,
                                onAlbumClick = { album ->
                                    onRecordSearch(query.trim())
                                    if (onAlbumClick != null) {
                                        onAlbumClick(album.title, album.songs, album.thumbnailUrl)
                                    } else {
                                        drillDownLabel = album.title
                                        drillDownSongs = album.songs
                                        drillDownArt = album.thumbnailUrl
                                        isDrillDownArtist = false
                                    }
                                },
                                onArtistClick = { artist ->
                                    onRecordSearch(query.trim())
                                    if (onArtistClick != null) {
                                        onArtistClick(artist.name, artist.songs)
                                    } else {
                                        drillDownLabel = artist.name
                                        drillDownSongs = artist.songs
                                        drillDownArt = artist.thumbnailUrl
                                        isDrillDownArtist = true
                                    }
                                },
                                onFolderClick = { folder ->
                                    onRecordSearch(query.trim())
                                    if (onFolderClick != null) {
                                        onFolderClick(folder.name, folder.songs)
                                    } else {
                                        drillDownLabel = folder.name
                                        drillDownSongs = folder.songs
                                        drillDownArt = null
                                        isDrillDownArtist = false
                                    }
                                },
                                onFolderLongPress = { folder -> songsForPlaylist = folder.songs },
                                selected = (result as? LocalSearchResult.Track)?.song?.videoId in selectedSongIds,
                                currentSong = currentSong,
                                isPlaying = isPlaying,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
                FastScroller(
                    listState = listState,
                    itemCount = searchResults.size,
                    sectionNameForIndex = { index ->
                        val result = searchResults.getOrNull(index)
                        SectionIndexer.getSectionName(
                            when (result) {
                                is LocalSearchResult.Track -> result.song.title
                                is LocalSearchResult.Album -> result.title
                                is LocalSearchResult.Artist -> result.name
                                is LocalSearchResult.Folder -> result.name
                                null -> null
                            },
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                )
                }
            }
        }
    }
    }

    songsForPlaylist?.let { selected ->
        if (selected.isNotEmpty()) {
            LocalAddToPlaylistSheet(
                songs = selected,
                onDismissRequest = { songsForPlaylist = null },
            )
        }
    }
}

@Composable
private fun YoutubeMusicSearchResults(
    query: String,
    submittedQuery: String?,
    state: UiState<List<SearchResult>>?,
    currentSong: Song?,
    isPlaying: Boolean,
    onTrackClick: (Song) -> Unit,
    onTrackLongPress: (Song) -> Unit,
    onTrackSwipe: (Song) -> Unit,
    onBrowseClick: (com.music.bitchord.data.model.BrowseItem) -> Unit,
    contentPadding: PaddingValues,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val message = when {
        query.isBlank() -> "Search the YouTube Music catalog when you are ready."
        submittedQuery != query.trim() -> "Submit the search to look in YouTube Music."
        else -> null
    }
    if (message != null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    when (state) {
        null, UiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is UiState.Error -> Box(
            modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is UiState.Success -> {
            val results = state.data
            if (results.isEmpty()) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No YouTube Music results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                        ),
                    ) {
                        item(key = "youtube_result_count") {
                            Text(
                                text = "${results.size} YouTube Music results",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                            )
                        }
                        itemsIndexed(results, key = { index, result ->
                            val identity = when (result) {
                                is SearchResult.TopTrack -> "top:${result.song.videoId}"
                                is SearchResult.Track -> "track:${result.song.videoId}"
                                is SearchResult.Browse -> "browse:${result.item.browseId}"
                            }
                            "youtube:$index:$identity"
                        }) { _, result ->
                            when (result) {
                                is SearchResult.TopTrack -> SongRow(
                                    song = result.song,
                                    onClick = { onTrackClick(result.song) },
                                    onLongPress = { onTrackLongPress(result.song) },
                                    onSwipeToQueue = { onTrackSwipe(result.song) },
                                    isCurrent = result.song.isSameTrackAs(currentSong),
                                    isPlaying = result.song.isSameTrackAs(currentSong) && isPlaying,
                                )
                                is SearchResult.Track -> SongRow(
                                    song = result.song,
                                    onClick = { onTrackClick(result.song) },
                                    onLongPress = { onTrackLongPress(result.song) },
                                    onSwipeToQueue = { onTrackSwipe(result.song) },
                                    isCurrent = result.song.isSameTrackAs(currentSong),
                                    isPlaying = result.song.isSameTrackAs(currentSong) && isPlaying,
                                )
                                is SearchResult.Browse -> YoutubeMusicBrowseRow(result.item) {
                                    onBrowseClick(result.item)
                                }
                            }
                        }
                    }
                    FastScroller(
                        listState = listState,
                        itemCount = results.size,
                        headerCount = 1,
                        sectionNameForIndex = { index ->
                            val result = results.getOrNull(index)
                            SectionIndexer.getSectionName(
                                when (result) {
                                    is SearchResult.TopTrack -> result.song.title
                                    is SearchResult.Track -> result.song.title
                                    is SearchResult.Browse -> result.item.title
                                    null -> null
                                },
                            )
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun YoutubeMusicBrowseRow(
    item: com.music.bitchord.data.model.BrowseItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle.ifBlank { item.type.name.lowercase().replaceFirstChar(Char::uppercase) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchSelectionBar(
    count: Int,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$count ${context.getString(com.music.bitchord.R.string.selected)}",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(context.getString(com.music.bitchord.R.string.add_to_playlist))
        }
        TextButton(onClick = onCancel) {
            Text(context.getString(com.music.bitchord.R.string.cancel))
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
