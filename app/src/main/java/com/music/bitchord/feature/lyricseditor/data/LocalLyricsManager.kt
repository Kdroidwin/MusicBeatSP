package com.music.bitchord.feature.lyricseditor.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.kyant.taglib.TagLib
import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.EnhancedLrc
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.WORD_LYRICS_FIELD
import com.music.bitchord.data.lyrics.cleanArtistForSearch
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.lyricseditor.domain.model.LyricsEditorSource
import com.music.bitchord.feature.lyricseditor.domain.model.LyricsSearchResultItem
import com.music.bitchord.feature.lyricseditor.domain.model.LyricsMatchScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.doubleOrNull

object LocalLyricsManager {

    private const val TAG = "LocalLyricsManager"
    private const val MAX_LOCAL_LYRICS_BYTES = 1024 * 1024
    private const val MAX_SEARCH_QUERY_CHARS = 256
    private const val MAX_SEARCH_RESULTS_PER_PROVIDER = 80
    private const val MAX_EXTENSION_SEARCH_RESPONSE_CHARS = 2 * 1024 * 1024
    private const val MAX_SEARCH_LYRICS_CHARS = 500_000
    private const val MAX_SEARCH_METADATA_CHARS = 512
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadedCache = mutableMapOf<String, String>()

    data class DownloadedLyricsResult(
        val plain: String? = null,
        val synced: String? = null,
    ) {
        val hasBoth: Boolean get() = !plain.isNullOrBlank() && !synced.isNullOrBlank()
        val best: String? get() = synced?.ifBlank { null } ?: plain?.ifBlank { null }
    }

    suspend fun readLyrics(
        context: Context,
        song: Song,
        source: LyricsEditorSource,
    ): String = withContext(Dispatchers.IO) {
        when (source) {
            LyricsEditorSource.Embedded -> readEmbeddedLyrics(context, song)
            LyricsEditorSource.File -> readFileLyrics(context, song)
            LyricsEditorSource.Downloaded -> readDownloadedLyrics(context, song)
        }
    }

    suspend fun saveLyrics(
        context: Context,
        song: Song,
        source: LyricsEditorSource,
        lyricsText: String,
    ): Boolean = withContext(Dispatchers.IO) {
        when (source) {
            LyricsEditorSource.Embedded -> saveEmbeddedLyrics(context, song, lyricsText)
            LyricsEditorSource.File -> saveFileLyrics(context, song, lyricsText)
            LyricsEditorSource.Downloaded -> saveDownloadedLyrics(song, lyricsText)
        }
    }

    /**
     * Automatically writes online lyrics into a local music file's metadata tags without user prompts.
     * If embedded tag saving fails, safely falls back to a companion .lrc file beside the track.
     */
    suspend fun autoEmbedLyrics(
        context: Context,
        song: Song,
        lyricsText: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (lyricsText.isBlank()) return@withContext false
        val embeddedOk = saveEmbeddedLyrics(context, song, lyricsText)
        if (embeddedOk) {
            return@withContext true
        }
        saveFileLyrics(context, song, lyricsText)
    }

    suspend fun autoDownload(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
        sources: Set<LyricsSource> = AppSettings.lyricsSources.value,
        order: List<LyricsSource> = AppSettings.lyricsSourceOrder.value,
        prioritizeSyllableSync: Boolean = AppSettings.prioritizeSyllableSync.value,
    ): DownloadedLyricsResult = withContext(Dispatchers.IO) {
        val repoResult = runCatching {
            LyricsRepository.lyrics(
                videoId = "",
                title = title,
                artist = artist,
                durationMs = durationMs,
                album = album,
                sources = sources,
                order = order,
                prioritizeSyllableSync = prioritizeSyllableSync,
            )
        }.getOrNull()

        if (repoResult != null && repoResult.lines.isNotEmpty()) {
            val lines = repoResult.lines
            val synced = lines.toLrc().takeIf { it.isNotBlank() }
            val plain = lines.map { it.text }.filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
            if (!synced.isNullOrBlank() || !plain.isNullOrBlank()) {
                return@withContext DownloadedLyricsResult(plain = plain, synced = synced)
            }
        }

        downloadFromLrcLib(title = title, artist = artist, album = album, durationMs = durationMs)
    }

