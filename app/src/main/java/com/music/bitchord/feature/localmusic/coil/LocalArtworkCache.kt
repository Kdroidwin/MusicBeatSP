package com.music.bitchord.feature.localmusic.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Shares local cover extraction/decode work between the player, mesh, and palette requests. */
internal object LocalArtworkCache {

    private var lastPersistentMode: Boolean? = null
    private var persistentCacheMigrated = false

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
            val appContext = context.applicationContext
            preparePersistentCache(appContext)
            val cached = readDisk(appContext, key)
            if (cached.found) {
                cached.bytes
            } else {
                load()?.also { bytes ->
                    writeDisk(appContext, key, bytes)
                } ?: run {
                    // A negative result is useful too: otherwise every player
                    // open re-runs TagLib and MediaMetadataRetriever on tracks
                    // whose artwork comes from the album provider. The key
                    // includes file mtime/length (or the MediaStore modified
                    // marker), so edits naturally invalidate this result.
                    writeMissingDisk(appContext, key)
                    null
                }
            }
        }

    suspend fun embeddedBitmap(
        context: Context,
        bytes: ByteArray,
        maxDim: Int,
        decode: () -> Bitmap?,
    ): Bitmap? {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val key = "embedded:${Base64.encodeToString(digest, Base64.NO_WRAP)}:$maxDim"
        val appContext = context.applicationContext
        return decodedBitmaps.getOrLoad(key) {
            readRenderedBitmap(appContext, key) ?: decode()?.also { bitmap ->
                writeRenderedBitmap(appContext, key, bitmap)
            }
        }
    }

    suspend fun albumBitmap(
        context: Context,
        albumId: Long,
        maxDim: Int,
        loadEncoded: () -> ByteArray?,
        decode: (ByteArray, Int) -> Bitmap?,
    ): Bitmap? {
        // MediaStore album-art fallbacks used to live only in the small in-memory
        // bitmap LRU. A full-library preload evicted early albums before playback
        // reached them, so the first play had to decode the provider image again.
        // Keep the encoded original in the same app-private persistent cache as
        // embedded covers, then decode only the requested player-size bitmap.
        val bytes = embeddedBytes(context, "album:$albumId", loadEncoded) ?: return null
        return embeddedBitmap(context, bytes, maxDim) { decode(bytes, maxDim) }
    }

    /**
     * Encoded embedded covers survive process restarts in app-private cache storage.
     * The caller's key includes the track URI and its modification marker, so updated
     * tags naturally get a new entry. Cache I/O is best-effort and never blocks playback.
     */
    private data class DiskLookup(val found: Boolean, val bytes: ByteArray? = null)

    private fun readDisk(context: Context, key: String): DiskLookup = runCatching {
        val file = diskFile(context, key)
        if (file.isFile) {
            if (file.length() in 1..MAX_SINGLE_ARTWORK_BYTES) {
                file.setLastModified(System.currentTimeMillis())
                return@runCatching DiskLookup(found = true, bytes = file.readBytes())
            }
            file.delete()
        }
        val missing = missingDiskFile(context, key)
        if (missing.isFile) {
            if (System.currentTimeMillis() - missing.lastModified() <= MISSING_CACHE_TTL_MS) {
                missing.setLastModified(System.currentTimeMillis())
                return@runCatching DiskLookup(found = true)
            }
            missing.delete()
        }
        DiskLookup(found = false)
    }.getOrDefault(DiskLookup(found = false))

    private fun writeDisk(context: Context, key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_SINGLE_ARTWORK_BYTES) return
        runCatching {
            val file = diskFile(context, key)
            missingDiskFile(context, key).delete()
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

    private fun writeMissingDisk(context: Context, key: String) {
        runCatching {
            val file = missingDiskFile(context, key)
            val directory = file.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            if (!file.exists()) file.createNewFile()
            file.setLastModified(System.currentTimeMillis())
            pruneDiskCache(directory)
        }
    }

    private fun diskFile(context: Context, key: String): File {
        return cacheFile(context, key, "cover")
    }

    private fun renderedFile(context: Context, key: String): File {
        return cacheFile(context, key, "rendered")
    }

    private fun cacheFile(context: Context, key: String, extension: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val filename = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val root = if (AppSettings.persistentLocalArtwork.value) context.filesDir else context.cacheDir
        return File(File(root, DISK_CACHE_DIRECTORY), "$filename.$extension")
    }

    /**
     * A full-library preload can outlive the small in-memory bitmap LRU. Keep
     * the already-downsampled player image on disk too, so reopening a track
     * does not decode a very large embedded image again after process restart
     * or cache eviction.
     */
    private fun readRenderedBitmap(context: Context, key: String): Bitmap? = runCatching {
        val file = renderedFile(context, key)
        if (!file.isFile) return@runCatching null
        if (file.length() !in 1..MAX_SINGLE_ARTWORK_BYTES) {
            file.delete()
            return@runCatching null
        }
        BitmapFactory.decodeFile(file.absolutePath)?.also {
            file.setLastModified(System.currentTimeMillis())
        } ?: run {
            file.delete()
            null
        }
    }.getOrNull()

    private fun writeRenderedBitmap(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            val file = renderedFile(context, key)
            val directory = file.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) return
            if (file.isFile && file.length() in 1..MAX_SINGLE_ARTWORK_BYTES) {
                file.setLastModified(System.currentTimeMillis())
                return
            }

            val temp = File.createTempFile("cover-rendered-", ".tmp", directory)
            try {
                val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 92
                val compressed = FileOutputStream(temp).use { output ->
                    bitmap.compress(format, quality, output)
                }
                if (!compressed || temp.length() !in 1..MAX_SINGLE_ARTWORK_BYTES) return
                if (!temp.renameTo(file)) {
                    if (!file.exists()) temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
                if (file.isFile) file.setLastModified(System.currentTimeMillis())
                pruneDiskCache(directory)
            } finally {
                temp.delete()
            }
        }
    }

    private fun missingDiskFile(context: Context, key: String): File {
        val coverFile = diskFile(context, key)
        return File(coverFile.parentFile, coverFile.nameWithoutExtension + ".missing")
    }

    private fun pruneDiskCache(directory: File) {
        val files = directory.listFiles()?.filter {
            it.isFile && it.extension in setOf("cover", "missing", "rendered")
        } ?: return
        var totalBytes = files.sumOf(File::length)
        var remaining = files.size
        files.sortedBy(File::lastModified).forEach { file ->
            if (totalBytes <= MAX_DISK_CACHE_BYTES && remaining <= MAX_DISK_CACHE_ENTRIES) return
            totalBytes -= file.length()
            remaining--
            file.delete()
        }
    }

    /** Promote already-extracted covers before a persistent-cache lookup. */
    private fun preparePersistentCache(context: Context) {
        synchronized(this) {
            val enabled = AppSettings.persistentLocalArtwork.value
            if (lastPersistentMode != enabled) {
                lastPersistentMode = enabled
                if (!enabled) persistentCacheMigrated = false
            }
            if (!enabled || persistentCacheMigrated) return
            persistentCacheMigrated = migrateExistingCache(context)
        }
    }

    private fun migrateExistingCache(context: Context): Boolean = runCatching {
            val source = File(context.cacheDir, DISK_CACHE_DIRECTORY)
            val destination = File(context.filesDir, DISK_CACHE_DIRECTORY)
            if (!source.isDirectory) return@runCatching true
            if (!destination.isDirectory && !destination.mkdirs()) return@runCatching false
            source.listFiles()
                ?.asSequence()
                ?.filter {
                    it.isFile && when (it.extension) {
                        "cover" -> it.length() in 1..MAX_SINGLE_ARTWORK_BYTES
                        "missing" -> System.currentTimeMillis() - it.lastModified() <= MISSING_CACHE_TTL_MS
                        "rendered" -> it.length() in 1..MAX_SINGLE_ARTWORK_BYTES
                        else -> false
                    }
                }
                ?.sortedBy(File::lastModified)
                ?.forEach { oldFile ->
                    val newFile = File(destination, oldFile.name)
                    if (!newFile.exists()) {
                        val temp = File.createTempFile("cover-migrate-", ".tmp", destination)
                        try {
                            oldFile.copyTo(temp, overwrite = true)
                            if (!temp.renameTo(newFile) && !newFile.exists()) {
                                temp.copyTo(newFile, overwrite = true)
                            }
                            if (newFile.exists()) newFile.setLastModified(oldFile.lastModified())
                        } finally {
                            temp.delete()
                        }
                    }
                }
            pruneDiskCache(destination)
            true
        }.getOrDefault(false)

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
    private const val MAX_DISK_CACHE_BYTES = 512L * 1024 * 1024
    private const val MAX_DISK_CACHE_ENTRIES = 4096
    private const val MAX_SINGLE_ARTWORK_BYTES = 8L * 1024 * 1024
    private const val MISSING_CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
}
