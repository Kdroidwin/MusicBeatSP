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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import com.music.bitchord.playback.beginRadioQueue
import com.music.bitchord.playback.playSongs
import com.music.bitchord.radio.data.RssEpisode
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val foreground = if (amoledBlack) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    var feeds by remember { mutableStateOf(RssFeedStore.feeds(context)) }
    var episodes by remember { mutableStateOf<List<RssEpisode>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(feeds.map { it.id to it.url }, refreshKey, networkUnavailable) {
        if (feeds.isEmpty() || networkUnavailable) {
            episodes = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        loadError = false
        val loadResult = withContext(Dispatchers.IO) {
            var hadFailure = false
            val found = feeds.flatMap { feed ->
                val result = runCatching { RssFeedRepository.fetch(feed) }
                result.onSuccess { (title, _) -> RssFeedStore.updateTitle(context, feed.id, title) }
                if (result.isFailure) hadFailure = true
                result.getOrNull()?.second.orEmpty()
            }
            found to hadFailure
        }
        feeds = RssFeedStore.feeds(context)
        episodes = loadResult.first.sortedByDescending { it.publishedAt.orEmpty() }
        loadError = loadResult.second
        isLoading = false
    }

    val visibleEpisodes = remember(episodes, query) {
        if (query.isBlank()) episodes else episodes.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.feedTitle.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "rss-controls") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    IconButton(onClick = { refreshKey++ }, enabled = feeds.isNotEmpty() && !isLoading && !networkUnavailable) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.radio_onsen_refresh))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.rss_feed_url_hint)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        runCatching { RssFeedStore.add(context, url) }
                            .onSuccess { feeds = it; url = "" }
                            .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.rss_feed_add_failed), Toast.LENGTH_LONG).show() }
                    }) { Text(stringResource(R.string.rss_feed_add)) }
                }
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
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (loadError) {
                    Text(stringResource(R.string.rss_feed_fetch_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        feeds.forEach { feed ->
            item(key = "rss-feed:${feed.id}") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(feed.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { feeds = RssFeedStore.remove(context, feed.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.rss_feed_remove))
                    }
                }
            }
        }
        if (isLoading) {
            item(key = "rss-loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (feeds.isEmpty()) {
            item(key = "rss-empty-feeds") {
                Text(stringResource(R.string.rss_feed_empty), Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (visibleEpisodes.isEmpty() && !networkUnavailable) {
            item(key = "rss-empty-episodes") {
                Text(stringResource(R.string.rss_episode_empty), Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visibleEpisodes, key = RssEpisode::id) { episode ->
            val playing = currentSong?.isRssAudio == true && currentSong.videoId == episode.id
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = foreground),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (playing && controller != null) {
                            if (isPlaying) controller.pause() else controller.play()
                        } else if (controller == null) {
                            Toast.makeText(context, R.string.radio_play_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                runCatching {
                                    controller.beginRadioQueue()
                                    controller.playSongs(listOf(episode.toSong()), 0)
                                }.onFailure {
                                    Toast.makeText(context, R.string.radio_play_failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    episode.imageUrl?.let { image ->
                        AsyncImage(
                            model = image,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(episode.feedTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        episode.publishedAt?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Icon(
                        if (playing && isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp).size(24.dp),
                    )
                }
            }
        }
        item(key = "rss-bottom-space") { Spacer(Modifier.height(8.dp)) }
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
    isRssAudio = true,
    radioStreamUrl = audioUrl,
)
