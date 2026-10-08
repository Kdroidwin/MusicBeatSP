package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Reads a local M3U/M3U8 file without granting or retaining access to unrelated folders. */
internal object DesktopM3uImporter {
    private const val MAX_FILE_BYTES = 10 * 1024 * 1024
    private const val MAX_ENTRIES = 50_000

    data class Result(
        val songs: List<Song>,
        val skipped: Int,
    )

    enum class Error { UNREADABLE, TOO_LARGE, EMPTY, NO_ENTRIES, TOO_MANY, NO_PARENT }

    class ImportException(val reason: Error) : IOException()

    fun import(file: Path): Result {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw ImportException(Error.UNREADABLE)
        }
        val bytes = Files.newInputStream(file).use { input -> input.readNBytes(MAX_FILE_BYTES + 1) }
        if (bytes.size > MAX_FILE_BYTES) throw ImportException(Error.TOO_LARGE)
        if (bytes.isEmpty()) throw ImportException(Error.EMPTY)

        val content = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            .removePrefix("\uFEFF")
        val playlistDirectory = file.toAbsolutePath().normalize().parent
            ?: throw ImportException(Error.NO_PARENT)
        val songs = ArrayList<Song>()
        val seen = HashSet<String>()
        var skipped = 0
        var extInfo: TrackInfo? = null
        var entries = 0

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    extInfo = parseExtInfo(line.substringAfter(':', "").substringAfter(',', ""))
                }
                return@forEach
            }

            entries++
            if (entries > MAX_ENTRIES) throw ImportException(Error.TOO_MANY)
            val metadata = extInfo
            extInfo = null
            val referencedPath = resolvePath(line, playlistDirectory)
            val song = referencedPath?.let(DesktopLocalMusic::songForPath)
            if (song == null) {
                skipped++
                return@forEach
            }
            val decorated = metadata?.let { info ->
                song.copy(
                    title = info.title?.takeIf(String::isNotBlank) ?: song.title,
                    artist = info.artist?.takeIf(String::isNotBlank) ?: song.artist,
                )
            } ?: song
            if (seen.add(decorated.videoId)) songs += decorated else skipped++
        }

        if (entries == 0) throw ImportException(Error.NO_ENTRIES)
        return Result(songs = songs, skipped = skipped)
    }

    private fun resolvePath(entry: String, playlistDirectory: Path): Path? = runCatching {
        when {
            entry.startsWith("file:", ignoreCase = true) -> Paths.get(URI(entry))
            entry.startsWith("content:", ignoreCase = true) ||
                entry.startsWith("http:", ignoreCase = true) ||
                entry.startsWith("https:", ignoreCase = true) -> null
            else -> {
                val path = Paths.get(entry)
                if (path.isAbsolute) path else playlistDirectory.resolve(path)
            }
        }?.normalize()
    }.getOrNull()

    private fun parseExtInfo(rawMetadata: String): TrackInfo? {
        val metadata = rawMetadata.trim().takeIf(String::isNotEmpty) ?: return null
        // M3U's common convention is "artist - title". A title-only field is also valid.
        val separator = metadata.indexOf(" - ")
        return if (separator > 0 && separator < metadata.lastIndex - 1) {
            TrackInfo(
                artist = metadata.substring(0, separator).trim(),
                title = metadata.substring(separator + 3).trim(),
            )
        } else {
            TrackInfo(title = metadata)
        }
    }

    private data class TrackInfo(val artist: String? = null, val title: String? = null)
}