    suspend fun downloadFromLrcLib(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
    ): DownloadedLyricsResult = withContext(Dispatchers.IO) {
        val cleanArtist = cleanArtistForSearch(artist)
        val normalizedArtist = if (artist.contains(";")) artist.replace(";", ",").trim() else artist.trim()
        val directResult = queryLrcLibDirect(title, normalizedArtist, album)
            ?: if (cleanArtist.isNotBlank() && !cleanArtist.equals(normalizedArtist, ignoreCase = true)) {
                queryLrcLibDirect(title, cleanArtist, album)
            } else null

        if (directResult != null) {
            return@withContext directResult
        }

        val lines = runCatching {
            LyricsRepository.lyrics(
                videoId = "",
                title = title,
                artist = normalizedArtist,
                durationMs = durationMs,
                album = album,
            )?.lines ?: if (cleanArtist.isNotBlank() && !cleanArtist.equals(normalizedArtist, ignoreCase = true)) {
                LyricsRepository.lyrics(
                    videoId = "",
                    title = title,
                    artist = cleanArtist,
                    durationMs = durationMs,
                    album = album,
                )?.lines
            } else null
        }.getOrNull()

        val synced = lines?.takeIf { it.isNotEmpty() }?.toLrc()
        val plain = lines?.map { it.text }?.filter { it.isNotBlank() }?.joinToString("\n")?.takeIf { it.isNotBlank() }

        DownloadedLyricsResult(plain = plain, synced = synced)
    }

    private fun queryLrcLibDirect(
        title: String,
        artist: String,
        album: String?,
    ): DownloadedLyricsResult? = runCatching {
        val builder = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
        if (!album.isNullOrBlank()) {
            builder.addQueryParameter("album_name", album)
        }
        val url = builder.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BitChord (https://github.com/bitchord)")
            .build()
        Http.lyricsClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = Http.readBoundedUtf8(response.body, 2 * 1024 * 1024)
                if (!body.isNullOrBlank()) {
                    val hits = json.parseToJsonElement(body) as? JsonArray
                    val firstHit = hits?.firstOrNull() as? JsonObject
                    if (firstHit != null) {
                        val synced = firstHit["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        val plain = firstHit["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        if (!synced.isNullOrBlank() || !plain.isNullOrBlank()) {
                            DownloadedLyricsResult(plain = plain, synced = synced)
                        } else null
                    } else null
                } else null
            } else null
        }
    }.getOrNull()

