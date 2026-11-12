package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.feature.homeai.LocalHomeAi
import com.music.bitchord.feature.localmusic.data.LocalFavoritesStore
import com.music.bitchord.feature.localsongactions.data.LocalPlayStatsStore
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PullToRefresh
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.ln

data class HomeLocalCatalog(
    val songs: List<Song>,
    val onSongClick: (List<Song>, Int) -> Unit,
    val onSongLongPress: (Song) -> Unit,
    val onlineFeedEnabled: Boolean,
    val allowModelDownload: Boolean,
)

/** Native Android home feed, using the same personalized shelves as BitChord. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState = rememberLazyListState(),
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
    localCatalog: HomeLocalCatalog = HomeLocalCatalog(emptyList(), { _, _ -> }, {}, true, true),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val localSongs = localCatalog.songs
    val onlineFeedEnabled = localCatalog.onlineFeedEnabled
    val allowModelDownload = localCatalog.allowModelDownload && !context.packageName.endsWith(".nointernet")
    val scope = rememberCoroutineScope()
    val favoriteIds by LocalFavoritesStore.favoriteIds.collectAsState()
    val playStatsRevision by LocalPlayStatsStore.revision.collectAsState()
    val downloadProgress by LocalHomeAi.downloadProgress.collectAsState()
    val playStats = remember(context, localSongs, playStatsRevision) {
        LocalPlayStatsStore.snapshot(context)
    }
    var modelInstalled by remember(context) { mutableStateOf(false) }
    var showModelPrompt by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var moodRevision by remember { mutableIntStateOf(0) }
    var wallClockMinute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    val cachedMoods = remember(context, localSongs, moodRevision, modelInstalled) {
        LocalHomeAi.cachedScores(context, localSongs)
    }

    LaunchedEffect(context, allowModelDownload) {
        val installed = withContext(Dispatchers.IO) { LocalHomeAi.isModelInstalled(context) }
        modelInstalled = installed
        if (allowModelDownload && !installed && LocalHomeAi.modelDecision(context) == null) {
            showModelPrompt = true
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            wallClockMinute = System.currentTimeMillis() / 60_000L
        }
    }

    // Work through a small batch of high-priority tracks while Home is open.
    // Persisted scores mean later visits continue with the next unheard tracks
    // instead of repeatedly decoding the same audio.
    LaunchedEffect(context, localSongs, modelInstalled, favoriteIds, playStatsRevision) {
        if (!modelInstalled || localSongs.isEmpty()) return@LaunchedEffect
        val maximumPlayed = localSongs.maxOfOrNull { LocalPlayStatsStore.getStats(it, playStats).playedCount } ?: 0
        val prioritized = localSongs
            .filter { !it.localUri.isNullOrBlank() || !it.localPath.isNullOrBlank() }
            .sortedByDescending { song ->
                val stats = LocalPlayStatsStore.getStats(song, playStats)
                (if (song.favoriteStoreKeys().any(favoriteIds::contains)) 500.0 else 0.0) +
                    (stats.playedCount.toDouble() / (maximumPlayed + 1.0)) * 300.0 +
                    (if (stats.lastPlayedTimestamp > 0L) 100.0 else 0.0)
            }
            .filter { LocalHomeAi.cachedScore(context, it) == null }
            .take(16)
        prioritized.forEach { song ->
            if (LocalHomeAi.classify(context, song) != null) moodRevision++
        }
    }

    val localSongScores = remember(localSongs, playStats, favoriteIds, cachedMoods) {
        localSongs.map { song ->
            val stats = LocalPlayStatsStore.getStats(song, playStats)
            val mood = cachedMoods[homeAiCacheKey(song)]
            val favorite = song.favoriteStoreKeys().any(favoriteIds::contains)
            Triple(song, stats, mood to favorite)
        }
    }
    val mostPlayed = remember(localSongScores) {
        localSongScores.asSequence()
            .filter { it.second.playedCount > 0 }
            .sortedWith(Comparator { left, right ->
                val byCount = right.second.playedCount.compareTo(left.second.playedCount)
                if (byCount != 0) byCount else right.second.lastPlayedTimestamp.compareTo(left.second.lastPlayedTimestamp)
            })
            .map { it.first }
            .take(24)
            .toList()
    }
    val wallClock = remember(wallClockMinute) { LocalDateTime.now() }
    val hour = wallClock.hour
    val dailyKey = wallClock.toLocalDate().toString()
    val targetUplifting = hour in 5..16
    val recommendations = remember(localSongScores, dailyKey, targetUplifting) {
        val highestPlayCount = localSongScores.maxOfOrNull { it.second.playedCount }?.coerceAtLeast(1) ?: 1
        localSongScores.sortedByDescending { (song, stats, moodAndFavorite) ->
            val (mood, favorite) = moodAndFavorite
            val moodScore = if (targetUplifting) mood?.uplifting else mood?.calm
            val historyScore = ln(stats.playedCount + 1.0) / ln(highestPlayCount + 1.0)
            val recencyScore = if (stats.lastPlayedTimestamp > 0L) {
                (1.0 - ((System.currentTimeMillis() - stats.lastPlayedTimestamp).coerceAtLeast(0L) / 1_209_600_000.0)).coerceIn(0.0, 1.0)
            } else 0.0
            val dayRotation = (homeAiStableHash("$dailyKey|${song.localUri ?: song.localPath ?: song.videoId}") % 10_000L) / 10_000.0
            (moodScore?.toDouble() ?: 0.5) * 0.45 + historyScore * 0.25 + recencyScore * 0.10 +
                (if (favorite) 0.17 else 0.0) + dayRotation * 0.13
        }.map { it.first }.take(24)
    }
    val upliftingSongs = remember(localSongScores) {
        localSongScores.mapNotNull { (song, _, moodAndFavorite) ->
            moodAndFavorite.first?.let { song to it.uplifting }
        }.sortedByDescending { it.second }.take(24).map { it.first }
    }
    val calmSongs = remember(localSongScores) {
        localSongScores.mapNotNull { (song, _, moodAndFavorite) ->
            moodAndFavorite.first?.let { song to it.calm }
        }.sortedByDescending { it.second }.take(24).map { it.first }
    }

    PullToRefresh(
        refreshing = isRefreshing,
        onRefresh = { if (onlineFeedEnabled) onRefresh() },
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "home-heading") {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.listen_now),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            item(key = "home-local-recommendations") {
                HomeAiShelfRow(
                    title = androidx.compose.ui.res.stringResource(R.string.home_ai_recommendations),
                    songs = recommendations,
                    emptyMessage = if (localSongs.isEmpty()) {
                        androidx.compose.ui.res.stringResource(R.string.home_ai_no_local_tracks)
                    } else {
                        androidx.compose.ui.res.stringResource(
                            if (targetUplifting) R.string.home_ai_recommendation_day else R.string.home_ai_recommendation_night,
                        )
                    },
                    onDownloadModel = if (!modelInstalled && allowModelDownload) {{ showModelPrompt = true }} else null,
                    onSongClick = localCatalog.onSongClick,
                    onSongLongPress = localCatalog.onSongLongPress,
                )
            }
            item(key = "home-local-most-played") {
                HomeAiShelfRow(
                    title = androidx.compose.ui.res.stringResource(R.string.home_ai_most_played),
                    songs = mostPlayed,
                    emptyMessage = androidx.compose.ui.res.stringResource(R.string.home_ai_no_play_history),
                    onSongClick = localCatalog.onSongClick,
                    onSongLongPress = localCatalog.onSongLongPress,
                )
            }
            item(key = "home-local-uplifting") {
                HomeAiShelfRow(
                    title = androidx.compose.ui.res.stringResource(R.string.home_ai_uplifting),
                    songs = upliftingSongs,
                    emptyMessage = androidx.compose.ui.res.stringResource(
                        if (modelInstalled) R.string.home_ai_analyzing else R.string.home_ai_model_needed,
                    ),
                    onDownloadModel = if (!modelInstalled && allowModelDownload) {{ showModelPrompt = true }} else null,
                    onSongClick = localCatalog.onSongClick,
                    onSongLongPress = localCatalog.onSongLongPress,
                )
            }
            item(key = "home-local-calm") {
                HomeAiShelfRow(
                    title = androidx.compose.ui.res.stringResource(R.string.home_ai_calm),
                    songs = calmSongs,
                    emptyMessage = androidx.compose.ui.res.stringResource(
                        if (modelInstalled) R.string.home_ai_analyzing else R.string.home_ai_model_needed,
                    ),
                    onDownloadModel = if (!modelInstalled && allowModelDownload) {{ showModelPrompt = true }} else null,
                    onSongClick = localCatalog.onSongClick,
                    onSongLongPress = localCatalog.onSongLongPress,
                )
            }
            if (onlineFeedEnabled) {
                item(key = "home-online-heading") {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.home_ai_youtube_music),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    )
                }
                when (state) {
                    UiState.Loading -> item(key = "home-online-loading") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is UiState.Error -> item(key = "home-online-error") {
                        MessageState(
                            message = state.message,
                            actionLabel = androidx.compose.ui.res.stringResource(R.string.retry),
                            onAction = onRetry,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        )
                    }
                    is UiState.Success -> {
                        itemsIndexed(state.data, key = { index, shelf -> "${shelf.title}:$index" }) { _, shelf ->
                            HomeShelfRow(
                                shelf = shelf,
                                onItemClick = { item -> onItemClick(item, shelf.title) },
                                onItemLongPress = onItemLongPress,
                            )
                        }
                        if (loadingMore) {
                            item(key = "home-loading-more") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }
                            }
                        }
                    }
                }
            }
        }
    }

    if (onlineFeedEnabled && state is UiState.Success) {
        val loadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(listState, loadingMore, state.data.size) {
            snapshotFlow {
                val layout = listState.layoutInfo
                val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }

    if (showModelPrompt) {
        AlertDialog(
            onDismissRequest = {
                showModelPrompt = false
                LocalHomeAi.setModelDecision(context, false)
            },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.home_ai_download_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(R.string.home_ai_download_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        LocalHomeAi.setModelDecision(context, true)
                        showModelPrompt = false
                        downloadError = null
                        scope.launch {
                            runCatching { LocalHomeAi.downloadModels(context) }
                                .onSuccess { modelInstalled = true }
                                .onFailure { downloadError = it.message }
                        }
                    },
                ) { Text(androidx.compose.ui.res.stringResource(R.string.home_ai_download_action)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        LocalHomeAi.setModelDecision(context, false)
                        showModelPrompt = false
                    },
                ) { Text(androidx.compose.ui.res.stringResource(R.string.not_now)) }
            },
        )
    }

    if (downloadProgress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(androidx.compose.ui.res.stringResource(R.string.home_ai_download_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val progress = downloadProgress!!
                    LinearProgressIndicator(
                        progress = { (progress.bytes.toFloat() / progress.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(androidx.compose.ui.res.stringResource(
                        R.string.home_ai_download_progress,
                        progress.bytes / 1_000_000.0,
                        progress.totalBytes / 1_000_000.0,
                    ))
                }
            },
            confirmButton = {},
        )
    }

    downloadError?.let { message ->
        AlertDialog(
            onDismissRequest = { downloadError = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.home_ai_download_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { downloadError = null; showModelPrompt = true }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadError = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun HomeAiShelfRow(
    title: String,
    songs: List<Song>,
    emptyMessage: String,
    onDownloadModel: (() -> Unit)? = null,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (songs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                onDownloadModel?.let { onClick ->
                    TextButton(onClick = onClick, modifier = Modifier.align(Alignment.Start)) {
                        Text(androidx.compose.ui.res.stringResource(R.string.home_ai_enable_analysis))
                    }
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(songs, key = { index, song -> "${song.localUri ?: song.localPath ?: song.videoId}:$index" }) { index, song ->
                    Column(
                        modifier = Modifier.width(126.dp).combinedClickable(
                            onClick = { onSongClick(songs, index) },
                            onLongClick = { onSongLongPress(song) },
                        ),
                    ) {
                        AsyncImage(
                            model = song.thumbnailUrl?.artworkAt(320),
                            contentDescription = song.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.height(7.dp))
                        Text(song.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun homeAiCacheKey(song: Song): String {
    val identity = song.localUri?.takeIf(String::isNotBlank)
        ?: song.localPath?.takeIf(String::isNotBlank)
        ?: song.videoId
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$identity|${song.durationText}|${song.localStartMs}|${song.localEndMs}".toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }.take(32)
}

private fun homeAiStableHash(value: String): Long =
    java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(4).fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xffL) }

private fun Song.favoriteStoreKeys(): List<String> = listOfNotNull(videoId, localUri, localPath).distinct()

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HomeShelfRow(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 22.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = shelf.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (shelf.subtitle.isNotBlank()) {
                Text(
                    text = shelf.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shelf.items, key = { item -> "${item.videoId}:${item.browseId}:${item.title}" }) { item ->
                Column(
                    modifier = Modifier
                        .width(156.dp)
                        .combinedClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = { onItemLongPress(item) },
                        ),
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(320),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
