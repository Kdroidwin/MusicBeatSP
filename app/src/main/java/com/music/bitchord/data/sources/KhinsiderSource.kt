package com.music.bitchord.data.sources

import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only adapter for the public pages Khinsider itself publishes.
 *
 * Search results are album pages; their track pages publish the actual MP3 and
 * FLAC links. No login, cookies, scripts, hidden endpoints or download tokens
 * are used. Every page and media URL is HTTPS and checked against the site's
 * known hosts before it is handed to the app/player.
 */
class KhinsiderSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.KHINSIDER
    override val displayName: String get() = config.label.ifBlank { SourceKind.KHINSIDER.label }

    // No request is needed just to render a built-in source row.
    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || AppSettings.offlineMode.value) return@withContext emptyList()
            val searchUrl = SITE_BASE.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("search", query.trim().take(MAX_QUERY_LENGTH))
                .build()
            val searchHtml = fetchHtml(searchUrl) ?: return@withContext emptyList()
            val searchDocument = Jsoup.parse(searchHtml, searchUrl.toString())

            // Results may be linked either to an album or directly to a track page.
            // Normalize track links to their album page, then fetch only a bounded
            // number of albums so one broad query cannot fan out without limit.
            val albumPages = linkedMapOf<String, HttpUrl>()
            searchDocument.select("a[href]").forEach { anchor ->
                val target = safeSiteUrl(anchor.absUrl("href")) ?: return@forEach
                val albumUrl = when {
                    isAlbumPage(target) -> target
                    isTrackPage(target) -> albumPageFor(target)
                    else -> null
                } ?: return@forEach
                albumPages.putIfAbsent(albumUrl.toString(), albumUrl)
            }

            val boundedLimit = limit.coerceIn(1, MAX_RESULTS)
            coroutineScope {
                albumPages.values.take(MAX_ALBUM_PAGES)
                    .map { albumUrl -> async { fetchAlbumTracks(albumUrl, boundedLimit) } }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.videoId }
                    .take(boundedLimit)
            }
        }

    private suspend fun fetchAlbumTracks(albumUrl: HttpUrl, limit: Int): List<Song> {
        if (AppSettings.offlineMode.value) return emptyList()
        val html = fetchHtml(albumUrl) ?: return emptyList()
        val document = Jsoup.parse(html, albumUrl.toString())
        val albumName = pageTitle(document).ifBlank { albumUrl.pathSegments.last() }
        val artist = albumArtist(document) ?: "Various Artists"
        val artwork = findArtwork(document, albumUrl, albumName)
        val tracks = linkedMapOf<String, Song>()
        document.select("a[href]").forEach { anchor ->
            if (tracks.size >= limit) return@forEach
            val trackUrl = safeSiteUrl(anchor.absUrl("href"))?.takeIf(::isTrackPage)
                ?: return@forEach
            if (albumPageFor(trackUrl) != albumUrl) return@forEach
            val row = anchor.parents().firstOrNull { it.tagName() == "tr" } ?: anchor.parent()
            val title = trackTitle(anchor) ?: return@forEach
            tracks.putIfAbsent(
                trackUrl.toString(),
                Song(
                    videoId = SourceRegistry.trackKey(config.id, trackUrl.toString()),
                    title = title,
                    artist = artist,
                    albumName = albumName,
                    thumbnailUrl = artwork,
                    durationText = row?.text()?.let(::durationText),
                    // Availability is confirmed from the track page when streaming;
                    // claiming lossless based only on a label is misleading.
                    sourceQuality = null,
                ),
            )
        }
        return tracks.values.toList()
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
        withContext(Dispatchers.IO) {
            if (AppSettings.offlineMode.value) return@withContext null
            val trackPage = safeSiteUrl(trackId)?.takeIf(::isTrackPage) ?: return@withContext null
            val html = fetchHtml(trackPage) ?: return@withContext null
            val document = Jsoup.parse(html, trackPage.toString())
            val downloads = document.select("a[href]").mapNotNull { anchor ->
                val url = safeDownloadUrl(anchor.absUrl("href")) ?: return@mapNotNull null
                val label = anchor.text().lowercase(Locale.ROOT)
                val path = url.encodedPath.lowercase(Locale.ROOT)
                when {
                    "flac" in label || path.endsWith(".flac") -> DownloadLink(url, "flac")
                    "mp3" in label || path.endsWith(".mp3") -> DownloadLink(url, "mp3")
                    else -> null
                }
            }.distinctBy { it.format }

            val flac = downloads.firstOrNull { it.format == "flac" }
            val mp3 = downloads.firstOrNull { it.format == "mp3" }
            val selected = when (request) {
                // Khinsider frequently has MP3-only albums (including the supplied
                // capture); keep them playable even when lossless is preferred.
                StreamRequest.Lossless -> flac ?: mp3
                StreamRequest.Best -> mp3 ?: flac
                is StreamRequest.Capped -> mp3
            } ?: return@withContext null

            SourceStream(
                url = selected.url.toString(),
                format = StreamFormat(codec = selected.format),
                headers = mapOf("Referer" to trackPage.toString()),
                belowRequest = request is StreamRequest.Lossless && selected.format != "flac",
            )
        }

    private suspend fun fetchHtml(url: HttpUrl): String? = suspendCancellableCoroutine { continuation ->
        if (AppSettings.offlineMode.value || !isSafeSitePage(url)) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = pageClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use {
                        if (!it.isSuccessful || !isSafeSitePage(it.request.url)) return@use null
                        val contentType = it.header("Content-Type").orEmpty()
                        if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
                            return@use null
                        }
                        Http.readBoundedUtf8(it.body, MAX_PAGE_BYTES)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                    return
                }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }

    private fun pageTitle(document: Document): String =
        (document.selectFirst("#pageContent h1") ?: document.selectFirst("h1"))
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.replace(Regex("\\s+(?:MP3|FLAC)\\s+-\\s+.*$", RegexOption.IGNORE_CASE), "")
            .orEmpty()

    private fun albumArtist(document: Document): String? {
        val artistLabel = Regex("(?i)^\\s*(?:album\\s+)?artists?\\s*:?\\s*(.+?)\\s*$")
        return document.select("tr, li, p, dt, dd, span, div")
            .asSequence()
            .map { it.ownText().ifBlank { it.text() }.replace(Regex("\\s+"), " ").trim() }
            .mapNotNull { artistLabel.matchEntire(it)?.groupValues?.getOrNull(1)?.trim() }
            .firstOrNull { it.isNotBlank() && it.length <= MAX_ARTIST_LENGTH }
    }

    private fun findArtwork(document: Document, page: HttpUrl, album: String): String? {
        val candidates = linkedMapOf<String, Int>()
        fun add(raw: String, baseScore: Int, alt: String = "") {
            val url = safeArtworkUrl(raw, page) ?: return
            val path = url.encodedPath.lowercase(Locale.ROOT)
            val text = "$path ${alt.lowercase(Locale.ROOT)}"
            if (listOf("avatar", "comment", "logo", "icon", "pixel", "user-").any(text::contains)) return
            val score = baseScore + when {
                "soundtrack" in text || "cover" in text || "album" in text -> 30
                else -> 0
            } + if (alt.contains(album, ignoreCase = true)) 10 else 0
            candidates[url.toString()] = maxOf(candidates[url.toString()] ?: Int.MIN_VALUE, score)
        }

        document.select("meta[property=og:image], meta[name=twitter:image]").forEach {
            add(it.attr("content"), 100, it.attr("alt"))
        }
        val coverSelectors = listOf(
            ".albumImage img", "#albumImage img", ".album-cover img", ".cover-art img",
            "[itemprop=image]", "img[src*=soundtrack]", "img[data-src*=soundtrack]",
        )
        coverSelectors.forEach { selector ->
            document.select(selector).forEach { addImage(it, page, ::add, 80) }
        }
        document.select("img[src], img[data-src], img[data-original]").forEach {
            addImage(it, page, ::add, 20)
        }
        return candidates.maxByOrNull { it.value }?.key
    }

    private fun addImage(
        element: Element,
        page: HttpUrl,
        add: (String, Int, String) -> Unit,
        score: Int,
    ) {
        val raw = sequenceOf("src", "data-src", "data-original", "data-lazy-src")
            .map(element::attr)
            .firstOrNull(String::isNotBlank)
            ?: element.attr("srcset").substringBefore(',').trim().substringBefore(' ')
        if (raw.isNotBlank()) add(raw, score, element.attr("alt"))
    }

    private fun trackTitle(anchor: Element): String? {
        val visible = anchor.text().replace(Regex("\\s+"), " ").trim()
        val clean = visible.replace(Regex("^\\s*\\d+(?:[.-]\\d+)*[.)]?\\s+"), "").trim()
        if (clean.isBlank() || clean.length > MAX_TITLE_LENGTH) return null
        if (clean.matches(Regex("(?i)(?:get_app|playlist_add|\\d{1,3}:\\d{2}|\\d+(?:\\.\\d+)?\\s*(?:mb|gb))"))) return null
        return clean
    }

    private fun durationText(rowText: String): String? =
        DURATION_PATTERN.find(rowText)?.value?.takeIf { it.length <= 6 }

    private fun safeSiteUrl(raw: String): HttpUrl? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host != SITE_HOST ||
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()
        ) return null
        if (url.pathSegments.size < 3 || url.pathSegments[0] != "game-soundtracks" ||
            url.pathSegments[1] != "album"
        ) return null
        return url.newBuilder().query(null).fragment(null).build()
    }

    private fun isSafeSitePage(url: HttpUrl): Boolean =
        url.scheme == "https" && url.host == SITE_HOST && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()

    private fun isAlbumPage(url: HttpUrl): Boolean = url.pathSegments.size == 3

    private fun isTrackPage(url: HttpUrl): Boolean =
        url.pathSegments.size >= 4 && url.encodedPath.endsWith(".mp3", ignoreCase = true)

    private fun albumPageFor(track: HttpUrl): HttpUrl? {
        if (!isTrackPage(track)) return null
        val path = track.encodedPath.substringBeforeLast('/')
        val album = track.newBuilder().encodedPath(path).query(null).fragment(null).build()
        return album.takeIf(::isAlbumPage)
    }

    private fun safeDownloadUrl(raw: String): HttpUrl? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host !in DOWNLOAD_HOSTS ||
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()
        ) return null
        if (!url.encodedPath.endsWith(".flac", true) && !url.encodedPath.endsWith(".mp3", true)) return null
        return url
    }

    private fun safeArtworkUrl(raw: String, page: HttpUrl): HttpUrl? {
        val url = page.resolve(raw) ?: return null
        if (url.scheme != "https" || url.host !in ARTWORK_HOSTS ||
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()
        ) return null
        val path = url.encodedPath.lowercase(Locale.ROOT)
        if (listOf(".jpg", ".jpeg", ".png", ".webp").none(path::endsWith)) return null
        return url
    }

    private data class DownloadLink(val url: HttpUrl, val format: String)

    private companion object {
        const val SITE_HOST = "downloads.khinsider.com"
        const val MAX_QUERY_LENGTH = 160
        const val MAX_RESULTS = 60
        const val MAX_ALBUM_PAGES = 3
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024
        const val MAX_TITLE_LENGTH = 180
        const val MAX_ARTIST_LENGTH = 160
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val SITE_BASE = "https://$SITE_HOST/".toHttpUrlOrNull()!!
        val DOWNLOAD_HOSTS = setOf("nu.vgmtreasurechest.com", "vgmtreasurechest.com")
        val ARTWORK_HOSTS = setOf(
            "downloads.khinsider.com", "images.khinsider.com", "vgmsite.com", "www.vgmsite.com",
            "vgmtreasurechest.com", "nu.vgmtreasurechest.com",
        )
        val DURATION_PATTERN = Regex("(?<!\\d)(?:[0-9]{1,3}):[0-5][0-9](?!\\d)")
        val pageClient = Http.untrustedClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
