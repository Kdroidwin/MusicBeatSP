package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

/**
 * Warms a small prefix of local playlist files while their rows are visible.
 * This only fills the operating-system file cache; it never writes audio or
 * changes the playback queue. Network and non-MediaStore content providers are
 * deliberately excluded.
 */
internal object LocalAudioHeaderPrefetcher {
    const val MAX_TRACKS_PER_PASS = 3

    private const val PREFIX_BYTES = 512 * 1024
    private const val CHUNK_BYTES = 32 * 1024
    private const val MAX_REMEMBERED_URIS = 128

    private val lock = Any()
    private val warmedUris = object : LinkedHashMap<String, Unit>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_REMEMBERED_URIS
    }

    fun canPrefetch(song: Song): Boolean = localUri(song) != null

    suspend fun prefetch(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val uris = songs.asSequence()
            .mapNotNull(::localUri)
            .distinctBy(Uri::toString)
            .take(MAX_TRACKS_PER_PASS)
            .toList()

        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val key = uri.toString()
            if (!reserve(key)) continue

            var completed = false
            try {
                val input = appContext.contentResolver.openInputStream(uri) ?: continue
                input.use { stream ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var remaining = PREFIX_BYTES
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read <= 0) break
                        remaining -= read
                    }
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional optimization: normal Media3 loading remains the fallback.
            } finally {
                if (!completed) forget(key)
            }
        }
    }

    private fun localUri(song: Song): Uri? {
        val source = song.localUri?.takeIf(String::isNotBlank)
            ?: song.localPath?.takeIf(String::isNotBlank)
            ?: return null
        val uri = Uri.parse(source).let { parsed ->
            if (parsed.scheme == null) Uri.fromFile(File(source)) else parsed
        }
        return when (uri.scheme) {
            "file" -> uri
            "content" -> uri.takeIf { it.authority == "media" }
            else -> null
        }
    }

    private fun reserve(key: String): Boolean = synchronized(lock) {
        if (warmedUris.containsKey(key)) {
            warmedUris[key]
            false
        } else {
            warmedUris[key] = Unit
            true
        }
    }

    private fun forget(key: String) {
        synchronized(lock) { warmedUris.remove(key) }
    }
}
