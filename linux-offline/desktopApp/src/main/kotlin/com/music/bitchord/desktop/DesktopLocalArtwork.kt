package com.music.bitchord.desktop

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

/** Artwork lookup for local files. It never opens a network connection. */
internal object DesktopLocalArtwork {
    private const val SCHEME = "local-artwork"
    private const val ATTACHED_PICTURE = 0x0400
    private const val MAX_ARTWORK_BYTES = 24L * 1024 * 1024
    private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private data class DirectoryArtworkIndex(
        val directoryModifiedMillis: Long,
        val byTrackName: Map<String, Path>,
        val sharedCover: Path?,
    )
    private val directoryArt = ConcurrentHashMap<Path, DirectoryArtworkIndex>()

    /** A sidecar image is preferred; embedded art is extracted only when a view asks for it. */
    fun reference(audio: Path, fileSize: Long, modifiedMillis: Long): String {
        val parent = audio.parent
        if (parent != null) {
            val cover = directoryIndex(parent)
                ?.let { index -> index.byTrackName[audio.fileName.toString().substringBeforeLast('.', audio.fileName.toString()).lowercase(Locale.ROOT)] ?: index.sharedCover }
            if (cover != null) reference("sidecar", cover)?.let { return it }
        }
        return reference("embedded", audio, fileSize, modifiedMillis)
    }

    /** Called by the artwork loader on its IO dispatcher. Successful extraction is cached on disk. */
    fun load(reference: String): ByteArray? = runCatching {
        val uri = java.net.URI(reference)
        if (uri.scheme != SCHEME || uri.host !in setOf("embedded", "sidecar")) return null
        val pathText = String(Base64.getUrlDecoder().decode(uri.rawPath.removePrefix("/")), UTF_8)
        val source = Path.of(pathText)
        if (!Files.isRegularFile(source)) return null
        val expectedSize = queryValue(uri.rawQuery, "size")?.toLongOrNull() ?: return null
        val expectedModified = queryValue(uri.rawQuery, "modified")?.toLongOrNull() ?: return null
        if (Files.size(source) != expectedSize || Files.getLastModifiedTime(source).toMillis() != expectedModified) {
            return null
        }

        if (uri.host == "sidecar") return readBounded(source)

        val cacheDirectory = DesktopMediaCache.directory.resolve("local-artwork")
        Files.createDirectories(cacheDirectory)
        val cacheFile = cacheDirectory.resolve(cacheName(source, expectedSize, expectedModified))
        readBounded(cacheFile)?.let { return it }

        val extracted = extractAttachedPicture(source) ?: return null
        if (extracted.isEmpty() || extracted.size > MAX_ARTWORK_BYTES) return null
        val temporary = Files.createTempFile(cacheDirectory, "art-", ".tmp")
        try {
            Files.write(temporary, extracted)
            runCatching {
                Files.move(
                    temporary,
                    cacheFile,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(temporary, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } finally {
            Files.deleteIfExists(temporary)
        }
        extracted
    }.getOrNull()

    private fun directoryIndex(folder: Path): DirectoryArtworkIndex? = runCatching {
        if (!Files.isDirectory(folder)) return null
        val modified = Files.getLastModifiedTime(folder).toMillis()
        directoryArt[folder]?.takeIf { it.directoryModifiedMillis == modified }?.let { return it }

        val supported = Files.newDirectoryStream(folder).use { stream ->
            stream.asSequence()
                .filter { path ->
                    !path.fileName.toString().startsWith('.') && Files.isRegularFile(path) &&
                        path.extension.lowercase(Locale.ROOT) in supportedImageExtensions
                }
                .filter { path -> runCatching { Files.size(path) in 1..MAX_ARTWORK_BYTES }.getOrDefault(false) }
                .toList()
        }
        val byTrack = supported.associateBy { path ->
            path.fileName.toString().substringBeforeLast('.', path.fileName.toString()).lowercase(Locale.ROOT)
        }
        val priorities = listOf(
            "cover", "frontcover", "front", "folder", "albumart", "album", "coverart", "artwork",
        )
        val shared = supported.minWithOrNull(
            compareBy<Path> { path ->
                val name = path.fileName.toString().substringBeforeLast('.', "").lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9]"), "")
                priorities.indexOfFirst { name == it }.let { exact ->
                    if (exact >= 0) exact else priorities.indexOfFirst { prefix -> name.startsWith(prefix) }
                        .let { rank -> if (rank >= 0) rank + priorities.size else Int.MAX_VALUE }
                }
            }.thenByDescending { path -> runCatching { Files.size(path) }.getOrDefault(0L) },
        )?.takeIf { path ->
            val name = path.fileName.toString().substringBeforeLast('.', "").lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")
            priorities.any { name == it || name.startsWith(it) } || supported.size == 1
        }
        DirectoryArtworkIndex(modified, byTrack, shared).also { directoryArt[folder] = it }
    }.getOrNull()

    private fun reference(kind: String, path: Path): String? = runCatching {
        val normalized = path.toAbsolutePath().normalize()
        val size = Files.size(normalized)
        if (!Files.isRegularFile(normalized) || size !in 1..MAX_ARTWORK_BYTES) return null
        reference(kind, normalized, size, Files.getLastModifiedTime(normalized).toMillis())
    }.getOrNull()

    private fun reference(kind: String, path: Path, size: Long, modifiedMillis: Long): String {
        val encodedPath = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toAbsolutePath().normalize().toString().toByteArray(UTF_8))
        return "$SCHEME://$kind/$encodedPath?size=$size&modified=$modifiedMillis"
    }

    private fun extractAttachedPicture(source: Path): ByteArray? {
        av_log_set_level(AV_LOG_ERROR)
        val format = AVFormatContext(null)
        if (avformat_open_input(format, source.toString(), null, null as AVDictionary?) < 0) return null
        try {
            // Some files have a readable cover stream even when FFmpeg cannot inspect the audio
            // stream fully (unsupported codec details, truncated tail, or damaged duration data).
            // Stream discovery is best-effort; do not discard attached pictures on that failure.
            avformat_find_stream_info(format, null as AVDictionary?)
            for (index in 0 until format.nb_streams()) {
                val stream = format.streams(index)
                if ((stream.disposition() and ATTACHED_PICTURE) == 0) continue
                val packet = stream.attached_pic()
                val size = packet.size().toLong()
                if (size !in 1..MAX_ARTWORK_BYTES) continue
                val data = packet.data() ?: continue
                return ByteArray(size.toInt()).also { data.get(it) }
            }
            return null
        } finally {
            avformat_close_input(format)
        }
    }

    private fun readBounded(path: Path): ByteArray? {
        if (!Files.isRegularFile(path)) return null
        val size = Files.size(path)
        if (size !in 1..MAX_ARTWORK_BYTES) return null
        return Files.readAllBytes(path)
    }

    private fun queryValue(query: String?, name: String): String? = query
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")

    private fun cacheName(path: Path, size: Long, modified: Long): String {
        val identity = "${path.toAbsolutePath().normalize()}|$size|$modified".toByteArray(UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(identity)
            .joinToString(separator = "") { byte -> "%02x".format(byte) } + ".img"
    }
}