    suspend fun searchAllProviders(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
        providers: Set<LyricsSource> = AppSettings.lyricsSources.value,
    ): List<LyricsSearchResultItem> = withContext(Dispatchers.IO) {
        val safeTitle = title.trim().take(MAX_SEARCH_QUERY_CHARS)
        val safeArtist = artist.trim().take(MAX_SEARCH_QUERY_CHARS)
        val safeAlbum = album?.trim()?.take(MAX_SEARCH_QUERY_CHARS)?.takeIf(String::isNotBlank)
        val enabledProviders = providers.take(64)
        val results = mutableListOf<LyricsSearchResultItem>()
        val primaryArtist = cleanArtistForSearch(safeArtist)
        val normalizedArtist = if (safeArtist.contains(";")) safeArtist.replace(";", ",").trim() else safeArtist

        coroutineScope {
            val jobs = mutableListOf<Deferred<List<LyricsSearchResultItem>>>()

            for (source in enabledProviders) {
                if (source.id == "lrclib") {
                    jobs += async {
                        searchLrcLib(safeTitle, safeArtist, safeAlbum)
                    }
                } else {
                    jobs += async {
                        // Tinque exposes native, multi-candidate searches for these providers.
                        // MusicBeatSP previously fell straight back to LyricsRepository.fetch,
                        // which only returns the first usable lyric and hides alternate matches.
                        val nativeSearchId = com.music.bitchord.data.lyrics.BuiltinLyricsSources.nativeSearchId(source.id)
                        if (nativeSearchId != null) {
                            val candidates = runCatching {
                                com.music.bitchord.data.lyrics.BuiltinLyricsSources.search(
                                    id = source.id,
                                    title = safeTitle,
                                    artist = primaryArtist.ifBlank { normalizedArtist },
                                    album = safeAlbum,
                                    durationMs = durationMs,
                                ).orEmpty()
                            }.getOrDefault(emptyList())
                            val parsedCandidates = candidates.take(MAX_SEARCH_RESULTS_PER_PROVIDER).mapNotNull { candidate ->
                                val bestRaw = candidate.bestRaw
                                if (bestRaw.isNullOrBlank()) return@mapNotNull null
                                if (bestRaw.length > MAX_SEARCH_LYRICS_CHARS) return@mapNotNull null
                                val rawResult = LyricsSearchResultItem(
                                    id = candidate.id.take(MAX_SEARCH_METADATA_CHARS),
                                    title = candidate.title.take(MAX_SEARCH_METADATA_CHARS),
                                    artist = candidate.artist.take(MAX_SEARCH_METADATA_CHARS),
                                    album = candidate.album?.take(MAX_SEARCH_METADATA_CHARS),
                                    durationSeconds = candidate.durationSeconds,
                                    provider = candidate.provider.ifBlank { source.label }.take(MAX_SEARCH_METADATA_CHARS),
                                    syncedLyrics = candidate.syncedLyrics?.takeIf { it.length <= MAX_SEARCH_LYRICS_CHARS },
                                    plainLyrics = candidate.plainLyrics?.takeIf { it.length <= MAX_SEARCH_LYRICS_CHARS },
                                )
                                rawResult.copy(
                                    title = rawResult.title.ifBlank { safeTitle }.take(MAX_SEARCH_METADATA_CHARS),
                                    artist = rawResult.artist.ifBlank { safeArtist }.take(MAX_SEARCH_METADATA_CHARS),
                                    album = rawResult.album ?: safeAlbum?.take(MAX_SEARCH_METADATA_CHARS),
                                    matchScorePercent = LyricsMatchScorer.percent(
                                        queryTitle = safeTitle,
                                        queryArtist = safeArtist,
                                        queryAlbum = safeAlbum,
                                        queryDurationMs = durationMs,
                                        result = rawResult,
                                    ),
                                )
                            }
                            if (parsedCandidates.isNotEmpty()) parsedCandidates
                            else fetchSingleResult(source, safeTitle, safeArtist, primaryArtist, normalizedArtist, durationMs, safeAlbum)
                        } else {
                            // Try searchLyrics first (multi-result) for extensions that support it.
                            val ext = com.music.bitchord.feature.lyrics.manager.LyricsExtensionManager.getExtension(source.id)
                            if (ext != null && ext.scriptFile.exists()) {
                                val searchJson = runCatching {
                                    com.music.bitchord.feature.lyrics.engine.LyricsExtensionExecutor.searchLyrics(
                                        extensionId = ext.id,
                                        scriptFile = ext.scriptFile,
                                        title = safeTitle,
                                        artist = primaryArtist.ifBlank { normalizedArtist },
                                        album = safeAlbum,
                                    )
                                }.getOrNull()

                                val parsed = if (!searchJson.isNullOrBlank()) {
                                    parseExtensionSearchResults(searchJson, source.label)
                                } else emptyList()

                                if (parsed.isNotEmpty()) {
                                    parsed
                                } else {
                                    // Fallback: use getLyrics for a single result
                                    fetchSingleResult(source, safeTitle, safeArtist, primaryArtist, normalizedArtist, durationMs, safeAlbum)
                                }
                            } else {
                                fetchSingleResult(source, safeTitle, safeArtist, primaryArtist, normalizedArtist, durationMs, safeAlbum)
                            }
                        }
                    }
                }
            }

            jobs.forEach { job ->
                val items = runCatching { job.await() }.getOrDefault(emptyList())
                results.addAll(items.take(MAX_SEARCH_RESULTS_PER_PROVIDER))
            }
        }

        results
    }

