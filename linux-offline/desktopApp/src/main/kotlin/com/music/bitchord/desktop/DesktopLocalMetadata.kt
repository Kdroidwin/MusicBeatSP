package com.music.bitchord.desktop

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Tags read from a local audio container. No network access is used. */
internal data class DesktopLocalTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/** Reads the container header only; it does not decode audio or load attached artwork. */
internal object DesktopLocalMetadata {
    private data class CacheKey(val path: String, val bytes: Long, val modified: Long)
    private val cache = ConcurrentHashMap<CacheKey, DesktopLocalTags>()

    fun read(path: Path, bytes: Long, modified: Long): DesktopLocalTags {
        val key = CacheKey(path.toAbsolutePath().normalize().toString(), bytes, modified)
        return cache.computeIfAbsent(key) { extract(path) }
    }

    private fun extract(path: Path): DesktopLocalTags = runCatching {
        val context = AVFormatContext(null)
        if (avformat_open_input(context, path.toString(), null, null as AVDictionary?) < 0) {
            return DesktopLocalTags()
        }
        try {
            val metadata = context.metadata()
            DesktopLocalTags(
                title = first(metadata, "title", "TIT2"),
                artist = first(metadata, "artist", "TPE1", "album_artist", "albumartist"),
                album = first(metadata, "album", "TALB"),
            )
        } finally {
            avformat_close_input(context)
        }
    }.getOrDefault(DesktopLocalTags())

    private fun first(dictionary: AVDictionary?, vararg keys: String): String? = keys
        .asSequence()
        .mapNotNull { key -> av_dict_get(dictionary, key, null, 0)?.value()?.string }
        .map(String::trim)
        .firstOrNull(String::isNotEmpty)
}
