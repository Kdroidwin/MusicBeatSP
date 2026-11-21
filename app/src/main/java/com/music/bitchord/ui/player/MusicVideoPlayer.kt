package com.music.bitchord.ui.player

import android.content.Context
import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.metrolist.innertubex.extraction.StreamResolveException
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.ChunkedDataSource
import com.music.bitchord.playback.toSong
import com.music.bitchord.ui.rememberIsForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** A silent video-only peer follows audio without entering the service's DSP/audio chain. */
@OptIn(UnstableApi::class)
internal object MusicVideoEngine {
    private const val CHUNK_BYTES = 1024L * 1024L
    private const val VIDEO_CACHE_TTL_MS = 45 * 60 * 1000L
    private const val SEEK_DRIFT_MS = 500L
    private const val MAX_REFUSED_CLIENTS = 3
    private const val MAX_REMEMBERED_VIDEOS = 12
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: Player? = null
    private var videoPlayer: ExoPlayer? = null
    private class Surface(val view: TextureView, val priority: Int)
    private val surfaces = mutableListOf<Surface>()
    private var loadedId: String? = null
    private var loadedProfile: String? = null
    private var loadJob: Job? = null
    private var syncJob: Job? = null
    private var releaseJob: Job? = null
    private val refusedProfiles = mutableMapOf<String, MutableSet<String>>()
    private val streams = LinkedHashMap<String, Pair<InnerTubeXResolver.VideoStream, Long>>()

    private val _shownVideoId = MutableStateFlow<String?>(null)
    val shownVideoId = _shownVideoId.asStateFlow()
    private val _frameOn = MutableStateFlow<TextureView?>(null)
    val frameOn = _frameOn.asStateFlow()
    private val _aspect = MutableStateFlow(16f / 9f)
    val aspect = _aspect.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing = _playing.asStateFlow()

    private fun topSurface(): TextureView? = surfaces.maxWithOrNull(
        compareBy<Surface> { it.priority }.thenBy { surfaces.indexOf(it) },
    )?.view