    /**
     * Fallback path: calls [LyricsRepository.fetch] (getLyrics) and wraps
     * the single result into a one-element list for the search results dialog.
     */
    private suspend fun fetchSingleResult(
        source: LyricsSource,
        title: String,
        artist: String,
        primaryArtist: String,
        normalizedArtist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricsSearchResultItem> {
        val lines = runCatching {
            LyricsRepository.fetch(
                source = source,
                videoId = "",
                title = title,
                artist = primaryArtist.ifBlank { normalizedArtist },
                durationMs = durationMs,
                album = album,
            )
        }.getOrNull()
        return if (!lines.isNullOrEmpty()) {
            val syncedLyrics = lines.toLrc().takeIf { it.length <= MAX_SEARCH_LYRICS_CHARS } ?: return emptyList()
            val plainLyrics = lines.joinToString("\n") { it.text }
                .takeIf { it.length <= MAX_SEARCH_LYRICS_CHARS }
            listOf(
                LyricsSearchResultItem(
                    id = "${source.id}_${System.currentTimeMillis()}",
                    title = title.take(MAX_SEARCH_METADATA_CHARS),
                    artist = artist.take(MAX_SEARCH_METADATA_CHARS),
                    album = album?.take(MAX_SEARCH_METADATA_CHARS),
                    durationSeconds = (durationMs / 1000).toInt(),
                    provider = source.label,
                    syncedLyrics = syncedLyrics,
                    plainLyrics = plainLyrics,
                )
            )
        } else emptyList()
    }

    /**
     * Parses the JSON array returned by [LyricsExtensionExecutor.searchLyrics].
     *
     * Each element is expected to have:
     * `{ id, title, artist, album?, durationSeconds?, provider?,
     *    syncedLyrics?, plainLyrics? }`
     */
    private fun parseExtensionSearchResults(
        jsonString: String,
        fallbackProvider: String,
    ): List<LyricsSearchResultItem> = runCatching {
        if (jsonString.length > MAX_EXTENSION_SEARCH_RESPONSE_CHARS) return emptyList()
        val array = json.parseToJsonElement(jsonString) as? JsonArray ?: return emptyList()
        array.take(MAX_SEARCH_RESULTS_PER_PROVIDER).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (synced == null && plain == null) return@mapNotNull null
            if (synced?.length?.let { it > MAX_SEARCH_LYRICS_CHARS } == true ||
                plain?.length?.let { it > MAX_SEARCH_LYRICS_CHARS } == true
            ) return@mapNotNull null
            if (synced?.contains("Wob gopini", ignoreCase = true) == true || plain?.contains("Wob gopini", ignoreCase = true) == true) {
                return@mapNotNull null
            }

            LyricsSearchResultItem(
                id = id.take(MAX_SEARCH_METADATA_CHARS),
                title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_SEARCH_METADATA_CHARS),
                artist = obj["artist"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_SEARCH_METADATA_CHARS),
                album = obj["album"]?.jsonPrimitive?.contentOrNull?.take(MAX_SEARCH_METADATA_CHARS),
                durationSeconds = obj["durationSeconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                provider = obj["provider"]?.jsonPrimitive?.contentOrNull?.take(MAX_SEARCH_METADATA_CHARS)
                    ?: fallbackProvider.take(MAX_SEARCH_METADATA_CHARS),
                syncedLyrics = synced,
                plainLyrics = plain,
            )
        }
    }.getOrDefault(emptyList())

    private fun searchLrcLib(title: String, artist: String, album: String?): List<LyricsSearchResultItem> {
        val primaryArtist = cleanArtistForSearch(artist)
        val normalizedArtist = if (artist.contains(";")) artist.replace(";", ",").trim() else artist.trim()
        val direct = executeLrcLibSearch(title, normalizedArtist, album)
        if (direct.isNotEmpty()) return direct

        if (primaryArtist.isNotBlank() && !primaryArtist.equals(normalizedArtist, ignoreCase = true)) {
            return executeLrcLibSearch(title, primaryArtist, album)
        }
        return emptyList()
    }

