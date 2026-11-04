package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.beginRadioQueue
import com.music.bitchord.playback.playSongs
import com.music.bitchord.radio.data.RssEpisode
import com.music.bitchord.radio.data.RssFeed
import com.music.bitchord.radio.data.RssFeedRepository
import com.music.bitchord.radio.data.RssFeedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RssRadioScreen(
    controller: MediaController?,
    currentSong: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    amoledBlack: Boolean,
    networkUnavailable: Boolean,
    onChangeService: () -> Unit,
    onDownloadEpisode: (Song) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val foreground = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    var feeds by remember { mutableStateOf(RssFeedStore.feeds(context)) }
    var episodes by remember { mutableStateOf<List<RssEpisode>>(emptyList()) }
    var downloadedEpisodes by remember { mutableStateOf<List<Song>>(emptyList()) }
    var feedName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedFeedId by remember { mutableStateOf<String?>(null) }
    var feedPendingDelete by remember { mutableStateOf<RssFeed?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    val activeDownloads by Downloads.active.collectAsStateWithLifecycle()

    LaunchedEffect(feeds.map { it.id to it.url }, refreshKey, networkUnavailable) {
        if (feeds.isEmpty()) {
            episodes = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        if (networkUnavailable) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        loadError = false
        val loadResult = withContext(Dispatchers.IO) {
            var hadFailure = false
            val found = mutableListOf<RssEpisode>()
            feeds.forEach { feed ->
                val result = runCatching { RssFeedRepository.fetch(feed) }
                result.onSuccess { (xmlTitle, feedEpisodes) ->
                    val storedFeed = RssFeedStore.updateTitle(context, feed.id, xmlTitle)
                        .firstOrNull { it.id == feed.id }
                    val displayTitle = storedFeed?.title ?: feed.title
                    found += feedEpisodes.map { it.copy(feedTitle = displayTitle) }
                }
                if (result.isFailure) hadFailure = true
            }
            found.sortedByDescending { it.publishedAt.orEmpty() } to hadFailure
        }
        feeds = RssFeedStore.feeds(context)
        episodes = loadResult.first
        loadError = loadResult.second
        isLoading = false
    }

    LaunchedEffect(savedDownloads) {
        downloadedEpisodes = withContext(Dispatchers.IO) {
            Downloads.getDownloadedSongs(context).filter { it.videoId.startsWith("rss-") }
        }
    }

    val selectedFeed = feeds.firstOrNull { it.id == selectedFeedId }
    LaunchedEffect(selectedFeedId, feeds) {
        if (selectedFeedId != null && selectedFeed == null) selectedFeedId = null
    }
    val visibleFeeds = remember(feeds, query) {
        if (query.isBlank()) feeds else feeds.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
    }
    val feedEpisodes = remember(episodes, selectedFeed?.id) {
        episodes.filter { it.feedId == selectedFeed?.id }
    }
    val downloadedForFeed = remember(downloadedEpisodes, selectedFeed?.title) {
        downloadedEpisodes.filter {
            it.artist.equals(selectedFeed?.title, ignoreCase = true) ||
                it.albumName.equals(selectedFeed?.title, ignoreCase = true)
        }
    }
    val remoteIds = remember(feedEpisodes) { feedEpisodes.mapTo(mutableSetOf(), RssEpisode::id) }
    val offlineOnly = remember(downloadedForFeed, remoteIds) {
        downloadedForFeed.filterNot { it.videoId in remoteIds }
    }
    val visibleEpisodes = remember(feedEpisodes, query) {
        if (query.isBlank()) feedEpisodes else feedEpisodes.filter {
            it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
        }
    }
    val visibleOffline = remember(offlineOnly, query) {
        if (query.isBlank()) offlineOnly else offlineOnly.filter { it.title.contains(query, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selectedFeed == null) {
            item(key = "rss-shows-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.radio_service_rss), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.rss_feed_count, feeds.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onChangeService) {
                        Icon(Icons.Rounded.Radio, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.radio_change_service))
                    }
                    IconButton(
                        onClick = { refreshKey++ },
                        enabled = feeds.isNotEmpty() && !isLoading && !networkUnavailable,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.radio_onsen_refresh))
                    }
                }
            }
            item(key = "rss-add-form") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = feedName,
                        onValueChange = { feedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.rss_feed_name_hint)) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.rss_feed_url_hint)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = feedName.isNotBlank() && url.isNotBlank(),
                            onClick = {
                                runCatching { RssFeedStore.add(context, url, feedName) }
                                    .onSuccess {
                                        feeds = it
                                        feedName = ""
                                        url = ""
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            it.message ?: context.getString(R.string.rss_feed_add_failed),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                            },
                        ) { Text(stringResource(R.string.rss_feed_add)) }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = { Text(stringResource(R.string.rss_shows_search)) },
                    )
                }
            }
            if (networkUnavailable) {
                item(key = "rss-offline") {
                    Text(
                        stringResource(R.string.radio_offline_unavailable),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (loadError) {
                item(key = "rss-feed-error") {
                    Text(
                        stringResource(R.string.rss_feed_fetch_failed),
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (isLoading && feeds.isNotEmpty()) {
                item(key = "rss-feed-loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (feeds.isEmpty()) {
                item(key = "rss-empty-feeds") {
                    Text(
                        stringResource(R.string.rss_feed_empty),
                        Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (visibleFeeds.isEmpty()) {
                item(key = "rss-empty-shows") {
                    Text(
                        stringResource(R.string.rss_show_empty),
                        Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleFeeds, key = { "rss-feed:${it.id}" }) { feed ->
                val remoteFeedEpisodes = episodes.filter { it.feedId == feed.id }
                val remoteIds = remoteFeedEpisodes.mapTo(mutableSetOf(), RssEpisode::id)
                val savedForFeed = downloadedEpisodes.filter { it.artist.equals(feed.title, ignoreCase = true) }
                val episodeCount = remoteFeedEpisodes.size + savedForFeed.count { it.videoId !in remoteIds }
                val cover = episodes.firstOrNull { it.feedId == feed.id }?.imageUrl
                    ?: downloadedEpisodes.firstOrNull { it.artist.equals(feed.title, ignoreCase = true) }?.thumbnailUrl
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = foreground),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedFeedId = feed.id
                            query = ""
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        cover?.let { image ->
                            AsyncImage(
                                model = image,
                                contentDescription = null,
                                modifier = Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.rss_episode_count, episodeCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                feed.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { feedPendingDelete = feed }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.rss_feed_remove))
                        }
                    }
                }
            }
        } else {
            item(key = "rss-show-header:${selectedFeed.id}") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedFeedId = null; query = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.rss_back_to_shows))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(selectedFeed.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.rss_episode_count, feedEpisodes.size + offlineOnly.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { refreshKey++ },
                        enabled = !isLoading && !networkUnavailable,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.radio_onsen_refresh))
                    }
                    IconButton(onClick = { feedPendingDelete = selectedFeed }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.rss_feed_remove))
                    }
                }
                Text(
                    selectedFeed.url,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item(key = "rss-episode-search:${selectedFeed.id}") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = { Text(stringResource(R.string.rss_feed_search)) },
                    )
                    if (networkUnavailable) {
                        Text(
                            stringResource(R.string.radio_offline_unavailable),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (loadError) {
                        Text(stringResource(R.string.rss_feed_fetch_failed), color = MaterialTheme.colorScheme.error)
                    }
                    if (isLoading && feedEpisodes.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    if (visibleOffline.isNotEmpty()) {
                        Text(
                            stringResource(R.string.rss_downloaded_episodes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            items(visibleOffline, key = { "rss-offline:${it.videoId}" }) { song ->
                val playing = currentSong?.videoId == song.videoId
                RssEpisodeCard(
                    title = song.title,
                    subtitle = song.durationText.orEmpty(),
                    imageUrl = song.thumbnailUrl,
                    foreground = foreground,
                    playing = playing,
                    isPlaying = isPlaying,
                    isDownloaded = true,
                    isDownloading = false,
                    downloadAvailable = !networkUnavailable,
                    onPlay = { playEpisode(controller, song, currentSong, isPlaying, context, scope) },
                    onDownload = {},
                )
            }
            items(visibleEpisodes, key = RssEpisode::id) { episode ->
                val song = episode.toSong()
                val playing = currentSong?.videoId == episode.id
                val downloadState = activeDownloads[episode.id]
                val isDownloading = downloadState is DownloadState.Queued || downloadState is DownloadState.Running
                RssEpisodeCard(
                    title = episode.title,
                    subtitle = episode.publishedAt.orEmpty(),
                    imageUrl = episode.imageUrl,
                    foreground = foreground,
                    playing = playing,
                    isPlaying = isPlaying,
                    isDownloaded = episode.id in savedDownloads,
                    isDownloading = isDownloading,
                    downloadAvailable = !networkUnavailable,
                    onPlay = { playEpisode(controller, song, currentSong, isPlaying, context, scope) },
                    onDownload = {
                        when {
                            episode.id in savedDownloads -> Toast.makeText(context, R.string.already_downloaded, Toast.LENGTH_SHORT).show()
                            isDownloading -> Unit
                            !AppSettings.downloadsAllowedNow -> Toast.makeText(context, R.string.wifi_only_download_refusal, Toast.LENGTH_LONG).show()
                            else -> onDownloadEpisode(song)
                        }
                    },
                )
            }
            if (!isLoading && visibleEpisodes.isEmpty() && visibleOffline.isEmpty() && !networkUnavailable) {
                item(key = "rss-empty-episodes:${selectedFeed.id}") {
                    Text(
                        stringResource(R.string.rss_episode_empty),
                        Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "rss-bottom-space") { Spacer(Modifier.height(8.dp)) }
    }

    feedPendingDelete?.let { feed ->
        AlertDialog(
            onDismissRequest = { feedPendingDelete = null },
            title = { Text(stringResource(R.string.rss_delete_feed_title, feed.title)) },
            text = { Text(stringResource(R.string.rss_delete_feed_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        feeds = RssFeedStore.remove(context, feed.id)
                        episodes = episodes.filterNot { it.feedId == feed.id }
                        if (selectedFeedId == feed.id) selectedFeedId = null
                        feedPendingDelete = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { feedPendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RssEpisodeCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    foreground: Color,
    playing: Boolean,
    isPlaying: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadAvailable: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = foreground),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            imageUrl?.let { image ->
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDownload, enabled = !isDownloaded && !isDownloading && downloadAvailable) {
                when {
                    isDownloaded -> Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.rss_episode_downloaded))
                    isDownloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Icon(Icons.Rounded.FileDownload, contentDescription = stringResource(R.string.rss_download_episode))
                }
            }
            Icon(
                if (playing && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(24.dp),
            )
        }
    }
}

private fun playEpisode(
    controller: MediaController?,
    song: Song,
    currentSong: Song?,
    isPlaying: Boolean,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (currentSong?.videoId == song.videoId && controller != null) {
        if (isPlaying) controller.pause() else controller.play()
    } else if (controller == null) {
        Toast.makeText(context, R.string.radio_play_failed, Toast.LENGTH_SHORT).show()
    } else {
        scope.launch {
            runCatching {
                controller.beginRadioQueue()
                controller.playSongs(listOf(song), 0)
            }.onFailure {
                Toast.makeText(context, R.string.radio_play_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun RssEpisode.toSong(): Song = Song(
    videoId = id,
    title = title,
    artist = feedTitle,
    albumName = feedTitle,
    thumbnailUrl = imageUrl,
    durationText = durationMs?.let { millis ->
        val totalSeconds = millis / 1000
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    },
    radioName = feedTitle,
    radioStreamUrl = audioUrl,
    isRssAudio = true,
    radioAudioMimeType = audioMimeType,
)