    init {
        scope.launch {
            AppSettings.inAppVideoPlaybackEnabled.drop(1).collect { enabled ->
                if (enabled) refresh() else unload()
            }
        }
        scope.launch {
            InnerTubeXResolver.sessionGeneration.drop(1).collect {
                streams.clear()
                refusedProfiles.clear()
                refresh()
            }
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            sync()
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = sync()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = sync(forceSeek = true)
    }

    private val videoListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            _frameOn.value = topSurface()
            _shownVideoId.value = loadedId
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val width = videoSize.width * videoSize.pixelWidthHeightRatio
            if (width > 0f && videoSize.height > 0) _aspect.value = width / videoSize.height
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = loadedId ?: return
            val profile = loadedProfile
            val refusal = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .any { it.responseCode in setOf(403, 404, 410) }
            if (profile != null && refusal) {
                val videoId = currentSong()?.videoId ?: id
                val skipped = refusedProfiles.getOrPut(videoId) { linkedSetOf() }
                if (skipped.add(profile) && skipped.size <= MAX_REFUSED_CLIENTS) {
                    InnerTubeXResolver.onVideoRefused()
                    streams.remove(streamKey(videoId, 1080))
                    streams.remove(streamKey(videoId, 480))
                    load(id)
                    return
                }
            }
            TrackLog.w("MusicVideo", "video stream failed (${error.errorCodeName})", about = id)
            unload()
        }
    }

    fun attach(player: Player?) {
        if (controller === player) return
        controller?.removeListener(controllerListener)
        controller = player
        player?.addListener(controllerListener)
        _playing.value = player?.isPlaying == true
        refresh()
    }

    fun claim(view: TextureView, priority: Int): () -> Unit {
        releaseJob?.cancel()
        surfaces.removeAll { it.view === view }
        surfaces += Surface(view, priority)
        appContext = view.context.applicationContext
        val current = ensurePlayer(view.context.applicationContext)
        if (topSurface() === view) {
            _frameOn.value = null
            current.setVideoTextureView(view)
            refresh()
        }
        return release@{
            val wasTop = topSurface() === view
            surfaces.removeAll { it.view === view }
            if (!wasTop) return@release
            if (_frameOn.value === view) _frameOn.value = null
            val next = topSurface()
            if (next != null) {
                current.setVideoTextureView(next)
            } else {
                current.clearVideoTextureView(view)
                releaseJob = scope.launch {
                    delay(2500L)
                    if (surfaces.isEmpty()) unload()
                }
            }
        }
    }

    private fun ensurePlayer(context: Context): ExoPlayer = videoPlayer ?: ExoPlayer.Builder(context)
        .build()
        .apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            addListener(videoListener)
        }
        .also { videoPlayer = it }

    private fun currentSong(): Song? = controller?.currentMediaItem?.toSong()

    private fun wantedSong(): Song? {
        if (!AppSettings.inAppVideoPlaybackEnabled.value || surfaces.isEmpty()) return null
        return currentSong()?.takeIf(Song::isVideo)
    }

    private fun localVideoUri(song: Song): Uri? {
        val candidate = song.localUri ?: song.videoId.takeIf {
            it.startsWith("content://") || it.startsWith("file://")
        } ?: return null
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("content", "file")) return null
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        return uri.takeIf { song.isExternalPreview || extension in LOCAL_VIDEO_EXTENSIONS }
    }

    private fun identity(song: Song): String = "${song.videoId}|${localVideoUri(song) ?: "youtube"}"
    fun identityFor(song: Song): String = identity(song)

    private fun refresh() {
        val song = wantedSong()
        if (song == null) {
            unload()
            return
        }
        val id = identity(song)
        if (id == loadedId) {
            sync()
            return
        }
        load(id)
    }

    private fun load(id: String) {
        loadJob?.cancel()
        syncJob?.cancel()
        val exo = videoPlayer
        exo?.stop()
        exo?.clearMediaItems()
        loadedId = id
        loadedProfile = null
        _shownVideoId.value = null
        _frameOn.value = null
        val song = wantedSong() ?: return
        val currentIdentity = identity(song)
        if (currentIdentity != id) return
        loadJob = scope.launch {
            try {
                val sourceAndAspect = localVideoUri(song)?.let { uri ->
                    val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                        ?.takeIf { it.startsWith("video/") }
                    val item = MediaItem.Builder().setUri(uri).apply { if (mime != null) setMimeType(mime) }.build()
                    ProgressiveMediaSource.Factory(DefaultDataSource.Factory(videoPlayerContext()))
                        .createMediaSource(item) to null
                } ?: run {
                    val cap = if (AppSettings.meteredConnection.value == true) 480 else 1080
                    val cached = cachedVideo(song.videoId, cap)
                    val stream = cached ?: withContext(Dispatchers.IO) {
                        InnerTubeXResolver.extractVideo(
                            videoId = song.videoId,
                            maxHeight = cap,
                            skipClients = refusedProfiles[song.videoId].orEmpty(),
                        )
                    }?.also { streams[streamKey(song.videoId, cap)] = it to System.currentTimeMillis() }
                    if (stream == null || stream.aspect <= 0f || !stream.url.startsWith("https://")) {
                        throw IllegalStateException("No playable video stream")
                    }
                    loadedProfile = stream.profileId
                    val upstream = OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(stream.headers)
                    val source = ProgressiveMediaSource.Factory(ChunkedDataSource.Factory(upstream, CHUNK_BYTES))
                        .createMediaSource(MediaItem.fromUri(stream.url))
                    source to stream.aspect
                }
                if (loadedId != id || surfaces.isEmpty() || !AppSettings.inAppVideoPlaybackEnabled.value) return@launch
                val player = videoPlayer ?: return@launch
                sourceAndAspect.second?.let { _aspect.value = it }
                val position = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                player.setMediaSource(sourceAndAspect.first, position)
                player.playWhenReady = controller?.isPlaying == true
                player.prepare()
                startSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (loadedId == id) {
                    TrackLog.w("MusicVideo", "could not load video: ${error.message}", about = song.videoId)
                    unload()
                }
            }
        }
    }

    // ExoPlayer's application context is intentionally not exposed; surfaces give
    // the player a context, retained only as an application reference here.
    private var appContext: Context? = null
    private fun videoPlayerContext(): Context = appContext
        ?: throw IllegalStateException("Video player context is unavailable")

    private fun cachedVideo(videoId: String, cap: Int): InnerTubeXResolver.VideoStream? {
        val key = streamKey(videoId, cap)
        val (stream, at) = streams[key] ?: return null
        if (System.currentTimeMillis() - at > VIDEO_CACHE_TTL_MS) {
            streams.remove(key)
            return null
        }
        return stream
    }

    private fun streamKey(videoId: String, cap: Int): String = "$videoId@$cap"

    private fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                sync()
                delay(250L)
            }
        }
    }

    private fun sync(forceSeek: Boolean = false) {
        val audio = controller ?: return
        val video = videoPlayer ?: return
        if (loadedId == null || video.playbackState == Player.STATE_IDLE) return
        val audioPosition = audio.currentPosition.coerceAtLeast(0L)
        val drift = video.currentPosition - audioPosition
        if (!audio.isPlaying) {
            video.playWhenReady = false
            setVideoSpeed(video, audio.playbackParameters.speed)
            if (forceSeek || abs(drift) > 80L) video.seekTo(audioPosition)
            return
        }
        video.playWhenReady = true
        setVideoSpeed(video, audio.playbackParameters.speed)
        if (video.playbackState != Player.STATE_BUFFERING && (forceSeek || abs(drift) > SEEK_DRIFT_MS)) {
            video.seekTo(audioPosition)
        }
    }

    private fun setVideoSpeed(player: ExoPlayer, speed: Float) {
        if (abs(player.playbackParameters.speed - speed) > 0.01f) {
            player.playbackParameters = PlaybackParameters(speed)
        }
    }

    private fun unload() {
        loadJob?.cancel()
        loadJob = null
        syncJob?.cancel()
        syncJob = null
        loadedId = null
        loadedProfile = null
        _shownVideoId.value = null
        _frameOn.value = null
        videoPlayer?.apply {
            stop()
            clearMediaItems()
        }
        while (streams.size > MAX_REMEMBERED_VIDEOS) streams.remove(streams.keys.first())
    }

    private val LOCAL_VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "ts", "avi")
}