    private fun executeLrcLibSearch(title: String, artist: String, album: String?): List<LyricsSearchResultItem> {
        return runCatching {
            val builder = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            if (title.isNotBlank()) builder.addQueryParameter("track_name", title.trim())
            if (artist.isNotBlank()) builder.addQueryParameter("artist_name", artist.trim())
            if (!album.isNullOrBlank()) builder.addQueryParameter("album_name", album.trim())
            val url = builder.build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BitChord (https://github.com/bitchord)")
                .build()

            Http.lyricsClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = Http.readBoundedUtf8(response.body, 2 * 1024 * 1024) ?: return emptyList()
                val hits = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()

                hits.take(MAX_SEARCH_RESULTS_PER_PROVIDER).mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val track = obj["trackName"]?.jsonPrimitive?.contentOrNull ?: title
                    val art = obj["artistName"]?.jsonPrimitive?.contentOrNull ?: artist
                    val alb = obj["albumName"]?.jsonPrimitive?.contentOrNull
                    val duration = obj["duration"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                    val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                    if (synced == null && plain == null) return@mapNotNull null
                    if (synced?.length?.let { it > MAX_SEARCH_LYRICS_CHARS } == true ||
                        plain?.length?.let { it > MAX_SEARCH_LYRICS_CHARS } == true
                    ) return@mapNotNull null

                    LyricsSearchResultItem(
                        id = "lrclib_${id.take(MAX_SEARCH_METADATA_CHARS)}",
                        title = track.take(MAX_SEARCH_METADATA_CHARS),
                        artist = art.take(MAX_SEARCH_METADATA_CHARS),
                        album = alb?.take(MAX_SEARCH_METADATA_CHARS),
                        durationSeconds = duration,
                        provider = "LRCLIB",
                        syncedLyrics = synced,
                        plainLyrics = plain,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readEmbeddedLyrics(context: Context, song: Song): String {
        return runCatching {
            val pfd = openFileDescriptor(context, song, "r") ?: return ""
            pfd.use { desc ->
                val metadata = TagLib.getMetadata(desc.dup().detachFd(), false)
                val props = metadata?.propertyMap.orEmpty()
                props[WORD_LYRICS_FIELD]?.firstOrNull()
                    ?: props["LYRICS"]?.firstOrNull()
                    ?: props["UNSYNCEDLYRICS"]?.firstOrNull()
                    ?: ""
            }
        }.getOrDefault("")
    }

    private fun saveEmbeddedLyrics(context: Context, song: Song, lyricsText: String): Boolean {
        return runCatching {
            val pfd = openFileDescriptor(context, song, "rw") ?: return false
            pfd.use { desc ->
                val current = TagLib.getMetadata(desc.dup().detachFd(), false)?.propertyMap ?: hashMapOf()
                val map = hashMapOf<String, Array<String>>()
                map.putAll(current)
                if (lyricsText.isBlank()) {
                    map.remove("LYRICS")
                    map.remove("UNSYNCEDLYRICS")
                    map.remove(WORD_LYRICS_FIELD)
                } else {
                    val trimmedLyrics = lyricsText.trim()
                    val enhancedLines = EnhancedLrc.parse(trimmedLyrics)
                        .takeIf { lines -> lines.any { it.isWordSynced || it.background?.isWordSynced == true } }
                    if (enhancedLines != null) {
                        // Keep the interoperable line-LRC in the standard field and
                        // store CTC's per-character A2 timestamps in the app's own
                        // field, matching the download taggers and player reader.
                        map["LYRICS"] = arrayOf(enhancedLines.toLrc())
                        map[WORD_LYRICS_FIELD] = arrayOf(trimmedLyrics)
                    } else {
                        map["LYRICS"] = arrayOf(trimmedLyrics)
                        map.remove(WORD_LYRICS_FIELD)
                    }
                }
                val cleanedMap = map
                    .filterKeys { !it.contains(Regex("(?i)REPLAYGAIN_(TRACK|ALBUM)_[A-Z0-7_]+")) }
                    .filterValues { it.isNotEmpty() }
                    .mapValuesTo(hashMapOf()) { it.value }

                val ok = TagLib.savePropertyMap(desc.dup().detachFd(), cleanedMap)
                if (ok) {
                    val path = resolveFilePath(context, song)
                    if (!path.isNullOrBlank()) {
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                    }
                }
                ok
            }
        }.getOrDefault(false)
    }

    private fun readFileLyrics(context: Context, song: Song): String {
        val filePath = resolveFilePath(context, song) ?: return ""
        val audioFile = File(filePath)
        val lrcFile = File(audioFile.parentFile ?: return "", "${audioFile.nameWithoutExtension}.lrc")
        if (!lrcFile.isFile || lrcFile.length() !in 1..MAX_LOCAL_LYRICS_BYTES.toLong()) return ""
        return runCatching {
            lrcFile.inputStream().use { input ->
                val output = ByteArrayOutputStream(minOf(lrcFile.length().toInt(), 8 * 1024))
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_LOCAL_LYRICS_BYTES) return ""
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun saveFileLyrics(context: Context, song: Song, lyricsText: String): Boolean {
        val filePath = resolveFilePath(context, song) ?: return false
        val audioFile = File(filePath)
        val lrcFile = File(audioFile.parentFile ?: return false, "${audioFile.nameWithoutExtension}.lrc")
        return runCatching {
            if (lyricsText.isBlank()) {
                if (lrcFile.exists()) lrcFile.delete() else true
            } else {
                lrcFile.writeText(lyricsText.trim())
                true
            }
        }.getOrDefault(false)
    }

    private fun readDownloadedLyrics(context: Context, song: Song): String {
        return downloadedCache[song.videoId] ?: run {
            val sidecar = EmbeddedLyrics.sidecar(song.localPath)
            sidecar.orEmpty()
        }
    }

    private fun saveDownloadedLyrics(song: Song, lyricsText: String): Boolean {
        downloadedCache[song.videoId] = lyricsText
        return true
    }

    private fun getUriForSong(song: Song): Uri? {
        val raw = song.localUri ?: song.videoId
        return when {
            raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
            !song.localPath.isNullOrBlank() -> Uri.fromFile(File(song.localPath))
            else -> null
        }
    }

    private fun openFileDescriptor(context: Context, song: Song, mode: String): ParcelFileDescriptor? {
        val uri = getUriForSong(song)
        if (uri != null) {
            val pfd = runCatching {
                if (uri.scheme == "file") {
                    val file = File(uri.path ?: return null)
                    val pfdMode = if (mode.contains("w")) {
                        ParcelFileDescriptor.MODE_READ_WRITE
                    } else {
                        ParcelFileDescriptor.MODE_READ_ONLY
                    }
                    ParcelFileDescriptor.open(file, pfdMode)
                } else {
                    context.contentResolver.openFileDescriptor(uri, mode)
                }
            }.getOrNull()
            if (pfd != null) return pfd
        }

        val path = resolveFilePath(context, song)
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) {
                return runCatching {
                    val pfdMode = if (mode.contains("w")) {
                        ParcelFileDescriptor.MODE_READ_WRITE
                    } else {
                        ParcelFileDescriptor.MODE_READ_ONLY
                    }
                    ParcelFileDescriptor.open(file, pfdMode)
                }.getOrNull()
            }
        }
        return null
    }

    private fun resolveFilePath(context: Context, song: Song): String? {
        if (!song.localPath.isNullOrBlank() && File(song.localPath).exists()) {
            return song.localPath
        }
        val uri = getUriForSong(song) ?: return null
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        return runCatching {
            val proj = arrayOf(MediaStore.Audio.Media.DATA)
            context.contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }
}
