package com.music.bitchord.data

import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One OkHttp client for the whole app.
 *
 * This matters: googlevideo binds a stream URL to the connection context of
 * the `player` request that minted it. If Innertube and ExoPlayer used
 * separate HTTP stacks they could resolve to different addresses (v4 vs v6)
 * and the media fetch would come back 403. Sharing the client keeps DNS,
 * address family and connection pooling identical for both.
 *
 * That sharing has a cost the defaults don't budget for. Every request the
 * app makes — [Innertube]'s player/browse calls, NewPipe's own signature and
 * `next`-endpoint fetches, [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource]
 * and [AudioCache][com.music.bitchord.playback.AudioCache]'s multi-megabyte
 * chunk downloads, and every [StreamResolver] probe — funnels through this
 * one client, and OkHttp's stock [Dispatcher] allows only 5 requests in
 * flight to a single host at a time. A track streaming while its successor
 * pre-caches is two or three of those requests already; a resolve running
 * alongside them queues behind whichever is occupying the rest — invisibly,
 * since a request stuck in OkHttp's queue and a slow server both just look
 * like a request that took several extra seconds. Sized well past anything
 * this app actually drives concurrently, so the queue is never the reason a
 * request was slow.
 */
object Http {

    private val activeYoutubeMusicCalls = Collections.newSetFromMap(
        ConcurrentHashMap<okhttp3.Call, Boolean>(),
    )
    private val activeNetworkCalls = Collections.newSetFromMap(
        ConcurrentHashMap<okhttp3.Call, Boolean>(),
    )

    /** Cancels only active YouTube/YouTube Music traffic when its opt-in is turned off. */
    fun cancelYoutubeMusicRequests() {
        activeYoutubeMusicCalls.toList().forEach { call -> runCatching { call.cancel() } }
    }

    /** Stops in-flight HTTP work when the user switches the whole app offline. */
    fun cancelAllNetworkRequests() {
        activeNetworkCalls.toList().forEach { call -> runCatching { call.cancel() } }
    }

    /** Stops active cleartext requests when the app-wide HTTP consent is withdrawn. */
    fun cancelCleartextRequests() {
        activeNetworkCalls.toList().forEach { call ->
            val isCleartext = runCatching { !call.request().url.isHttps }.getOrDefault(false)
            if (isCleartext) runCatching { call.cancel() }
        }
    }

    /** Stops requests and streams tied to one configured remote-server origin. */
    fun cancelRequestsForOrigin(url: String) {
        val origin = url.trim().toHttpUrlOrNull() ?: return
        activeNetworkCalls.toList().forEach { call ->
            val matches = runCatching {
                call.request().url.let { it.scheme == origin.scheme && it.host == origin.host && it.port == origin.port }
            }.getOrDefault(false)
            if (matches) runCatching { call.cancel() }
        }
    }

    private fun isYoutubeMusicRelatedHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be") ||
            host == "youtubei.googleapis.com" ||
            host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
            host == "ytimg.com" || host.endsWith(".ytimg.com") ||
            host == "ggpht.com" || host.endsWith(".ggpht.com") ||
            host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")

    // ---- Temporary data-usage instrumentation --------------------------
    //
    // Added to answer, with real numbers instead of code-reading, which of
    // this app's network callers actually account for the reported data
    // usage (issue #48): every request funnels through this one client, so
    // a network interceptor here sees every byte the app receives,
    // regardless of which subsystem asked for it. Categorised by host —
    // googlevideo.com is YouTube's own audio bytes (and StreamResolver's
    // probes, distinguishable by their small actual read against a 2MB
    // Range ask), a canvas provider's CDN is motion artwork, youtube.com /
    // youtubei is API/extraction traffic. Grep logcat for "BCDataUsage"
    // during a play session; each line is one response with its actual
    // transferred bytes and the running total for its host.
    //
    // Left in place rather than deleted — the investigation this served is
    // not the last one this app will need — but off by default: flip to
    // true only while actively measuring. Wrapping every response body in a
    // counting source and writing a log line per request is not free, and
    // paying that on every install for a question already answered is the
    // wrong trade.
    private const val USAGE_LOGGING_ENABLED = false
    private const val USAGE_TAG = "BCDataUsage"
    private val usageTotals = ConcurrentHashMap<String, AtomicLong>()

    private class CountingSource(source: Source, private val onClose: (Long) -> Unit) :
        ForwardingSource(source) {
        private var bytes = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytes += read
            return read
        }
        override fun close() {
            super.close()
            onClose(bytes)
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val counted: BufferedSource,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = counted
    }

