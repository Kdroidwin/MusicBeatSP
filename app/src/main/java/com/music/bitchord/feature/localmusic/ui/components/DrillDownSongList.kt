package com.music.bitchord.feature.localmusic.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import com.music.bitchord.ui.utils.debouncedCombinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.ui.components.FastScroller
import com.music.bitchord.ui.components.SectionIndexer
import com.music.bitchord.feature.artistimage.model.ArtistImage
import com.music.bitchord.ui.components.ExplicitSongTitle
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.icons.BitChordIcons
import kotlin.math.abs

@Composable
fun DrillDownHeader(
    label: String,
    artworkUrl: String?,
    songs: List<Song>,
    isArtist: Boolean = false,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 8.dp, bottom = 12.dp),
    ) {
        val shape = if (isArtist) CircleShape else RoundedCornerShape(16.dp)
        val isFavorites = label.equals("Favorites", ignoreCase = true) || artworkUrl == "favorites"

        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(188.dp)
                .clip(shape)
                .background(
                    if (isFavorites) {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFE91E63),
                                Color(0xFF8E24AA),
                                Color(0xFF3F51B5),
                            ),
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isFavorites) {
                Icon(
                    imageVector = BitChordIcons.HeartFilled,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.size(76.dp),
                )
            } else {
                Icon(
                    imageVector = if (isArtist) Icons.Rounded.Person else Icons.Rounded.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(64.dp),
                )
                val imageModel: Any? = if (isArtist) {
                    remember(label, artworkUrl) {
                        ArtistImage(
                            name = label,
                            fallbackUrl = artworkUrl ?: songs.firstNotNullOfOrNull { it.thumbnailUrl },
                            isLarge = true,
                        )
                    }
                } else {
                    (artworkUrl ?: songs.firstNotNullOfOrNull { it.thumbnailUrl })?.artworkAt(CARD_ART_PX)
                }

                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .then(if (isArtist) Modifier.thumbnailBorder(shape) else Modifier),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                if (isArtist) append("Artist · ")
                append(pluralStringResource(R.plurals.track_count_plural, songs.size, songs.size))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun DrillDownActionRow(
    songs: List<Song>,
    viewType: LibraryViewType? = null,
    onViewTypeToggle: (() -> Unit)? = null,
    onSongClick: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onMore: (() -> Unit)? = null,
    reorderEnabled: Boolean = false,
    onReorderToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val buttonBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val buttonBorder = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val buttonContentColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(buttonBackground)
                .border(0.5.dp, buttonBorder, CircleShape)
                .clickable { if (songs.isNotEmpty()) onShuffle(songs) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                BitChordIcons.Shuffle,
                contentDescription = stringResource(R.string.shuffle),
                tint = buttonContentColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Row(
            modifier = Modifier
                .height(50.dp)
                .clip(CircleShape)
                .background(buttonBackground)
                .border(0.5.dp, buttonBorder, CircleShape)
                .clickable { if (songs.isNotEmpty()) onSongClick(songs, 0) }
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                BitChordIcons.Play,
                contentDescription = null,
                tint = buttonContentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.play),
                style = MaterialTheme.typography.titleMedium,
                color = buttonContentColor,
            )
        }
        if (onViewTypeToggle != null && viewType != null) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(buttonBackground)
                    .border(0.5.dp, buttonBorder, CircleShape)
                    .clickable(onClick = onViewTypeToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (viewType == LibraryViewType.GRID) BitChordIcons.ListView else BitChordIcons.GridView,
                    contentDescription = stringResource(
                        if (viewType == LibraryViewType.GRID) R.string.switch_to_list_view else R.string.switch_to_grid_view,
                    ),
                    tint = buttonContentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onReorderToggle != null) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(
                        if (reorderEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else buttonBackground,
                    )
                    .border(
                        0.5.dp,
                        if (reorderEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f) else buttonBorder,
                        CircleShape,
                    )
                    .clickable(onClick = onReorderToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Reorder,
                    contentDescription = stringResource(R.string.toggle_playlist_reorder),
                    tint = if (reorderEnabled) MaterialTheme.colorScheme.primary else buttonContentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onMore != null) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(buttonBackground)
                    .border(0.5.dp, buttonBorder, CircleShape)
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.more),
                    tint = buttonContentColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongGridCard(
    song: Song,
    selected: Boolean = false,
    isCurrent: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .debouncedCombinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(4.dp),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .thumbnailBorder(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            if (song.thumbnailUrl != null) {
                AsyncImage(
                    model = song.thumbnailUrl.artworkAt(CARD_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isCurrent) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = stringResource(R.string.now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        ExplicitSongTitle(
            song = song,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = song.artist.ifBlank { stringResource(R.string.unknown_artist) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DrillDownSongList(
    label: String,
    artworkUrl: String?,
    songs: List<Song>,
    isArtist: Boolean = false,
    viewType: LibraryViewType = LibraryViewType.LIST,
    onViewTypeToggle: (() -> Unit)? = null,
    selectedIds: Set<String> = emptySet(),
    selectionMode: Boolean = false,
    onSelectionToggle: ((Song) -> Unit)? = null,
    selectionToolbar: (@Composable () -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongMore: ((Song) -> Unit)? = null,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onMore: (() -> Unit)? = null,
    isPlaylist: Boolean = false,
    showArtworkInList: Boolean = false,
    reorderEnabled: Boolean = false,
    onReorderToggle: (() -> Unit)? = null,
    onReorderComplete: ((List<Song>) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    songDropdownMenu: (@Composable (Song) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (viewType == LibraryViewType.GRID) {
        val gridState = rememberLazyGridState()
        Box(modifier = modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PAGE_GUTTER,
                    end = PAGE_GUTTER,
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DrillDownHeader(
                        label = label,
                        artworkUrl = artworkUrl,
                        songs = songs,
                        isArtist = isArtist,
                        onBack = onBack,
                    )
                }
                if (!selectionMode) item(span = { GridItemSpan(maxLineSpan) }) {
                    DrillDownActionRow(
                        songs = songs,
                        viewType = viewType,
                        onViewTypeToggle = onViewTypeToggle,
                        onSongClick = onSongClick,
                        onShuffle = onShuffle,
                        onMore = onMore,
                        reorderEnabled = reorderEnabled,
                        onReorderToggle = onReorderToggle,
                    )
                }
                if (selectionToolbar != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) { selectionToolbar() }
                }
                itemsIndexed(songs) { index, song ->
                    SongGridCard(
                        song = song,
                        selected = song.videoId in selectedIds || (song.localUri ?: song.videoId) in selectedIds,
                        isCurrent = song.isSameTrackAs(currentSong),
                        onClick = {
                            if (selectionMode) onSelectionToggle?.invoke(song) else onSongClick(songs, index)
                        },
                        onLongPress = { onSongLongPress(song) },
                    )
                }
            }
            FastScroller(
                gridState = gridState,
                itemCount = songs.size,
                headerCount = 2,
                sectionNameForIndex = { index -> SectionIndexer.getSectionName(songs[index].title) },
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
            )
        }
    } else {
        val listState = rememberLazyListState()
        var displayedSongs by remember(songs) { mutableStateOf(songs) }
        val dragState = rememberPlaylistSongDragState(listState, displayedSongs.size) { from, to, draggedKey, insertAfter, groupedKeys ->
            val current = displayedSongs
            if (from !in current.indices || to !in current.indices || from == to) return@rememberPlaylistSongDragState null

            val movingSongs = if (groupedKeys.size > 1 && draggedKey in groupedKeys) {
                current.filter { (it.localUri ?: it.videoId) in groupedKeys }
            } else {
                listOf(current[from])
            }
            if (movingSongs.isEmpty()) return@rememberPlaylistSongDragState null

            val movingKeys = movingSongs.mapTo(mutableSetOf()) { it.localUri ?: it.videoId }
            val targetKey = current[to].localUri ?: current[to].videoId
            if (targetKey in movingKeys) return@rememberPlaylistSongDragState null

            val remaining = current.filterNot { (it.localUri ?: it.videoId) in movingKeys }
            val targetIndex = remaining.indexOfFirst { (it.localUri ?: it.videoId) == targetKey }
            if (targetIndex < 0) return@rememberPlaylistSongDragState null

            val anchorOrder = movingSongs.indexOfFirst { (it.localUri ?: it.videoId) == draggedKey }
                .coerceAtLeast(0)
            val insertionIndex = (targetIndex + if (insertAfter) 1 else 0).coerceIn(0, remaining.size)
            val reordered = remaining.toMutableList().apply { addAll(insertionIndex, movingSongs) }
            if (reordered == current) return@rememberPlaylistSongDragState null

            displayedSongs = reordered
            insertionIndex + anchorOrder
        }
        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                item {
                    DrillDownHeader(
                        label = label,
                        artworkUrl = artworkUrl,
                        songs = songs,
                        isArtist = isArtist,
                        onBack = onBack,
                    )
                }
                if (!selectionMode) item {
                    DrillDownActionRow(
                        songs = songs,
                        viewType = viewType,
                        onViewTypeToggle = onViewTypeToggle,
                        onSongClick = onSongClick,
                        onShuffle = onShuffle,
                        onMore = onMore,
                        reorderEnabled = reorderEnabled,
                        onReorderToggle = onReorderToggle,
                    )
                }
                if (selectionToolbar != null) {
                    item { selectionToolbar() }
                }
                itemsIndexed(displayedSongs, key = { _, song -> song.localUri ?: song.videoId }) { index, song ->
                    val trackNumber = if (isPlaylist && showArtworkInList) null else index + 1
                    if (reorderEnabled && isPlaylist) {
                        val songKey = song.localUri ?: song.videoId
                        val isDragging = dragState.draggedKey == songKey
                        val finishDrag by rememberUpdatedState(newValue = {
                            if (dragState.draggedKey == songKey) {
                                dragState.onDragEnd()
                                onReorderComplete?.invoke(displayedSongs)
                            }
                        })
                        DisposableEffect(songKey) {
                            onDispose { if (dragState.draggedKey == songKey) finishDrag() }
                        }
                        Row(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragState.renderOffset else 0f }
                                .background(
                                    if (isDragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else Color.Transparent,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                SongRow(
                                    song = song,
                                    selected = song.videoId in selectedIds || (song.localUri ?: song.videoId) in selectedIds,
                                    isCurrent = song.isSameTrackAs(currentSong),
                                    isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                                    trackNumber = trackNumber,
                                    onClick = {
                                        if (selectionMode) onSelectionToggle?.invoke(song)
                                        else onSongClick(displayedSongs, index)
                                    },
                                    onLongPress = { onSongLongPress(song) },
                                    onMore = onSongMore?.let { more -> { more(song) } },
                                    onSwipeToQueue = null,
                                    dropdownMenu = songDropdownMenu?.let { menu -> { menu(song) } },
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = stringResource(
                                    if (selectionMode && (songKey in selectedIds || song.videoId in selectedIds)) {
                                        R.string.drag_selected_songs_to_reorder
                                    } else {
                                        R.string.drag_to_reorder_song
                                    },
                                ),
                                tint = if (isDragging) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(end = PAGE_GUTTER)
                                    .pointerInput(songKey, selectionMode, selectedIds) {
                                        detectDragGestures(
                                            onDragStart = {
                                                val draggedIsSelected = songKey in selectedIds || song.videoId in selectedIds
                                                val groupKeys = if (selectionMode && draggedIsSelected) {
                                                    displayedSongs
                                                        .filter { (it.localUri ?: it.videoId) in selectedIds || it.videoId in selectedIds }
                                                        .map { it.localUri ?: it.videoId }
                                                        .toSet()
                                                } else {
                                                    emptySet()
                                                }
                                                dragState.onDragStart(songKey, groupKeys)
                                            },
                                            onDragEnd = { finishDrag() },
                                            onDragCancel = { finishDrag() },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragState.onDrag(dragAmount.y)
                                            },
                                        )
                                    },
                            )
                        }
                    } else {
                        SongRow(
                            song = song,
                            selected = song.videoId in selectedIds || (song.localUri ?: song.videoId) in selectedIds,
                            isCurrent = song.isSameTrackAs(currentSong),
                            isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                            trackNumber = trackNumber,
                            onClick = {
                                if (selectionMode) onSelectionToggle?.invoke(song) else onSongClick(displayedSongs, index)
                            },
                            onLongPress = { onSongLongPress(song) },
                            onMore = onSongMore?.let { more -> { more(song) } },
                            onSwipeToQueue = if (selectionMode) null else { { onSongSwipe(song) } },
                            dropdownMenu = songDropdownMenu?.let { menu -> { menu(song) } },
                        )
                    }
                    if (index < songs.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            FastScroller(
                listState = listState,
                itemCount = songs.size,
                headerCount = 2,
                sectionNameForIndex = { index -> SectionIndexer.getSectionName(songs[index].title) },
                contentPadding = contentPadding,
            )
        }
    }
}

/** Drag-to-reorder state for the song section below the two static header rows. */
@Composable
private fun rememberPlaylistSongDragState(
    listState: androidx.compose.foundation.lazy.LazyListState,
    songCount: Int,
    onMove: (from: Int, to: Int, draggedKey: String, insertAfter: Boolean, groupedKeys: Set<String>) -> Int?,
): PlaylistSongDragState {
    val state = remember(listState) { PlaylistSongDragState(listState) }
    state.lazyOffset = 2
    state.lazyRange = if (songCount > 0) 2 until songCount + 2 else IntRange.EMPTY
    state.onMove = onMove
    with(LocalDensity.current) {
        state.edgeZone = 40.dp.toPx()
        state.edgeSpeed = 340.dp.toPx()
    }

    val direction = state.autoScrollDir
    LaunchedEffect(state, direction) {
        if (direction == 0) return@LaunchedEffect
        listState.scroll {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
                previous = now
                val scrolled = scrollBy(state.autoScrollSpeed * seconds)
                if (scrolled == 0f) break
                state.onScrolled()
            }
        }
    }
    return state
}

/** Holds the grabbed song under the finger, swaps crossed rows, and scrolls at list edges. */
private class PlaylistSongDragState(
    private val listState: androidx.compose.foundation.lazy.LazyListState,
) {
    var lazyRange: IntRange = IntRange.EMPTY
    var lazyOffset: Int = 2
    var onMove: (from: Int, to: Int, draggedKey: String, insertAfter: Boolean, groupedKeys: Set<String>) -> Int? =
        { _, _, _, _, _ -> null }
    var edgeZone: Float = 0f
    var edgeSpeed: Float = 0f

    var draggedKey by mutableStateOf<Any?>(null)
        private set
    private var groupedKeys: Set<String> = emptySet()
    var renderOffset by mutableFloatStateOf(0f)
        private set
    var autoScrollDir by mutableIntStateOf(0)
        private set
    var autoScrollSpeed: Float = 0f
        private set
    private var heldCenter: Float = Float.NaN
    private var awaiting: Int? = null

    fun onDragStart(key: Any, groupedKeys: Set<String> = emptySet()) {
        draggedKey = key
        this.groupedKeys = groupedKeys
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    fun onDrag(deltaY: Float) = settle(deltaY)
    fun onScrolled() = settle(0f)

    fun onDragEnd() {
        draggedKey = null
        groupedKeys = emptySet()
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    private fun settle(deltaY: Float) {
        val key = draggedKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == key } ?: run {
            setAutoScroll(0f)
            return
        }
        val half = dragged.size / 2f
        if (heldCenter.isNaN()) heldCenter = dragged.offset + half
        heldCenter += deltaY
        holdToSongRange(items, dragged)
        val top = heldCenter - half
        aimAutoScroll(top, dragged)
        renderOffset = insideViewport(top, dragged.size) - dragged.offset

        awaiting?.let { targetIndex ->
            if (dragged.index != targetIndex) return
            awaiting = null
        }
        val target = items
            .filter { it.index in lazyRange && it.index != dragged.index }
            .minByOrNull { abs((it.offset + it.size / 2f) - heldCenter ) }
            ?: return
        if (abs(heldCenter - (target.offset + target.size / 2f)) > target.size / 2f) return
        if (target.index == listState.firstVisibleItemIndex && listState.canScrollBackward) return
        val expectedDraggedIndex = onMove(
            dragged.index - lazyOffset,
            target.index - lazyOffset,
            key as? String ?: return,
            heldCenter > target.offset + target.size / 2f,
            groupedKeys,
        ) ?: return
        awaiting = expectedDraggedIndex + lazyOffset
    }

    private fun aimAutoScroll(top: Float, dragged: androidx.compose.foundation.lazy.LazyListItemInfo) {
        val info = listState.layoutInfo
        val bottom = top + dragged.size
        val speed = when {
            top < info.viewportStartOffset + edgeZone -> {
                -edgeSpeed * ((info.viewportStartOffset + edgeZone - top) / edgeZone).coerceIn(0f, 1f)
            }
            bottom > info.viewportEndOffset - edgeZone -> {
                edgeSpeed * ((bottom - (info.viewportEndOffset - edgeZone)) / edgeZone).coerceIn(0f, 1f)
            }
            else -> 0f
        }
        val blocked = when {
            speed < 0f -> dragged.index <= lazyRange.first || !listState.canScrollBackward
            speed > 0f -> dragged.index >= lazyRange.last || !listState.canScrollForward
            else -> true
        }
        setAutoScroll(if (blocked) 0f else speed)
    }

    private fun holdToSongRange(items: List<androidx.compose.foundation.lazy.LazyListItemInfo>, dragged: androidx.compose.foundation.lazy.LazyListItemInfo) {
        val half = dragged.size / 2f
        items.firstOrNull { it.index == lazyRange.first }?.let {
            heldCenter = heldCenter.coerceAtLeast(it.offset + half)
        }
        items.firstOrNull { it.index == lazyRange.last }?.let {
            heldCenter = heldCenter.coerceAtMost(it.offset + it.size - half)
        }
    }

    private fun insideViewport(top: Float, size: Int): Float {
        val info = listState.layoutInfo
        val minTop = info.viewportStartOffset.toFloat()
        val maxTop = (info.viewportEndOffset - size).toFloat().coerceAtLeast(minTop)
        return top.coerceIn(minTop, maxTop)
    }

    private fun setAutoScroll(speed: Float) {
        autoScrollSpeed = speed
        val direction = when {
            speed > 0f -> 1
            speed < 0f -> -1
            else -> 0
        }
        if (autoScrollDir != direction) autoScrollDir = direction
    }
}
