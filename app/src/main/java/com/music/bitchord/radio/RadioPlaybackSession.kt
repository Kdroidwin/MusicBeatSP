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

    /** Applies credentials only to Radiko's own HTTPS host family. */
    fun addHeadersFor(uri: Uri, dataSpec: DataSpec): DataSpec {
        val host = uri.host?.lowercase() ?: return dataSpec
        val allowedHost = host == "radiko.jp" || host.endsWith(".radiko.jp") ||
            host == "smartstream.ne.jp" || host.endsWith(".smartstream.ne.jp")
        if (!allowedHost || !uri.scheme.equals("https", ignoreCase = true)) return dataSpec
        val headers = authHeaders
        if (headers.isEmpty()) return dataSpec
        return dataSpec.buildUpon()
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
            .build()
    }
}
