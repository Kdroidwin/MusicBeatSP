package com.music.bitchord.playback.cast

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import androidx.mediarouter.app.MediaRouteButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Registers one activity-lifecycle listener; the local player remains service-owned. */
@Composable
fun CastPlaybackBridge(controller: MediaController?, song: Song?) {
    val context = LocalContext.current.applicationContext
    val latestSong by rememberUpdatedState(song)

    DisposableEffect(context, controller) {
        val castContext = runCatching { CastContext.getSharedInstance(context) }.getOrNull()
            ?: return@DisposableEffect onDispose {}
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var localServer: LocalCastHttpServer? = null
        var castLoadJob: Job? = null
        var castStatusTimeoutJob: Job? = null
        var callbackClient: RemoteMediaClient? = null
        var mediaCallback: RemoteMediaClient.Callback? = null

        fun clearMediaCallback() {
            val client = callbackClient
            val callback = mediaCallback
            if (client != null && callback != null) client.unregisterCallback(callback)
            callbackClient = null
            mediaCallback = null
        }

        fun loadCurrentTrack(session: CastSession) {
            val activeController = controller ?: return
            val item = activeController.currentMediaItem ?: return
            val uri = item.localConfiguration?.uri ?: return
            val currentSong = latestSong
            val remote = session.remoteMediaClient ?: run {
                showCastMessage(context, R.string.chromecast_unavailable)
                return
            }
            castLoadJob?.cancel()
            castStatusTimeoutJob?.cancel()
            castStatusTimeoutJob = null
            clearMediaCallback()
            localServer?.close()
            localServer = null
            castLoadJob = scope.launch {
                val contentType = detectAudioMimeType(context, activeController, uri, currentSong)
                val mediaUrl = if (uri.scheme == "http" || uri.scheme == "https") {
                    uri.toString()
                } else {
                    if (uri.scheme != "file" && uri.scheme != "content") {
                        showCastMessage(context, R.string.chromecast_local_file_unavailable)
                        return@launch
                    }
                    try {
                        val server = LocalCastHttpServer(context, uri, contentType)
                        localServer = server
                        withContext(Dispatchers.IO) { server.start() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        localServer?.close()
                        localServer = null
                        showCastMessage(context, R.string.chromecast_local_file_unavailable)
                        return@launch
                    }
                }

                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                    putString(MediaMetadata.KEY_TITLE, currentSong?.title.orEmpty())
                    putString(MediaMetadata.KEY_ARTIST, currentSong?.artist.orEmpty())
                    currentSong?.thumbnailUrl
                        ?.let(Uri::parse)
                        ?.takeIf { it.scheme == "https" || it.scheme == "http" }
                        ?.let { addImage(WebImage(it)) }
                }
                val infoBuilder = MediaInfo.Builder(mediaUrl)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(contentType)
                    .setMetadata(metadata)
                if (activeController.duration > 0L) {
                    infoBuilder.setStreamDuration(activeController.duration)
                }
                var failureReported = false
                fun reportPlaybackFailure() {
                    if (failureReported) return
                    failureReported = true
                    castStatusTimeoutJob?.cancel()
                    castStatusTimeoutJob = null
                    clearMediaCallback()
                    localServer?.close()
                    localServer = null
                    showCastMessage(context, R.string.chromecast_playback_failed)
                }
                val callback = object : RemoteMediaClient.Callback() {
                    override fun onStatusUpdated() {
                        val status = remote.mediaStatus ?: return
                        when {
                            status.playerState == MediaStatus.PLAYER_STATE_PLAYING -> {
                                castStatusTimeoutJob?.cancel()
                                castStatusTimeoutJob = null
                                // Do not stop local audio merely because LOAD was
                                // acknowledged: the receiver can still be stuck
                                // fetching the file. Switch only once it plays.
                                activeController.pause()
                            }
                            status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                                status.idleReason == MediaStatus.IDLE_REASON_ERROR -> {
                                reportPlaybackFailure()
                            }
                        }
                    }

                    override fun onMediaError(error: MediaError) {
                        reportPlaybackFailure()
                    }
                }
                remote.registerCallback(callback)
                callbackClient = remote
                mediaCallback = callback
                runCatching {
                    remote.load(
                        MediaLoadRequestData.Builder()
                            .setMediaInfo(infoBuilder.build())
                            .setAutoplay(true)
                            .setCurrentTime(activeController.currentPosition.coerceAtLeast(0L))
                            .build(),
                    ).setResultCallback { result ->
                        if (!result.status.isSuccess) {
                            reportPlaybackFailure()
                        } else {
                            castStatusTimeoutJob?.cancel()
                            castStatusTimeoutJob = scope.launch {
                                kotlinx.coroutines.delay(CAST_START_TIMEOUT_MS)
                                if (remote.playerState == MediaStatus.PLAYER_STATE_LOADING ||
                                    remote.playerState == MediaStatus.PLAYER_STATE_BUFFERING
                                ) {
                                    reportPlaybackFailure()
                                }
                            }
                        }
                    }
                }.onFailure {
                    reportPlaybackFailure()
                }
            }
        }

        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = loadCurrentTrack(session)
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionStartFailed(session: CastSession, error: Int) {
                showCastMessage(context, R.string.chromecast_unavailable)
            }
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, error: Int) {
                castLoadJob?.cancel()
                castLoadJob = null
                castStatusTimeoutJob?.cancel()
                castStatusTimeoutJob = null
                clearMediaCallback()
                localServer?.close()
                localServer = null
            }
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = loadCurrentTrack(session)
            override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        castContext.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        castContext.sessionManager.currentCastSession?.let(::loadCurrentTrack)
        onDispose {
            castContext.sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
            castLoadJob?.cancel()
            castStatusTimeoutJob?.cancel()
            clearMediaCallback()
            localServer?.close()
            scope.cancel()
        }
    }
}

