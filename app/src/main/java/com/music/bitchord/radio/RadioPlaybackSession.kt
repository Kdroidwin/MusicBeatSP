package com.music.bitchord.radio

import android.net.Uri
import androidx.media3.datasource.DataSpec

/** Short-lived Radiko request headers; never written to preferences or queue backups. */
object RadioPlaybackSession {
    @Volatile
    private var authHeaders: Map<String, String> = emptyMap()

    fun setHeaders(headers: Map<String, String>) {
        authHeaders = headers.toMap()
    }

    fun clear() {
        authHeaders = emptyMap()
    }

    /** Live Radiko playlists and segments must bypass the app's persistent audio cache. */
    fun isRadikoUri(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val allowedHost = host == "radiko.jp" || host.endsWith(".radiko.jp") ||
            host == "smartstream.ne.jp" || host.endsWith(".smartstream.ne.jp")
        return allowedHost && uri.scheme.equals("https", ignoreCase = true)
    }

    /** Applies credentials only to Radiko's own HTTPS host family. */
    fun addHeadersFor(uri: Uri, dataSpec: DataSpec): DataSpec {
        if (!isRadikoUri(uri)) return dataSpec
        val headers = authHeaders
        if (headers.isEmpty()) return dataSpec
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
            .build()
    }
}
