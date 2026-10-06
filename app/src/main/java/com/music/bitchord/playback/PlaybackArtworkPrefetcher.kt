package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.localmusic.coil.LocalAudioArtworkFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Warms upcoming artwork without competing with the current track's first audio buffer. */
internal object PlaybackArtworkPrefetcher {

    private const val PREFETCH_DELAY_MS = 1_500L
    private const val SAME_ARTWORK_COOLDOWN_MS = 30_000L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingKey: String? = null
    private var pendingJob: Job? = null
    private var pendingLocalAudio = false
    private var lastAttemptedUrl: String? = null
    private var lastAttemptedAtMs = 0L

    fun cancel() {
        synchronized(lock) {
            // Local artwork reads are short, offline, and useful after resume too.
            // Let an in-flight local request finish and populate Coil's cache.
            if (pendingLocalAudio && pendingJob?.isActive == true) return
            pendingJob?.cancel()
            pendingJob = null
            pendingKey = null
            pendingLocalAudio = false
        }
    }

    /** Prefetch only the upcoming track; the visible player owns current-track artwork. */
    fun prefetch(context: Context, nextSong: Song?) {
        data class Candidate(val url: String, val localAudio: Boolean)

        val candidates = buildList {
            nextSong?.artworkAt(PLAYER_ART_PX)?.takeIf(String::isNotBlank)?.let { url ->
                add(Candidate(url, LocalAudioArtworkFetcher.isLocalAudioUri(Uri.parse(url))))
            }
        }.distinctBy(Candidate::url)

        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val eligible = candidates.filter { candidate ->
                candidate.localAudio || candidate.url != lastAttemptedUrl ||
                    now - lastAttemptedAtMs >= SAME_ARTWORK_COOLDOWN_MS
            }
            val key = eligible.joinToString("\u0000") { it.url }
            if (eligible.isEmpty()) {
                pendingJob?.cancel()
                pendingJob = null
                pendingKey = null
                pendingLocalAudio = false
                return
            }
            if (key == pendingKey && pendingJob?.isActive == true) {
                return
            }

            pendingJob?.cancel()
            pendingJob = null
            pendingKey = null
            pendingLocalAudio = false

            val appContext = context.applicationContext
            pendingKey = key
            pendingLocalAudio = eligible.any(Candidate::localAudio)
            pendingJob = scope.launch {
                val completedUrls = mutableListOf<Candidate>()
                try {
                    eligible.forEach { candidate ->
                        // Local next-track covers can be warmed while the current
                        // track is playing. Keep the delay for remote sources too,
                        // where artwork work can compete with the audio stream.
                        if (!candidate.localAudio) delay(PREFETCH_DELAY_MS)
                        if (AppSettings.offlineMode.value && candidate.url.isRemote()) return@forEach

                        val request = ImageRequest.Builder(appContext)
                            .data(candidate.url)
                            // Match NowPlayingScreen's request so its first draw can
                            // reuse this memory-cache entry instead of fetching again.
                            .size(PLAYER_ART_PX)
                            .build()
                        SingletonImageLoader.get(appContext).execute(request)
                        completedUrls += candidate
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A prefetch is opportunistic; the visible player request
                    // remains the source of truth and retries by itself.
                } finally {
                    synchronized(lock) {
                        if (pendingKey == key) {
                            pendingKey = null
                            pendingJob = null
                            pendingLocalAudio = false
                            completedUrls.lastOrNull { !it.localAudio }?.let { lastRemote ->
                                lastAttemptedUrl = lastRemote.url
                                lastAttemptedAtMs = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun String.isRemote(): Boolean = when (Uri.parse(this).scheme) {
        "http", "https" -> true
        else -> false
    }
}
