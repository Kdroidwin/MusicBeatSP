package com.music.bitchord.data.sources

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.serializer
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val remoteServerJson = Json { ignoreUnknownKeys = true }

/** Base for the two server APIs ported from Namida's music-web-server layer. */
abstract class RemoteMusicServerSource(
    final override val config: SourceConfig,
    private val credentialProvider: () -> RemoteServerCredentials?,
) : MusicSource, SourceRegistry.ConfigBacked {

    final override val configId: String get() = config.id
    final override val kind: SourceKind get() = config.kind
    final override val displayName: String
        get() = config.label.ifBlank { baseUrl?.host ?: config.kind.label }

    protected val baseUrl: HttpUrl? = config.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?.takeIf { it.scheme == "https" || (it.scheme == "http" && config.allowInsecureHttp) }
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }

    // These API responses may contain or be sent alongside credentials. Do
    // not follow redirects: a server must not be able to forward a token to a
    // different origin through an HTTP redirect.
    private val apiClient by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    protected val formatsByTrack = ConcurrentHashMap<String, StreamFormat>()
    private val mediaSourcesByTrack = ConcurrentHashMap<String, JellyfinMediaSource>()

    protected fun credentials(): RemoteServerCredentials? = credentialProvider()

    protected fun endpoint(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl? {
        val base = baseUrl ?: return null
        val prefix = base.encodedPath.trimEnd('/')
        return base.newBuilder()
            .encodedPath("${prefix}/")
            .apply {
                segments.flatMap { it.split('/').filter(String::isNotEmpty) }
                    .forEach(::addPathSegment)
            }
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
    }

    protected suspend fun requestBytes(
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        maxBytes: Int = MAX_RESPONSE_BYTES,
        operation: String = "server request",
    ): ByteArray {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json, image/*, */*")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .apply {
                if (jsonBody != null) {
                    post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                } else {
                    get()
                }
            }
            .build()

        val call = apiClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    continuation.resumeWith(Result.failure(error))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw RemoteServerHttpException(it.code, operation)
                            val body = it.body
                            val contentLength = body.contentLength()
                            if (contentLength > maxBytes) throw IOException("Server response exceeded the size limit")
                            body.byteStream().use { input ->
                                val initialCapacity = minOf(
                                    maxBytes,
                                    contentLength.takeIf { length -> length > 0 }?.toInt() ?: 8_192,
                                )
                                val output = ByteArrayOutputStream(initialCapacity)
                                val buffer = ByteArray(8_192)
                                var total = 0
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > maxBytes) throw IOException("Server response exceeded the size limit")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        continuation.resumeWith(Result.success(bytes))
                    } catch (error: Exception) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            })
        }
    }

    protected suspend fun requestJson(
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
        operation: String = "server request",
    ): JsonObject = remoteServerJson.parseToJsonElement(
        requestBytes(url, headers, jsonBody, operation = operation).decodeToString(),
    ).jsonObject

    protected fun sourceArtworkUri(imageId: String): String = SourceRegistry.remoteServerArtworkUri(configId, imageId)

    protected fun healthFrom(error: Throwable): SourceHealth = when (error) {
        is RemoteServerHttpException -> if (error.statusCode == 401 || error.statusCode == 403) {
            SourceHealth.Rejected("HTTP ${error.statusCode}: authentication was rejected during ${error.operation}")
        } else {
            SourceHealth.Unreachable("HTTP ${error.statusCode} during ${error.operation}")
        }
        is SecurityException -> SourceHealth.Rejected("Server authentication was rejected")
        else -> SourceHealth.Unreachable(error.message?.let(::safeServerMessage) ?: "Server is unreachable")
    }

    protected fun requestLimit(limit: Int) = limit.coerceIn(1, MAX_SEARCH_RESULTS)

    protected fun rememberFormat(trackId: String, format: StreamFormat) {
        formatsByTrack[trackId] = format
        while (formatsByTrack.size > MAX_CACHED_TRACK_FORMATS) {
            val oldest = formatsByTrack.keys.firstOrNull() ?: break
            formatsByTrack.remove(oldest)
        }
    }

    protected fun rememberMediaSource(trackId: String, source: JellyfinMediaSource) {
        mediaSourcesByTrack[trackId] = source
        while (mediaSourcesByTrack.size > MAX_CACHED_TRACK_FORMATS) {
            val oldest = mediaSourcesByTrack.keys.firstOrNull() ?: break
            mediaSourcesByTrack.remove(oldest)
        }
    }

    protected fun mediaSource(trackId: String): JellyfinMediaSource? = mediaSourcesByTrack[trackId]

    protected fun durationText(seconds: Int?): String? {
        val value = seconds?.takeIf { it >= 0 } ?: return null
        val hours = value / 3_600
        val minutes = (value % 3_600) / 60
        val remaining = value % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, remaining)
        else "%d:%02d".format(Locale.ROOT, minutes, remaining)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private const val MAX_SEARCH_RESULTS = 100
        private const val MAX_CACHED_TRACK_FORMATS = 2_048
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        protected const val IMAGE_MAX_BYTES = MAX_IMAGE_BYTES
    }
}

