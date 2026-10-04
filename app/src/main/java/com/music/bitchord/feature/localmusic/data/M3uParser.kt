package com.music.bitchord.feature.localmusic.data

import java.io.File
import java.nio.file.Paths

/** One media reference from an M3U playlist, with optional EXTINF tags. */
data class M3uEntry(
    val location: String,
    val durationSeconds: Long? = null,
    val title: String? = null,
    val artist: String? = null,
)

data class ParsedM3uPlaylist(
    val suggestedName: String,
    val entries: List<M3uEntry>,
)

/** Parsing and path normalization kept free of Android APIs so both formats can be unit tested. */
object M3uParser {
    fun parse(text: String, fileName: String): ParsedM3uPlaylist {
        val entries = mutableListOf<M3uEntry>()
        var pendingInfo: ExtInfo? = null

        text.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingInfo = parseExtInfo(line.substringAfter(':'))
                return@forEach
            }
            if (line.startsWith('#')) return@forEach

            val info = pendingInfo
            entries += M3uEntry(
                location = line.trim('"'),
                durationSeconds = info?.durationSeconds,
                title = info?.title?.takeIf(String::isNotBlank),
                artist = info?.artist?.takeIf(String::isNotBlank),
            )
            pendingInfo = null
        }

        return ParsedM3uPlaylist(
            suggestedName = fileName.substringBeforeLast('.', fileName).trim().ifBlank { "Imported Playlist" },
            entries = entries,
        )
    }

    /** Resolves a relative M3U entry against the directory containing the playlist. */
    fun resolveRelativePath(baseDirectory: String?, location: String): String? {
        val trimmed = location.trim().replace('\\', '/')
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
        if (trimmed.startsWith('/')) return normalizePath(trimmed)
        val base = baseDirectory?.takeIf(String::isNotBlank) ?: return null
        return runCatching { File(base, trimmed).canonicalPath }
            .getOrElse { runCatching { Paths.get(base, trimmed).normalize().toString() }.getOrNull() }
    }

    fun resolveAbsolutePath(path: String): String = normalizePath(path.trim().replace('\\', '/'))

    private fun normalizePath(path: String): String =
        runCatching { File(path).canonicalPath }
            .getOrElse { runCatching { Paths.get(path).normalize().toString() }.getOrDefault(path) }

    private data class ExtInfo(val durationSeconds: Long?, val title: String?, val artist: String?)

    private fun parseExtInfo(raw: String): ExtInfo {
        val comma = raw.indexOf(',')
        if (comma < 0) return ExtInfo(raw.trim().toLongOrNull()?.takeIf { it >= 0L }, null, null)
        val duration = raw.substring(0, comma).trim().toLongOrNull()?.takeIf { it >= 0L }
        val display = raw.substring(comma + 1).trim()
        val separator = display.indexOf(" - ")
        val artist = display.takeIf { separator > 0 }?.substring(0, separator)?.trim()
        val title = if (separator > 0) display.substring(separator + 3).trim() else display
        return ExtInfo(duration, title, artist)
    }
}
