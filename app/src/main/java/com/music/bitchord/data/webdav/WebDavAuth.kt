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
        val prefix = root.encodedPath.trimEnd('/')
        val path = candidate.encodedPath
        return prefix.isEmpty() || prefix == "/" || path == prefix || path.startsWith("$prefix/")
    }
}