data class JellyfinMediaSource(
    val id: String?,
    val container: String?,
)

/** Jellyfin client using its supported MediaBrowser auth header and direct-play stream route. */
class JellyfinMusicSource(
    config: SourceConfig,
    credentialProvider: () -> RemoteServerCredentials?,
) : RemoteMusicServerSource(config, credentialProvider) {

    private data class Auth(val userId: String, val accessToken: String, val credentials: RemoteServerCredentials)
    private val authMutex = Mutex()
    @Volatile private var cachedAuth: Auth? = null

    private suspend fun authenticate(): Auth = authMutex.withLock {
        val userCredentials = credentials() ?: throw SecurityException("Secure server credentials are unavailable")
        if (userCredentials.username.isBlank() || userCredentials.password.isBlank()) {
            throw SecurityException("Username and password are required")
        }
        cachedAuth?.takeIf { it.credentials == userCredentials }?.let { return@withLock it }
        val url = endpoint("Users", "AuthenticateByName") ?: throw IOException("Invalid server URL")
        val body = """{"Username":${remoteServerJson.encodeToString(String.serializer(), userCredentials.username)},"Pw":${remoteServerJson.encodeToString(String.serializer(), userCredentials.password)}}"""
        val response = requestJson(
            url,
            headers = mapOf("X-Emby-Authorization" to mediaBrowserHeader()),
            jsonBody = body,
            operation = "Jellyfin sign-in",
        )
        val token = response.string("AccessToken")?.takeIf(String::isNotBlank)
            ?: throw SecurityException("Jellyfin did not return an access token")
        val userId = response["User"]?.objectOrNull()?.string("Id")?.takeIf(String::isNotBlank)
            ?: throw SecurityException("Jellyfin did not return a user id")
        Auth(userId, token, userCredentials).also { cachedAuth = it }
    }

    private fun mediaBrowserHeader(): String =
        "MediaBrowser Client=\"MusicBeatSP\", Device=\"Android\", " +
            "DeviceId=\"${BuildConfig.APPLICATION_ID}\", Version=\"${BuildConfig.VERSION_NAME}\""

    private fun authHeaders(auth: Auth) = mapOf(
        "X-Emby-Authorization" to mediaBrowserHeader(),
        "X-Emby-Token" to auth.accessToken,
    )

    override suspend fun health(): SourceHealth = try {
        val auth = authenticate()
        val url = endpoint("System", "Info") ?: throw IOException("Invalid server URL")
        requestJson(url, authHeaders(auth), operation = "Jellyfin connection check")
        SourceHealth.Ok("Jellyfin connected")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        healthFrom(error)
    }

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val auth = authenticate()
        val url = endpoint("Users", auth.userId, "Items", query = mapOf(
            "UserId" to auth.userId,
            "IncludeItemTypes" to "Audio",
            "Recursive" to "true",
            "SearchTerm" to term,
            "StartIndex" to "0",
            "Limit" to requestLimit(limit).toString(),
            "Fields" to "MediaStreams,MediaSources,ProviderIds",
        )) ?: throw IOException("Invalid server URL")
        val root = requestJson(url, authHeaders(auth), operation = "Jellyfin search")
        val items = root.array("Items") ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element.objectOrNull() ?: return@mapNotNull null
            val id = item.string("Id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item.string("Name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val audioStream = item.audioStream()
            val codec = audioStream?.string("Codec") ?: item.string("Container")
            val format = StreamFormat(
                codec = codec?.lowercase(Locale.ROOT),
                kbps = audioStream?.int("BitRate")?.div(1_000),
                sampleRateHz = audioStream?.int("SampleRate"),
                bitDepth = audioStream?.int("BitDepth"),
            )
            rememberFormat(id, format)
            val mediaSource = item.array("MediaSources")
                ?.firstOrNull()
                ?.objectOrNull()
            rememberMediaSource(
                id,
                JellyfinMediaSource(
                    id = mediaSource?.string("Id")?.takeIf(String::isNotBlank),
                    container = (mediaSource?.string("Container") ?: item.string("Container"))
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) },
                ),
            )
            val artists = item.stringArray("Artists")
            val artist = artists.firstOrNull()
                ?: item.string("AlbumArtist")
                ?: item.array("ArtistItems")?.firstOrNull()?.objectOrNull()?.string("Name")
                ?: "Unknown Artist"
            val album = item.string("Album")
            val seconds = item.long("RunTimeTicks")?.let { (it / TICKS_PER_SECOND).toInt().coerceAtLeast(0) }
            val imageId = item.string("AlbumId")?.takeIf(String::isNotBlank)
                ?: id.takeIf { item.objectOrNull("ImageTags")?.string("Primary") != null }
            Song(
                videoId = SourceRegistry.trackKey(configId, id),
                title = title,
                artist = artist,
                thumbnailUrl = imageId?.let(::sourceArtworkUri),
                durationText = durationText(seconds),
                albumName = album,
                sourceQuality = qualityLabel(format),
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val auth = authenticate()
        val mediaSource = mediaSource(trackId)
        val query = buildMap {
            put("UserId", auth.userId)
            // Jellyfin's audio stream endpoint expects the selected media source
            // when it is known. The item id is also the media-source id for
            // ordinary single-source library items.
            put("MediaSourceId", mediaSource?.id ?: trackId)
            put("static", "true")
            mediaSource?.container?.let { put("Container", it) }
        }
        val url = endpoint("Audio", trackId, "stream", query = query)
            ?: return null
        val format = formatsByTrack[trackId] ?: StreamFormat()
        return SourceStream(
            url = url.toString(),
            format = format,
            headers = authHeaders(auth),
            durationSec = null,
        )
    }

    override suspend fun artwork(imageId: String): ByteArray? = try {
        val auth = authenticate()
        val url = endpoint("Items", imageId, "Images", "Primary") ?: return null
        requestBytes(
            url,
            authHeaders(auth),
            maxBytes = IMAGE_MAX_BYTES,
            operation = "Jellyfin artwork",
        ).takeIf { it.isNotEmpty() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun qualityLabel(format: StreamFormat): String? = when (format.isLossless) {
        true -> "LOSSLESS"
        false -> "HIGH"
        null -> null
    }

    companion object {
        private const val TICKS_PER_SECOND = 10_000_000L
    }
}

/** Navidrome and compatible servers use the OpenSubsonic token authentication scheme. */
class NavidromeMusicSource(
    config: SourceConfig,
    credentialProvider: () -> RemoteServerCredentials?,
) : RemoteMusicServerSource(config, credentialProvider) {

    private enum class AuthMechanism { TOKEN, API_KEY, LEGACY_PASSWORD }

    private data class Auth(
        val username: String,
        val salt: String,
        val token: String,
        val credentials: RemoteServerCredentials,
        val mechanism: AuthMechanism = AuthMechanism.TOKEN,
        val verified: Boolean = false,
    )

    private val authMutex = Mutex()
    @Volatile private var cachedAuth: Auth? = null

    private suspend fun authenticate(): Auth = authMutex.withLock {
        val userCredentials = credentials() ?: throw SecurityException("Secure server credentials are unavailable")
        if (userCredentials.username.isBlank()) throw SecurityException("Username is required")
        cachedAuth?.takeIf { it.credentials == userCredentials }?.let { return@withLock it }
        val saltBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val salt = Base64.getEncoder().withoutPadding().encodeToString(saltBytes)
        val token = MessageDigest.getInstance("MD5")
            .digest((userCredentials.password + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        Auth(userCredentials.username, salt, token, userCredentials).also { cachedAuth = it }
    }

    private fun subsonicUrl(
        path: String,
        auth: Auth,
        parameters: Map<String, String> = emptyMap(),
        json: Boolean = true,
    ): HttpUrl? = endpoint(path, query = buildMap {
        put("v", SUBSONIC_VERSION)
        put("c", CLIENT_ID)
        if (json) put("f", "json")
        when (auth.mechanism) {
            AuthMechanism.TOKEN -> {
                put("u", auth.username)
                put("s", auth.salt)
                put("t", auth.token)
            }
            // OpenSubsonic requires apiKey authentication to omit u, p, t and s.
            AuthMechanism.API_KEY -> put("apiKey", auth.credentials.password)
            AuthMechanism.LEGACY_PASSWORD -> {
                put("u", auth.username)
                put("p", encodedPassword(auth.credentials.password))
            }
        }
        putAll(parameters)
    })

    /**
     * Every JSON endpoint uses the same conservative fallback sequence. Most
     * servers continue to use the salted token. Nextcloud Music rejects that
     * mechanism with code 41 and expects its generated API key instead. A
     * server that explicitly says API-key auth is unsupported can still use
     * the Subsonic-compatible encoded-password form.
     */
    private suspend fun requestSubsonic(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        json: Boolean = true,
        operation: String,
    ): JsonObject {
        var auth = authenticate()
        repeat(MAX_AUTH_ATTEMPTS) {
            requireSecureFallback(auth)
            val url = subsonicUrl(path, auth, parameters, json)
                ?: throw IOException("Invalid server URL")
            val response = requestJson(url, operation = operation).subsonicResponse()
            try {
                ensureSubsonicOk(response)
                markVerified(auth)
                return response
            } catch (error: OpenSubsonicApiException) {
                val next = nextMechanism(auth.mechanism, error.protocolCode) ?: throw error
                auth = updateMechanism(auth, next)
            }
        }
        throw IOException("OpenSubsonic authentication fallback failed")
    }

    private fun requireSecureFallback(auth: Auth) {
        if (auth.mechanism != AuthMechanism.TOKEN && baseUrl?.scheme != "https") {
            throw SecurityException("OpenSubsonic API-key and password authentication require HTTPS")
        }
    }

    private suspend fun updateMechanism(previous: Auth, next: AuthMechanism): Auth = authMutex.withLock {
        val current = cachedAuth?.takeIf { it.credentials == previous.credentials } ?: previous
        if (current.mechanism != previous.mechanism) return@withLock current
        current.copy(mechanism = next, verified = false).also { cachedAuth = it }
    }

    private suspend fun markVerified(auth: Auth): Auth = authMutex.withLock {
        val current = cachedAuth?.takeIf { it.credentials == auth.credentials } ?: auth
        if (current.mechanism != auth.mechanism) return@withLock current
        current.copy(verified = true).also { cachedAuth = it }
    }

    /**
     * Queued Subsonic items can be restored after process death, when there is
     * no cached auth mode. Validate once before returning stream/art URLs so
     * code 41 can switch the cached mode before the player requests the bytes.
     */
    private suspend fun verifiedAuth(): Auth {
        val auth = authenticate()
        if (!auth.verified) {
            requestSubsonic("rest/ping.view", operation = "OpenSubsonic authentication check")
        }
        return authenticate()
    }

    private fun nextMechanism(current: AuthMechanism, code: Int?): AuthMechanism? = when {
        current == AuthMechanism.TOKEN && code == TOKEN_AUTH_UNSUPPORTED -> AuthMechanism.API_KEY
        current == AuthMechanism.API_KEY && code in API_KEY_AUTH_UNAVAILABLE_CODES -> AuthMechanism.LEGACY_PASSWORD
        else -> null
    }

    override suspend fun health(): SourceHealth = try {
        val response = requestSubsonic("rest/ping.view", operation = "OpenSubsonic connection check")
        SourceHealth.Ok(response.string("version")?.let { "OpenSubsonic $it" } ?: "OpenSubsonic connected")
    } catch (error: CancellationException) {
        throw error
    } catch (error: OpenSubsonicApiException) {
        if (error.protocolCode in AUTH_REJECTION_CODES) {
            SourceHealth.Rejected("OpenSubsonic authentication was rejected")
        } else {
            SourceHealth.Unreachable("OpenSubsonic request failed")
        }
    } catch (error: Exception) {
        healthFrom(error)
    }

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val response = requestSubsonic("rest/search3.view", mapOf(
            "query" to term,
            "songCount" to requestLimit(limit).toString(),
            "albumCount" to "0",
            "artistCount" to "0",
        ), operation = "OpenSubsonic search")
        val songs = response.objectOrNull("searchResult3")?.arrayOrSingle("song") ?: return emptyList()
        return songs.mapNotNull { element ->
            val item = element.objectOrNull() ?: return@mapNotNull null
            val id = item.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = item.string("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val seconds = item.int("duration")
            val codec = item.string("suffix")?.lowercase(Locale.ROOT)
            val format = StreamFormat(
                codec = codec,
                kbps = item.int("bitRate"),
                sampleRateHz = item.int("samplingRate"),
                bitDepth = item.int("bitDepth"),
            )
            rememberFormat(id, format)
            val coverId = item.string("coverArt")?.takeIf(String::isNotBlank) ?: item.string("albumId")
            Song(
                videoId = SourceRegistry.trackKey(configId, id),
                title = title,
                artist = item.string("artist")?.takeIf(String::isNotBlank) ?: "Unknown Artist",
                thumbnailUrl = coverId?.let(::sourceArtworkUri),
                durationText = durationText(seconds),
                albumName = item.string("album"),
                sourceQuality = when (format.isLossless) {
                    true -> "LOSSLESS"
                    false -> "HIGH"
                    null -> null
                },
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val auth = verifiedAuth()
        val maxBitRate = (request as? StreamRequest.Capped)?.maxKbps
        val url = subsonicUrl(
            "rest/stream.view",
            auth,
            buildMap { if (maxBitRate != null) put("maxBitRate", maxBitRate.toString()); put("id", trackId) },
            json = false,
        ) ?: return null
        return SourceStream(
            url = url.toString(),
            format = formatsByTrack[trackId] ?: StreamFormat(),
            durationSec = null,
        )
    }

    override suspend fun artwork(imageId: String): ByteArray? = try {
        val auth = verifiedAuth()
        val url = subsonicUrl("rest/getCoverArt.view", auth, mapOf("id" to imageId, "size" to "1200"), json = false)
            ?: return null
        requireSecureFallback(auth)
        requestBytes(url, maxBytes = IMAGE_MAX_BYTES, operation = "OpenSubsonic artwork").takeIf { it.isNotEmpty() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun ensureSubsonicOk(response: JsonObject) {
        if (response.string("status") == "ok") return
        val error = response.objectOrNull("error")
        throw OpenSubsonicApiException(error?.int("code"))
    }

    companion object {
        private const val SUBSONIC_VERSION = "1.16.1"
        private const val CLIENT_ID = "MusicBeatSP"
        private const val TOKEN_AUTH_UNSUPPORTED = 41
        private const val MAX_AUTH_ATTEMPTS = 3
        private val API_KEY_AUTH_UNAVAILABLE_CODES = setOf(10, 40, 42)
        private val AUTH_REJECTION_CODES = setOf(40, 41, 43)
    }
}

/** Subsonic's legacy `p=enc:` form is reversible, so only use it over HTTPS. */
private fun encodedPassword(password: String): String = buildString {
    append("enc:")
    password.toByteArray(Charsets.UTF_8).forEach { byte ->
        append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
    }
}

internal class RemoteServerHttpException(val statusCode: Int, val operation: String) :
    IOException("HTTP $statusCode during $operation")

internal class OpenSubsonicApiException(val protocolCode: Int?) :
    IOException("OpenSubsonic error ${protocolCode ?: "unknown"}")

private fun safeServerMessage(message: String): String =
    message.filterNot(Char::isISOControl).trim().take(160).ifBlank { "Server is unreachable" }

private fun JsonObject.subsonicResponse(): JsonObject =
    objectOrNull("subsonic-response") ?: objectOrNull("subsonicResponse") ?: this

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.array(key: String): JsonArray? = this[key]?.let { element ->
    runCatching { element.jsonArray }.getOrNull()
}

private fun JsonObject.arrayOrSingle(key: String): List<JsonElement> = this[key]?.let { element ->
    runCatching { element.jsonArray.toList() }.getOrElse {
        if (element.objectOrNull() != null) listOf(element) else emptyList()
    }
}.orEmpty()

private fun JsonObject.stringArray(key: String): List<String> = array(key).orEmpty().mapNotNull { element ->
    element.jsonPrimitive.contentOrNull
}

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key]?.objectOrNull()

private fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonObject.audioStream(): JsonObject? =
    array("MediaStreams")?.firstOrNull { item -> item.objectOrNull()?.string("Type") == "Audio" }?.objectOrNull()