    /** Keeps an HTTP call cancellable until its response body has been closed. */
    private class CallTrackedBody(
        private val delegate: ResponseBody,
        private val onClose: () -> Unit,
    ) : ResponseBody() {
        private val trackedSource by lazy(LazyThreadSafetyMode.NONE) {
            object : ForwardingSource(delegate.source()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        onClose()
                    }
                }
            }.buffer()
        }

        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source(): BufferedSource = trackedSource
    }

    private val usageInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else {
            val host = request.url.host
            val counting = CountingSource(body.source()) { bytes ->
                val total = usageTotals.computeIfAbsent(host) { AtomicLong() }.addAndGet(bytes)
                Log.d(
                    USAGE_TAG,
                    "$host ${request.method} status=${response.code} bytes=$bytes total[$host]=$total",
                )
            }.buffer()
            response.newBuilder().body(CountedBody(body, counting)).build()
        }
    }
    // ---- End temporary instrumentation ----------------------------------

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            if (AppSettings.offlineMode.value) throw IOException("Offline mode is enabled")
            val initialRequest = chain.request()
            val webDavAuth = com.music.bitchord.data.webdav.WebDavAuth.authHeaderFor(initialRequest.url)
            val request = if (webDavAuth != null && initialRequest.header("Authorization") == null) {
                initialRequest.newBuilder()
                    .header("Authorization", webDavAuth)
                    .header(WEB_DAV_SCOPED_AUTH_HEADER, "1")
                    .build()
            } else {
                initialRequest
            }
            val youtubeRelated = isYoutubeMusicRelatedHost(request.url.host)
            if (youtubeRelated && !AppSettings.youtubeMusicEnabled.value) {
                throw IOException("YouTube Music is disabled")
            }
            val call = chain.call()
            activeNetworkCalls.add(call)
            if (youtubeRelated) activeYoutubeMusicCalls.add(call)
            var responseBodyOwnsTracking = false
            val removeTrackedCall = {
                activeNetworkCalls.remove(call)
                if (youtubeRelated) activeYoutubeMusicCalls.remove(call)
            }
            try {
                // Recheck after registering so a request queued at the same
                // moment as a privacy switch cannot slip onto the wire.
                if (AppSettings.offlineMode.value) throw IOException("Offline mode is enabled")
                if (youtubeRelated && !AppSettings.youtubeMusicEnabled.value) {
                    throw IOException("YouTube Music is disabled")
                }
                val response = chain.proceed(request)
                val trackedResponse = response.newBuilder()
                    .body(CallTrackedBody(response.body, removeTrackedCall))
                    .build()
                responseBodyOwnsTracking = true
                trackedResponse
            } finally {
                if (!responseBodyOwnsTracking) removeTrackedCall()
            }
        }
        // Network Security Config permits cleartext at the Android layer so
        // self-hosted LAN servers can be used when the user explicitly opts
        // into HTTP for that server. Keep the actual app-wide gate here and
        // match the exact configured origin; redirects to other HTTP hosts
        // are denied as each network exchange passes through this interceptor.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val webDavScopedRequest = request.header(WEB_DAV_SCOPED_AUTH_HEADER) != null
            val carriesWebDavAuth = com.music.bitchord.data.webdav.WebDavAuth.authHeader
                ?.let { request.header("Authorization") == it } == true
            if ((webDavScopedRequest || carriesWebDavAuth) &&
                !com.music.bitchord.data.webdav.WebDavAuth.isAllowedUrl(request.url)
            ) {
                throw IOException("WebDAV credentials cannot leave the configured server path")
            }
            val carriesRemoteServerAuth = request.header("X-Emby-Authorization") != null ||
                request.header("X-Emby-Token") != null ||
                request.url.queryParameter("c") == "MusicBeatSP"
            if (carriesRemoteServerAuth &&
                !com.music.bitchord.data.sources.SourceRegistry.isRemoteServerOriginAllowed(request.url)
            ) {
                throw IOException("Remote-server credentials cannot be sent to an unconfigured host")
            }
            val cleartextAllowed = AppSettings.cleartextHttpAllowed.value && (
                com.music.bitchord.data.webdav.WebDavAuth.isCleartextAllowed(request.url) ||
                    com.music.bitchord.data.sources.SourceRegistry.isCleartextAllowed(request.url)
                )
            if (!request.url.isHttps && !cleartextAllowed) {
                throw IOException("Unencrypted HTTP is not enabled for this server")
            }
            chain.proceed(
                if (webDavScopedRequest) request.newBuilder().removeHeader(WEB_DAV_SCOPED_AUTH_HEADER).build()
                else request,
            )
        }
        .apply { if (USAGE_LOGGING_ENABLED) addNetworkInterceptor(usageInterceptor) }
        .build()

    private const val WEB_DAV_SCOPED_AUTH_HEADER = "X-MusicBeat-WebDAV-Scoped"
}
