package com.music.bitchord.feature.localmusic.coil

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Shares local cover extraction/decode work between the player, mesh, and palette requests. */
internal object LocalArtworkCache {

    private val embeddedBytes = SingleFlightLru<String, ByteArray>(
        maxWeight = 8L * 1024 * 1024,
        maxEntries = 64,
        weightOf = { it.size.toLong() },
    )
    private val decodedBitmaps = SingleFlightLru<String, Bitmap>(
        maxWeight = 12L * 1024 * 1024,
        maxEntries = 24,
        weightOf = { it.allocationByteCount.toLong() },
    )

    suspend fun embeddedBytes(context: Context, key: String, load: () -> ByteArray?): ByteArray? =
        embeddedBytes.getOrLoad(key) {
            readDisk(context.applicationContext, key) ?: load()?.also { bytes ->
                writeDisk(context.applicationContext, key, bytes)
            }
        }

    suspend fun embeddedBitmap(bytes: ByteArray, maxDim: Int, decode: () -> Bitmap?): Bitmap? {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val key = "embedded:${Base64.encodeToString(digest, Base64.NO_WRAP)}:$maxDim"
        return decodedBitmaps.getOrLoad(key, decode)
    }

    suspend fun albumBitmap(albumId: Long, maxDim: Int, decode: () -> Bitmap?): Bitmap? =
        decodedBitmaps.getOrLoad("album:$albumId:$maxDim", decode)

    /**
     * Encoded embedded covers survive process restarts in app-private cache storage.
     * The caller's key includes the track URI and its modification marker, so updated
     * tags naturally get a new entry. Cache I/O is best-effort and never blocks playback.
     */
    private fun readDisk(context: Context, key: String): ByteArray? = runCatching {
        val file = diskFile(context, key)
        if (!file.isFile || file.length() !in 1..MAX_SINGLE_ARTWORK_BYTES) {
            file.delete()
            return@runCatching null
        }
        file.setLastModified(System.currentTimeMillis())
        file.readBytes()
    }.getOrNull()

    private fun writeDisk(context: Context, key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_SINGLE_ARTWORK_BYTES) return
        runCatching {
            val file = diskFile(context, key)
            val directory = file.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            if (file.isFile && file.length() in 1..MAX_SINGLE_ARTWORK_BYTES) {
                file.setLastModified(System.currentTimeMillis())
                return
            }

            val temp = File.createTempFile("cover-", ".tmp", directory)
            try {
                FileOutputStream(temp).use { it.write(bytes) }
                if (!temp.renameTo(file)) {
                    if (!file.exists()) temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
                pruneDiskCache(directory)
            } finally {
                temp.delete()
            }
        }
    }

    private fun diskFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val filename = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(File(context.cacheDir, DISK_CACHE_DIRECTORY), "$filename.cover")
    }

    private fun pruneDiskCache(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "cover" } ?: return
        var totalBytes = files.sumOf(File::length)
        var remaining = files.size
        files.sortedBy(File::lastModified).forEach { file ->
            if (totalBytes <= MAX_DISK_CACHE_BYTES && remaining <= MAX_DISK_CACHE_ENTRIES) return
            totalBytes -= file.length()
            remaining--
            file.delete()
        }
    }

    /** A small weighted cache with single-flight loads and short negative caching. */
    private class SingleFlightLru<K : Any, V : Any>(
        private val maxWeight: Long,
        private val maxEntries: Int,
        private val weightOf: (V) -> Long,
    ) {
        private data class Entry<V>(val value: V?, val storedAtMs: Long)

        private val lock = Any()
        private val cache = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
        private val inFlight = HashMap<K, CompletableDeferred<V?>>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var weight = 0L

        suspend fun getOrLoad(key: K, load: () -> V?): V? {
            var deferred: CompletableDeferred<V?>
            var isOwner = false
            synchronized(lock) {
                val now = android.os.SystemClock.elapsedRealtime()
                val cached = cache[key]
                if (cached != null) {
                    if (cached.value != null || now - cached.storedAtMs < NEGATIVE_CACHE_MS) {
                        return cached.value
                    }
                    removeLocked(key)
                }

                deferred = inFlight[key] ?: CompletableDeferred<V?>().also {
                    inFlight[key] = it
                    isOwner = true
                }
            }

            if (isOwner) {
                scope.launch {
                    val value = runCatching(load).getOrNull()
                    synchronized(lock) {
                        if (inFlight[key] === deferred) inFlight.remove(key)
                        putLocked(key, Entry(value, android.os.SystemClock.elapsedRealtime()))
                    }
                    deferred.complete(value)
                }
            }
            return deferred.await()
        }

        private fun putLocked(key: K, entry: Entry<V>) {
            removeLocked(key)
            cache[key] = entry
            entry.value?.let { weight += weightOf(it).coerceAtLeast(0L) }
            while (cache.size > maxEntries || weight > maxWeight) {
                val oldest = cache.entries.iterator().let { if (it.hasNext()) it.next().key else return }
                removeLocked(oldest)
            }
        }

        private fun removeLocked(key: K) {
            cache.remove(key)?.value?.let { weight -= weightOf(it).coerceAtLeast(0L) }
        }

        private companion object {
            const val NEGATIVE_CACHE_MS = 30_000L
        }
    }

    private const val DISK_CACHE_DIRECTORY = "local-artwork"
    private const val MAX_DISK_CACHE_BYTES = 64L * 1024 * 1024
    private const val MAX_DISK_CACHE_ENTRIES = 256
    private const val MAX_SINGLE_ARTWORK_BYTES = 8L * 1024 * 1024
}
