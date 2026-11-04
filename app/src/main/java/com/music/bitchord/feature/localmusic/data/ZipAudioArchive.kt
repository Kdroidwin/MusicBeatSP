package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
    private val audioExtensions = setOf(
        "3gp", "aac", "aiff", "alac", "ape", "flac", "m4a", "mp3", "mp4", "oga", "ogg",
        "opus", "wav", "webm", "wma", "wv", "dsf", "dff",
    )

    fun listAudioEntries(context: Context, uri: Uri, archiveName: String): List<ZipAudioEntry> {
        val result = mutableListOf<ZipAudioEntry>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val filename = entry.name.substringAfterLast('/')
                    val extension = filename.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && extension in audioExtensions &&
                        (entry.size < 0L || entry.size <= MAX_AUDIO_ENTRY_BYTES)
                    ) {
                        val title = filename.substringBeforeLast('.')
                        result += ZipAudioEntry(
                            path = entry.name,
                            title = title.ifBlank { filename },
                            extension = extension,
                            archiveName = archiveName.substringBeforeLast('.').ifBlank { archiveName },
                            sizeBytes = entry.size,
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
        val target = File(directory, "$id.${entry.extension}")
        if (!target.isFile || target.length() == 0L) {
            val partial = File(directory, "$id.part")
            partial.delete()
            var copied = false
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val current = zip.nextEntry ?: break
                        if (current.name == entry.path && !current.isDirectory) {
                            partial.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var total = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= MAX_AUDIO_ENTRY_BYTES) { "Audio entry is too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            copied = true
                            break
                        }
                        zip.closeEntry()
                    }
                }
            } ?: return@withContext null
            if (!copied || partial.length() == 0L || !partial.renameTo(target)) {
                partial.delete()
                return@withContext null
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
