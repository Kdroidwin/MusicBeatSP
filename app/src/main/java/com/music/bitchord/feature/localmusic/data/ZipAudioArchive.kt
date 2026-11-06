package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal data class ZipAudioEntry(
    val path: String,
    val title: String,
    val extension: String,
    val archiveName: String,
    val sizeBytes: Long,
)

/** Reads audio entries from a user-selected ZIP and extracts a tapped track to cache for playback. */
internal object ZipAudioArchive {
    private const val MAX_AUDIO_ENTRY_BYTES = 1_073_741_824L
    private const val MAX_ARCHIVE_ENTRIES = 20_000
    private const val MAX_ARCHIVE_SCAN_BYTES = 4_294_967_296L
    private const val CACHE_FREE_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
    private val audioExtensions = setOf(
        "3gp", "aac", "aiff", "alac", "ape", "flac", "m4a", "mp3", "mp4", "oga", "ogg",
        "opus", "wav", "webm", "wma", "wv", "dsf", "dff",
    )

    fun listAudioEntries(context: Context, uri: Uri, archiveName: String): List<ZipAudioEntry> {
        val result = mutableListOf<ZipAudioEntry>()
        var entryCount = 0
        var inflatedBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                        throw IOException("ZIP archive contains too many entries")
                    }
                    val filename = entry.name.substringAfterLast('/')
                    val extension = filename.substringAfterLast('.', "").lowercase()
                    var shouldList = !entry.isDirectory && extension in audioExtensions &&
                        (entry.size < 0L || entry.size <= MAX_AUDIO_ENTRY_BYTES)
                    var entryBytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        inflatedBytes += count
                        if (entryBytes > MAX_AUDIO_ENTRY_BYTES) shouldList = false
                        if (inflatedBytes > MAX_ARCHIVE_SCAN_BYTES) {
                            throw IOException("ZIP archive expands beyond the scan limit")
                        }
                    }
                    if (shouldList) {
                        val title = filename.substringBeforeLast('.')
                        result += ZipAudioEntry(
                            path = entry.name,
                            title = title.ifBlank { filename },
                            extension = extension,
                            archiveName = archiveName.substringBeforeLast('.').ifBlank { archiveName },
                            sizeBytes = entryBytes,
                        )
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Could not open ZIP archive")
        return result
    }

    suspend fun extract(context: Context, uri: Uri, entry: ZipAudioEntry): Song? = withContext(Dispatchers.IO) {
        val id = sha256("${uri}\u0000${entry.path}")
        val directory = File(context.cacheDir, "zip-audio").apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("Could not create audio cache")
        val target = File(directory, "$id.${entry.extension}")
        if (!target.isFile || target.length() == 0L) {
            val partial = File(directory, "$id.part")
            partial.delete()
            var copied = false
            try {
                val spaceLimitedMax = (StatFs(directory.absolutePath).availableBytes - CACHE_FREE_SPACE_RESERVE_BYTES)
                    .coerceAtLeast(0L)
                    .coerceAtMost(MAX_AUDIO_ENTRY_BYTES)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entryCount = 0
                        var inflatedBytes = 0L
                        while (true) {
                            val current = zip.nextEntry ?: break
                            if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                                throw IOException("ZIP archive contains too many entries")
                            }
                            if (current.name == entry.path && !current.isDirectory) {
                                if (current.size > spaceLimitedMax) {
                                    throw IOException("Not enough cache space to extract this audio")
                                }
                                partial.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var total = 0L
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        inflatedBytes += count
                                        if (total > spaceLimitedMax) {
                                            throw IOException("Audio entry exceeds available cache space")
                                        }
                                        if (inflatedBytes > MAX_ARCHIVE_SCAN_BYTES) {
                                            throw IOException("ZIP archive expands beyond the scan limit")
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                                zip.closeEntry()
                                copied = true
                                break
                            } else {
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    inflatedBytes += count
                                    if (inflatedBytes > MAX_ARCHIVE_SCAN_BYTES) {
                                        throw IOException("ZIP archive expands beyond the scan limit")
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                } ?: return@withContext null
                if (!copied || partial.length() == 0L || !partial.renameTo(target)) {
                    partial.delete()
                    return@withContext null
                }
            } catch (failure: Throwable) {
                partial.delete()
                throw failure
            }
        }
        Song(
            videoId = "zip-$id",
            title = entry.title,
            artist = entry.archiveName,
            thumbnailUrl = null,
            localUri = Uri.fromFile(target).toString(),
            localPath = target.absolutePath,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
