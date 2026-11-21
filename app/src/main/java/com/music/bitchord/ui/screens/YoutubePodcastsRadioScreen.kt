package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.playSongs

/** YouTube Music Podcasts landing page exposed as one of Radio's selectable services. */
@Composable
internal fun YoutubePodcastsRadioScreen(
    controller: MediaController?,
    currentSong: Song?,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    networkUnavailable: Boolean,
    onChangeService: () -> Unit,
    onOpenShow: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?) -> Unit,
) {
    val youtubeMusicEnabled by AppSettings.youtubeMusicEnabled.collectAsStateWithLifecycle()
    val appOffline by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val youtubeAllowed = youtubeMusicEnabled && !appOffline && !networkUnavailable
    var shelves by remember { mutableStateOf<List<HomeShelf>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(youtubeAllowed, refreshKey) {
        if (!youtubeAllowed) {
            loading = false
            failed = false
            shelves = emptyList()
            return@LaunchedEffect
        }
        loading = true
        failed = false
        YtMusicRepository.podcastsLanding()
            .onSuccess { shelves = it }
            .onFailure { failed = true }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "youtube-podcasts-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.radio_service_youtube_podcasts),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { refreshKey++ }, enabled = youtubeAllowed && !loading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.service_refresh))
                }
                TextButton(onClick = onChangeService) {
                    Icon(Icons.Rounded.Tune, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.radio_change_service))
                }
            }
        }
        if (!youtubeAllowed) {
            item(key = "youtube-podcasts-disabled") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (networkUnavailable || appOffline) {
                            stringResource(R.string.radio_offline_unavailable)
                        } else {
                            stringResource(R.string.radio_youtube_podcasts_enable_youtube)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!networkUnavailable && !appOffline && !youtubeMusicEnabled) {
                        TextButton(onClick = { AppSettings.setYoutubeMusicEnabled(true) }) {
                            Text(stringResource(R.string.radio_youtube_podcasts_enable_button))
                        }
                    }
                }
            }
        } else if (loading && shelves.isEmpty()) {
            item(key = "youtube-podcasts-loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (failed && shelves.isEmpty()) {
            item(key = "youtube-podcasts-error") {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.radio_youtube_podcasts_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { refreshKey++ }) { Text(stringResource(R.string.retry)) }
                }
            }
        } else if (shelves.isEmpty()) {
            item(key = "youtube-podcasts-empty") {
                Text(
                    stringResource(R.string.radio_youtube_podcasts_empty),
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            shelves.forEachIndexed { shelfIndex, shelf ->
                item(key = "youtube-podcast-shelf-title-$shelfIndex") {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = shelf.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                        if (shelf.subtitle.isNotBlank()) {
                            Text(
                                text = shelf.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
                item(key = "youtube-podcast-shelf-items-$shelfIndex") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        shelf.items.forEachIndexed { itemIndex, item ->
                            PodcastLandingCard(
                                item = item,
                                isCurrent = item.videoId != null && item.videoId == currentSong?.videoId,
                                isPlaying = isPlaying,
                                onClick = {
                                    val browseId = item.browseId
                                    if (browseId != null) {
                                        onOpenShow(browseId, item.title, item.subtitle, item.thumbnailUrl)
                                    } else {
                                        val videoId = item.videoId ?: return@PodcastLandingCard
                                        if (videoId == currentSong?.videoId && controller != null) {
                                            if (isPlaying) controller.pause() else controller.play()
                                        } else {
                                            controller?.playSongs(
                                                listOf(
                                                    Song(
                                                        videoId = videoId,
                                                        title = item.title,
                                                        artist = item.subtitle.ifBlank { shelf.title },
                                                        thumbnailUrl = item.thumbnailUrl,
                                                    ),
                                                ),
                                                0,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        if (loading && shelves.isNotEmpty()) {
            item(key = "youtube-podcasts-refreshing") {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
        item(key = "youtube-podcasts-footer") { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun PodcastLandingCard(
    item: ShelfItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.width(156.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(142.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                )
                if (isCurrent && isPlaying) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.now_playing),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 2.dp),
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