@OptIn(UnstableApi::class)
@Composable
internal fun AndroidMusicVideo(song: Song, modifier: Modifier, priority: Int) {
    val enabled by AppSettings.inAppVideoPlaybackEnabled.collectAsStateWithLifecycle()
    val foreground = rememberIsForeground()
    var view by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(view, enabled, foreground, priority) {
        val target = view
        val release = if (enabled && song.isVideo && target != null && foreground) {
            MusicVideoEngine.claim(target, priority)
        } else null
        onDispose { release?.invoke() }
    }
    val shown by MusicVideoEngine.shownVideoId.collectAsStateWithLifecycle()
    val frameOn by MusicVideoEngine.frameOn.collectAsStateWithLifecycle()
    val aspect by MusicVideoEngine.aspect.collectAsStateWithLifecycle()
    val id = remember(song.videoId, song.localUri) { MusicVideoEngine.identityFor(song) }
    val visible = enabled && song.isVideo && view != null && frameOn === view && shown == id
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(250), label = "videoSurfaceAlpha")

    if (enabled && song.isVideo) {
        Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        isOpaque = false
                        this.alpha = 0f
                        view = this
                    }
                },
                update = { it.alpha = alpha },
                onRelease = { if (view === it) view = null },
                modifier = Modifier.layout { measurable, constraints ->
                    val width = constraints.maxWidth.coerceAtLeast(1)
                    val height = constraints.maxHeight.coerceAtLeast(1)
                    val ratio = aspect.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
                    val boundsAspect = width.toFloat() / height
                    val (videoWidth, videoHeight) = if (boundsAspect > ratio) {
                        (height * ratio).roundToInt() to height
                    } else {
                        width to (width / ratio).roundToInt()
                    }
                    val placeable = measurable.measure(Constraints.fixed(videoWidth.coerceAtLeast(1), videoHeight.coerceAtLeast(1)))
                    layout(width, height) { placeable.place((width - videoWidth) / 2, (height - videoHeight) / 2) }
                },
            )
        }
    }
}
