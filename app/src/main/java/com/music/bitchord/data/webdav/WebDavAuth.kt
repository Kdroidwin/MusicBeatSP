package com.music.bitchord.data.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * In-memory credentials for [com.music.bitchord.data.Http]'s interceptor.
 *
 * Held outside SharedPreferences so the network layer never touches prefs:
 * [com.music.bitchord.data.settings.AppSettings] publishes here on init and
 * on every WebDAV edit.
 */
object WebDavAuth {
    @Volatile var host: String? = null
    @Volatile var authHeader: String? = null
    @Volatile private var configuredRoot: HttpUrl? = null
    @Volatile private var allowCleartext = false

    /** [host] and [header] as WebDavConfig derives them from the saved server. */
    fun update(host: String?, header: String?) {
        this.host = host
        authHeader = header
        configuredRoot = host?.let { "https://$it/".toHttpUrlOrNull() }
        allowCleartext = false
    }

    /** Publish a saved server and scope its credentials to that server root. */
    fun update(url: String, username: String, password: String, allowInsecureHttp: Boolean = false) {
        val normalized = WebDavConfig.normalizeUrl(url)
        val parsed = normalized.toHttpUrlOrNull()
        val root = parsed?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
                (it.isHttps || (it.scheme == "http" && allowInsecureHttp))
        }
        configuredRoot = root
        host = root?.host
        authHeader = if (username.isBlank() && password.isBlank()) null else WebDavConfig.basicAuthHeader(username, password)
        allowCleartext = allowInsecureHttp && root?.scheme == "http"
    }

    /** A URL is in scope only when it stays on the configured origin and path. */
    fun isAllowedUrl(url: HttpUrl): Boolean {
        val root = configuredRoot ?: return false
        // URL user-info can make a seemingly same-host href carry a second,
        // server-controlled credential. Fragments are never part of a DAV
        // request and can hide misleading path text in diagnostics/UI.
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
        return sameOrigin(root, url) && withinRoot(root, url) &&
            (url.isHttps || (allowCleartext && url.scheme == "http"))
    }

    fun isCleartextAllowed(url: HttpUrl): Boolean =
        allowCleartext && url.scheme == "http" && isAllowedUrl(url)

    fun authHeaderFor(url: HttpUrl): String? = authHeader?.takeIf { isAllowedUrl(url) }

    fun authHeaderFor(rawUrl: String): String? = rawUrl.toHttpUrlOrNull()?.let(::authHeaderFor)

    fun isAllowedOrigin(url: HttpUrl): Boolean = configuredRoot?.let { sameOrigin(it, url) } == true

    fun shouldAuthorize(requestHost: String): Boolean {
        val expected = host ?: return false
        val header = authHeader ?: return false
        if (header.isBlank()) return false
        return requestHost.equals(expected, ignoreCase = true)
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

    private fun withinRoot(root: HttpUrl, candidate: HttpUrl): Boolean {
        val prefix = safePathSegments(root) ?: return false
        val path = safePathSegments(candidate) ?: return false
        return path.size >= prefix.size && path.take(prefix.size) == prefix
    }

    /**
     * Compare decoded path segments so a server-provided `%2F` or `%5C` cannot
     * smuggle a separator through the configured-root prefix check. OkHttp
     * canonicalizes dot segments, and rejecting any remaining traversal form
     * keeps the auth scope independent of server-specific path decoding.
     */
    private fun safePathSegments(url: HttpUrl): List<String>? {
        val segments = url.pathSegments.dropLastWhile(String::isEmpty)
        if (segments.any { segment ->
                segment.isEmpty() || segment == "." || segment == ".." ||
                    '/' in segment || '\\' in segment || '\u0000' in segment
            }
        ) return null
        return segments
    }
}
