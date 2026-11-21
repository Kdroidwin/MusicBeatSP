package com.music.bitchord.feature.localmusic.ui.components

import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Long-lived, bounded cache for the large image used behind playlist detail pages. */
internal object PersistentPlaylistArtworkCache {
    private const val DIRECTORY = "playlist_fullscreen_art"
    private const val MAX_BYTES = 192L * 1024L * 1024L
    private const val MAX_FILES = 96

    fun fileFor(context: Context, url: String): File = File(directory(context), "${key(url)}.png")

    fun cachedFile(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.isFile && it.length() > 0L }

    fun imageRequest(context: Context, file: File): ImageRequest = ImageRequest.Builder(context)
        .data(file)
        // A refreshed file keeps the same path. Include its modified time so
        // Coil does not reuse the previous in-memory decode after refresh.
        .memoryCacheKey("playlist-art-file:${file.name}:${file.length()}:${file.lastModified()}")
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

    suspend fun load(
        context: Context,
        url: String,
        refresh: Boolean,
        refreshKey: Long,
    ): File? = withContext(Dispatchers.IO) {
        val destination = fileFor(context, url)
        if (!refresh && destination.isFile && destination.length() > 0L) {
            destination.setLastModified(System.currentTimeMillis())
            return@withContext destination
        }

        runCatching {
            destination.parentFile?.mkdirs()
            val cacheKey = "playlist-art:$url${if (refresh) ":refresh:$refreshKey" else ""}"
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(ARTWORK_SIZE_PX)
                .allowHardware(false)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .apply {
                    if (refresh) {
                        memoryCachePolicy(CachePolicy.DISABLED)
                        diskCachePolicy(CachePolicy.DISABLED)
                    }
                }
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                ?: return@runCatching null
            val bitmap = result.image.toBitmap()
            if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.width.toLong() * bitmap.height > 4_194_304L) {
                return@runCatching null
            }
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            val saved = temporary.outputStream().buffered().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            if (!saved || temporary.length() <= 0L) {
                temporary.delete()
                return@runCatching null
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            destination.setLastModified(System.currentTimeMillis())
            prune(destination.parentFile, destination)
            destination.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
    }

    suspend fun fetchFresh(context: Context, url: String, refreshKey: Long): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val cacheKey = "playlist-art:$url:refresh:$refreshKey"
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(ARTWORK_SIZE_PX)
                .allowHardware(false)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
            val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@runCatching null
            result.image.toBitmap().takeIf { bitmap ->
                bitmap.width > 0 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= 4_194_304L
            }
        }.getOrNull()
    }

    private fun directory(context: Context): File = File(context.applicationContext.filesDir, DIRECTORY)

    private fun key(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun prune(directory: File?, keep: File) {
        val files = directory?.listFiles { file -> file.isFile && file.extension == "png" }?.toList().orEmpty()
        var totalBytes = files.sumOf { it.length() }
        var totalFiles = files.size
        files.asSequence()
            .filterNot { it == keep }
            .sortedBy(File::lastModified)
            .forEach { old ->
                if (totalBytes <= MAX_BYTES && totalFiles <= MAX_FILES) return
                val size = old.length()
                if (old.delete()) {
                    totalBytes -= size
                    totalFiles--
                }
            }
    }

    const val ARTWORK_SIZE_PX = 1440
}
