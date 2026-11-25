package com.music.bitchord.data.webdav

import com.music.bitchord.data.model.Song
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * WebDAV music library configuration and mapping.
 *
 * A WebDAV server is treated as a remote folder of audio files: tracks are
 * listed with PROPFIND, streamed over plain HTTPS with Basic auth, and shown
 * in the same Songs / Artists / Albums view as on-device files.
 */
object WebDavConfig {

    const val BROWSE_ID = "local:webdav"

    val audioExtensions = setOf(
        "mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "wma", "aif", "aiff", "webm",
    )

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val candidate = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "https://$trimmed"
        val parsed = candidate.toHttpUrlOrNull() ?: return ""
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null
        ) return ""
        return parsed.newBuilder()
            .encodedPath(parsed.encodedPath.trimEnd('/').ifEmpty { "/" })
            .build()
            .toString()
            .trimEnd('/')
    }

    fun isValid(url: String): Boolean =
        normalizeUrl(url).toHttpUrlOrNull()?.isHttps == true

    fun isConfigured(url: String): Boolean =
        normalizeUrl(url).toHttpUrlOrNull()?.let(WebDavAuth::isAllowedUrl) == true

    fun basicAuthHeader(username: String, password: String): String? {
        if (username.isBlank() && password.isBlank()) return null
        val credentials = "$username:$password"
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(credentials.toByteArray(Charsets.UTF_8))
        return "Basic $encoded"
    }

    fun hostOf(url: String): String? = normalizeUrl(url).toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)

    /** True only for one of pCloud's published regional WebDAV hosts. */
    fun isPCloudEndpoint(url: String): Boolean {
        val parsed = normalizeUrl(url).toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.port == 443 &&
            PCloudRegion.entries.any { hostOf(it.webDavUrl) == parsed.host.lowercase(Locale.ROOT) }
    }

    /** Distinguishes pCloud rows from other WebDAV libraries in search and source filters. */
    fun isPCloudSong(song: Song): Boolean {
        if (!isWebDavId(song.videoId)) return false
        val parsed = song.videoId.removePrefix("webdav:").toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.port == 443 &&
            PCloudRegion.entries.any { hostOf(it.webDavUrl) == parsed.host.lowercase(Locale.ROOT) }
    }

    fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in audioExtensions
    }

    /**
     * Stable track id for a remote file. Prefixed so it never collides with a
     * YouTube id, a MediaStore content URI, or a module source key.
     */
    fun idFor(fileUrl: String): String = "webdav:$fileUrl"

    fun isWebDavId(videoId: String): Boolean = videoId.startsWith("webdav:")

    fun fileUrlOf(videoId: String): String? = videoId.removePrefix("webdav:").takeIf {
        isWebDavId(videoId) && it.toHttpUrlOrNull()?.let(WebDavAuth::isAllowedUrl) == true
    }

    /**
     * A row for a remote file. Title/artist come from the filename —
     * "Artist - Title.ext" splits, anything else is a title by an unknown
     * artist — and the parent folder names the album when there is one.
     */
    fun songFor(fileUrl: String, albumName: String? = null): Song {
        val decoded = runCatching {
            java.net.URLDecoder.decode(fileUrl.substringAfterLast('/'), "UTF-8")
        }.getOrDefault(fileUrl.substringAfterLast('/'))
        return com.music.bitchord.data.remote.RemoteSong.build(
            videoId = idFor(fileUrl),
            streamUrl = fileUrl,
            fileName = decoded,
            albumName = albumName,
            source = "WebDAV",
            browseId = BROWSE_ID,
        )
    }
}

/** Official pCloud WebDAV endpoints; region is selected by the account's data center. */
enum class PCloudRegion(val webDavUrl: String) {
    US("https://webdav.pcloud.com"),
    EU("https://ewebdav.pcloud.com"),
}

/** Publishes the saved server's credentials to the shared HTTP client. */
fun WebDavAuth.update(url: String, username: String, password: String) =
    update(WebDavConfig.hostOf(url), WebDavConfig.basicAuthHeader(username, password))
