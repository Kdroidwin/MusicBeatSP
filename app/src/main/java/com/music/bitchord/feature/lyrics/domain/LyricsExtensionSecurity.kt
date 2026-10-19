package com.music.bitchord.feature.lyrics.domain

import java.net.URI

/** Input validation for data received from the optional lyrics-extension registry. */
internal object LyricsExtensionSecurity {
    fun isSafeExtensionId(value: String): Boolean =
        value.length in 1..64 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

    fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)
}
