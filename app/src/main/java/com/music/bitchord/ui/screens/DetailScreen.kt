package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.canvas.AppleArtistArtRepository
import com.music.bitchord.data.canvas.appleCatalogue
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * Artist landing page adapted from BitChord's artist page. The artist page data,
 * catalogue-backed hero art and section ordering use the same upstream sources;
 * MusicBeatSP's existing SongRow and playback callbacks keep local actions intact.
 */
@Composable
fun DetailScreen(
    page: DetailPage,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState = rememberLazyListState(),
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongMore: ((Song) -> Unit)? = null,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onToggleSubscription: (() -> Unit)?,
    contentPadding: PaddingValues,
    onChangeArtistArtwork: (() -> Unit)? = null,
    onResetArtistArtwork: (() -> Unit)? = null,
    isLocalOnlyArtist: Boolean = false,
    onToggleLocalOnlyArtist: ((Boolean) -> Unit)? = null,
    selectionMode: Boolean = false,
    selectionToolbar: (@Composable (Color) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val songsState = page.songs
    val songs = (songsState as? UiState.Success)?.data.orEmpty()
    val showArtistOverview by AppSettings.showArtistOverview.collectAsStateWithLifecycle()
    val showLocalArtistSections by AppSettings.showLocalArtistSections.collectAsStateWithLifecycle()
    val subscribed = page.subscription?.subscribed == true
    val subscriptionLabel = page.subscription?.let {
        stringResource(if (it.subscribed) R.string.unsubscribe else R.string.subscribe)
    }
    var appleArt by remember(page.browseId) { mutableStateOf(AppleArtistArtRepository.cached(page.browseId)) }
    LaunchedEffect(page.browseId, page.title, page.sections, songsState is UiState.Loading) {
        if (!page.browseId.startsWith("local:") && AppSettings.youtubeMusicNetworkAllowed && songsState !is UiState.Loading) {
            appleArt = AppleArtistArtRepository.artFor(page.browseId, page.title) {
                page.appleCatalogue()
            } ?: appleArt
        }
    }

    val heroUrl = page.artistArtworkOverride ?: appleArt?.heroUrl ?: page.thumbnailUrl
    val palette = rememberArtworkPalette(imageUrl = heroUrl)
    val wash = palette.wash

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(wash),
        contentPadding = contentPadding,
    ) {
        item(key = "artist-hero") {
            ArtistHero(
                title = page.title,
                heroUrl = heroUrl,
                wash = wash,
                palette = palette,
                onPlay = { if (songs.isNotEmpty()) onSongClick(songs, 0) },
                onShuffle = { if (songs.isNotEmpty()) onShuffle(songs) },
                subscriptionLabel = subscriptionLabel,
                subscribed = subscribed,
                onToggleSubscription = onToggleSubscription,
                hasCustomArtwork = page.artistArtworkOverride != null,
                onChangeArtwork = onChangeArtistArtwork,
                onResetArtwork = onResetArtistArtwork,
                isLocalOnlyArtist = isLocalOnlyArtist,
                onToggleLocalOnlyArtist = onToggleLocalOnlyArtist,
            )
        }

        if (showArtistOverview && (page.description?.isNotBlank() == true || page.subscriberCountText != null || page.monthlyListenerCount != null)) {
            item(key = "artist-about") {
                Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 14.dp)) {
                    SectionTitle(stringResource(R.string.about_artist), palette)
                    if (page.description?.isNotBlank() == true) {
                        Text(
                            text = page.description,
                            color = palette.onBackgroundVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val stats = listOfNotNull(page.subscriberCountText, page.monthlyListenerCount)
                    if (stats.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stats.joinToString("  ·  "),
                            color = palette.onBackgroundVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        val localSections = page.sections.take(page.localSectionsCount.coerceIn(0, page.sections.size))
        val remoteSections = page.sections.drop(localSections.size)
        if (showLocalArtistSections) {
            items(localSections, key = { "local-artist-shelf-${it.title}" }) { shelf ->
                ArtistShelf(shelf = shelf, palette = palette, onItemClick = onSectionItemClick)
            }
        }
        if (selectionMode && selectionToolbar != null) {
            item(key = "artist-selection-toolbar") { selectionToolbar(palette.onBackground) }
        }

        when (songsState) {
            UiState.Loading -> item(key = "artist-loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
            }
            is UiState.Error -> item(key = "artist-error") {
                Text(
                    text = songsState.message,
                    modifier = Modifier.padding(PAGE_GUTTER),
                    color = palette.onBackgroundVariant,
                )
            }
            is UiState.Success -> {
                if (songs.isNotEmpty()) {
                    item(key = "top-songs-heading") {
                        SectionTitle(stringResource(R.string.top_songs), palette)
                    }
                    if (page.hybridArtist || page.browseId.startsWith("local:artist:")) {
                        itemsIndexed(songs, key = { _, song -> "artist-local-${song.localUri ?: song.videoId}" }) { index, song ->
                            SongRow(
                                song = song,
                                onClick = { onSongClick(songs, index) },
                                onLongPress = { onSongLongPress(song) },
                                onMore = onSongMore?.let { more -> { more(song) } },
                                onSwipeToQueue = { onSongSwipe(song) },
                                rowBackground = wash,
                                subtitleColor = palette.onBackgroundVariant,
                                downloadedTint = null,
                                isCurrent = currentSong?.videoId == song.videoId,
                                isPlaying = currentSong?.videoId == song.videoId && isPlaying,
                                activeTint = palette.accent,
                            )
                        }
                    } else item(key = "top-songs") {
                        val topSongs = songs.take(20)
                        BoxWithConstraints {
                            val columnWidth = (maxWidth - PAGE_GUTTER * 2).coerceAtLeast(260.dp)
                            val topSongsState = rememberLazyListState()
                            LazyRow(
                                state = topSongsState,
                                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(topSongs.chunked(4), key = { chunk -> chunk.firstOrNull()?.videoId.orEmpty() }) { column ->
                                    Column(Modifier.width(columnWidth)) {
                                        column.forEach { song ->
                                            val index = songs.indexOf(song)
                                            SongRow(
                                                song = song,
                                                onClick = { onSongClick(songs, index) },
                                                onLongPress = { onSongLongPress(song) },
                                                onSwipeToQueue = { onSongSwipe(song) },
                                                rowBackground = wash,
                                                subtitleColor = palette.onBackgroundVariant,
                                                downloadedTint = null,
                                                isCurrent = currentSong?.videoId == song.videoId,
                                                isPlaying = currentSong?.videoId == song.videoId && isPlaying,
                                                activeTint = palette.accent,
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

        items(remoteSections, key = { "artist-shelf-${it.title}" }) { shelf ->
            ArtistShelf(
                shelf = shelf,
                palette = palette,
                onItemClick = onSectionItemClick,
            )
        }

        val suggested = page.suggestedSongs
        if (suggested.isNotEmpty()) {
            item(key = "suggested-heading") { SectionTitle(stringResource(R.string.suggested), palette) }
            itemsIndexed(suggested, key = { _, song -> "artist-suggested-${song.videoId}" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onSongClick(suggested, index) },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    rowBackground = wash,
                    subtitleColor = palette.onBackgroundVariant,
                    downloadedTint = null,
                    isCurrent = currentSong?.videoId == song.videoId,
                    isPlaying = currentSong?.videoId == song.videoId && isPlaying,
                    activeTint = palette.accent,
                )
            }
        }
    }
}

@Composable
private fun ArtistHero(
    title: String,
    heroUrl: String?,
    wash: Color,
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    subscriptionLabel: String?,
    subscribed: Boolean,
    onToggleSubscription: (() -> Unit)?,
    hasCustomArtwork: Boolean,
    onChangeArtwork: (() -> Unit)?,
    onResetArtwork: (() -> Unit)?,
    isLocalOnlyArtist: Boolean,
    onToggleLocalOnlyArtist: ((Boolean) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val artHeight = (maxWidth * 1.1f).coerceIn(340.dp, 590.dp)
        var showArtistOptions by remember(title, heroUrl) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().height(artHeight)) {
            AsyncImage(
                model = heroUrl?.artworkAt(1200),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(palette.elevated),
            )
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0.26f to Color.Transparent,
                            0.62f to wash.copy(alpha = 0.38f),
                            1f to wash,
                        ),
                    )
                },
            )
            if (onChangeArtwork != null) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onToggleLocalOnlyArtist != null) {
                        Box {
                            IconButton(
                                onClick = { showArtistOptions = true },
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.46f)),
                            ) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.artist_page_options),
                                    tint = Color.White,
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = showArtistOptions,
                                onDismissRequest = { showArtistOptions = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (isLocalOnlyArtist) R.string.artist_page_use_default_source
                                                else R.string.artist_page_use_local_only,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        showArtistOptions = false
                                        onToggleLocalOnlyArtist(!isLocalOnlyArtist)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onChangeArtwork,
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.46f)),
                    ) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.change_artist_image),
                            tint = Color.White,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    if (hasCustomArtwork && onResetArtwork != null) {
                        IconButton(
                            onClick = onResetArtwork,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.46f)),
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.reset_artist_image),
                                tint = Color.White,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(PAGE_GUTTER),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = palette.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    ArtistActions(
        palette = palette,
        onPlay = onPlay,
        onShuffle = onShuffle,
        subscriptionLabel = subscriptionLabel,
        subscribed = subscribed,
        onToggleSubscription = onToggleSubscription,
    )
}

@Composable
private fun ArtistActions(
    palette: ArtworkPalette,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    subscriptionLabel: String?,
    subscribed: Boolean,
    onToggleSubscription: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistRoundAction(Icons.Rounded.Shuffle, stringResource(R.string.shuffle), palette, onShuffle)
        Spacer(Modifier.width(22.dp))
        IconButton(
            onClick = onPlay,
            modifier = Modifier.size(68.dp).clip(CircleShape).background(palette.accent),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.play), tint = palette.onBackground, modifier = Modifier.size(40.dp))
        }
        if (subscriptionLabel != null && onToggleSubscription != null) {
            Spacer(Modifier.width(22.dp))
            ArtistRoundAction(
                icon = if (subscribed) Icons.Rounded.Check else Icons.Rounded.Notifications,
                label = subscriptionLabel,
                palette = palette,
                onClick = onToggleSubscription,
            )
        }
    }
}

@Composable
private fun ArtistRoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: ArtworkPalette,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(54.dp).clip(CircleShape).background(palette.elevated.copy(alpha = 0.72f))) {
        Icon(icon, contentDescription = label, tint = palette.onBackground, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun SectionTitle(title: String, palette: ArtworkPalette) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = palette.onBackground,
        modifier = Modifier.fillMaxWidth().padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ArtistShelf(
    shelf: HomeShelf,
    palette: ArtworkPalette,
    onItemClick: (ShelfItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        SectionTitle(shelf.title, palette)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shelf.items, key = { "${it.browseId}:${it.videoId}:${it.title}" }) { item ->
                Column(
                    modifier = Modifier.width(SHELF_CARD_WIDTH).clip(RoundedCornerShape(10.dp)).clickable { onItemClick(item) },
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl?.artworkAt(CARD_ART_PX),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(palette.elevated),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(item.title, color = palette.onBackground, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.subtitle, color = palette.onBackgroundVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