private const val CAST_START_TIMEOUT_MS = 30_000L

@Composable
fun CastActionItem(onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }
    if (castContext == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onUnavailable)
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Cast, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(18.dp))
            Text(stringResource(R.string.chromecast), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Cast, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(
            stringResource(R.string.chromecast),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        AndroidView(
            factory = { viewContext ->
                MediaRouteButton(viewContext).also { button ->
                    CastButtonFactory.setUpMediaRouteButton(viewContext, button)
                }
            },
            modifier = Modifier.size(48.dp),
        )
    }
}

/** Compact native Cast route button for the configurable player shortcut row. */
@Composable
fun CastQuickActionButton(onUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val castContext = remember(context) {
        runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    }
    if (castContext == null) {
        IconButton(onClick = onUnavailable, modifier = modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Cast,
                contentDescription = stringResource(R.string.chromecast),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    } else {
        AndroidView(
            factory = { viewContext ->
                MediaRouteButton(viewContext).also { button ->
                    button.contentDescription = viewContext.getString(R.string.chromecast)
                    CastButtonFactory.setUpMediaRouteButton(viewContext, button)
                }
            },
            modifier = modifier.size(48.dp),
        )
    }
}

private fun detectAudioMimeType(
    context: Context,
    controller: MediaController,
    uri: Uri,
    song: Song?,
): String {
    val itemMimeType = controller.currentMediaItem?.localConfiguration?.mimeType
        ?.takeIf { it.startsWith("audio/", ignoreCase = true) }
    val selectedAudioTrackType = controller.currentTracks.groups.asSequence()
        .flatMap { group -> (0 until group.length).asSequence().map { group.getTrackFormat(it).sampleMimeType } }
        .firstOrNull { it?.startsWith("audio/", ignoreCase = true) == true }
    val providerMimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?.takeIf { it.startsWith("audio/", ignoreCase = true) }
    val extensionMimeType = sequenceOf(song?.localPath, uri.path, song?.localUri)
        .filterNotNull()
        .mapNotNull(::mimeTypeFromPath)
        .firstOrNull()
    return itemMimeType ?: selectedAudioTrackType ?: providerMimeType ?: extensionMimeType ?: "audio/mpeg"
}

private fun mimeTypeFromPath(path: String): String? = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "ogg", "oga", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "m4a", "mp4" -> "audio/mp4"
    "aac" -> "audio/aac"
    "aif", "aiff" -> "audio/aiff"
    else -> null
}

private fun showCastMessage(context: Context, message: Int) {
    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
}
