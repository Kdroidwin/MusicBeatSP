package com.music.bitchord.feature.localmusic.coil

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri
import com.kyant.taglib.TagLib
import com.music.bitchord.data.model.LOCAL_ARTWORK_SIZE_PARAMETER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_THUMBNAIL_PX = 256
private const val MEDIASTORE_THUMBNAIL_MAX_PX = 512
private const val MAX_ARTWORK_PX = 1600
private const val MAX_CACHED_ARTWORK_BYTES = 8 * 1024 * 1024

/**
 * Coil 3 Fetcher that extracts genuine track-level embedded artwork directly
 * from local audio files via native TagLib / MediaMetadataRetriever, falling back
 * to MediaStore album art or directory cover sidecars.
 *
 * This prevents tracks sharing generic album names (e.g. "Music" or "Unknown")
 * from colliding under a single MediaStore album_id and showing the wrong album art.
 */
class LocalAudioArtworkFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUri = if (uri.scheme == "content") uri.buildUpon().clearQuery().build() else uri
            val requestedPx = maxOf(
                options.size.width.pxOrElse { 0 },
                options.size.height.pxOrElse { 0 },
                uri.getQueryParameter(LOCAL_ARTWORK_SIZE_PARAMETER)?.toIntOrNull() ?: 0,
            ).takeIf { it > 0 }?.coerceIn(256, MAX_ARTWORK_PX) ?: DEFAULT_THUMBNAIL_PX
            val needsFullArtwork = requestedPx > MEDIASTORE_THUMBNAIL_MAX_PX

            // Small library rows and playlist covers use MediaStore's fast cached
            // thumbnail. Larger requests (the player and expanded artwork) must
            // continue to the embedded/original cover below; returning this
            // thumbnail first was the source of the visibly blurry player art.
            if (!needsFullArtwork && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cleanUri.scheme == "content") {
                val thumb = runCatching {
                    val thumbPx = requestedPx.coerceAtLeast(DEFAULT_THUMBNAIL_PX)
                    context.contentResolver.loadThumbnail(cleanUri, Size(thumbPx, thumbPx), null)
                }.getOrNull()
                if (thumb != null) {
                    return@withContext ImageFetchResult(
                        image = thumb.asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK,
                    )
                }
            }

            // Preserve the more accurate track-level embedded art as a fallback. This still
            // handles file:// URIs and providers without a MediaStore thumbnail. Keep the
            // encoded cover bytes separate from decoding: the player, its palette, and its
            // mesh can all ask for the same local cover at once, and separate MediaStore URIs
            // for tracks on one album should not make us parse/decode the same cover repeatedly.
            val embeddedCacheKey = embeddedCacheKey(context, uri, cleanUri)
            val embeddedBytes = LocalArtworkCache.embeddedBytes(context, embeddedCacheKey) {
                extractEmbeddedCoverBytes(context, uri)
            }
            val embeddedBitmap = embeddedBytes?.let { bytes ->
                LocalArtworkCache.embeddedBitmap(bytes, requestedPx) {
                    decodeCoverBitmap(bytes, requestedPx)
                }
            }
            if (embeddedBitmap != null) {
                return@withContext ImageFetchResult(
                    image = embeddedBitmap.asImage(),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
            }

            // 3. Fallback to albumart/<albumId> if albumId query param is provided or if URI is albumart
            val albumId = uri.getQueryParameter("albumId")?.toLongOrNull()
                ?: if (uri.path?.contains("/audio/albumart") == true) {
                    uri.lastPathSegment?.toLongOrNull()
                } else null
            if (albumId != null && albumId > 0) {
                // Album thumbnails are useful at row size. A large player request
                // reads the album-art provider's original bytes and downsamples
                // once to the requested decode size instead of enlarging 512px art.
                if (!needsFullArtwork && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val albumUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                    val albumThumb = runCatching {
                        val thumbPx = requestedPx.coerceAtLeast(DEFAULT_THUMBNAIL_PX)
                            .coerceAtMost(MEDIASTORE_THUMBNAIL_MAX_PX)
                        context.contentResolver.loadThumbnail(albumUri, Size(thumbPx, thumbPx), null)
                    }.getOrNull()
                    if (albumThumb != null) {
                        return@withContext ImageFetchResult(
                            image = albumThumb.asImage(),
                            isSampled = false,
                            dataSource = DataSource.DISK,
                        )
                    }
                }

                // Try legacy albumart ContentProvider URI
                val albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")
                val albumBitmap = LocalArtworkCache.albumBitmap(
                    context = context,
                    albumId = albumId,
                    maxDim = requestedPx,
                    loadEncoded = { readCoverBytes(context, albumArtUri) },
                    decode = ::decodeCoverBitmap,
                ) ?: decodeCoverStream(context, albumArtUri, requestedPx)
                if (albumBitmap != null) {
                    return@withContext ImageFetchResult(
                        image = albumBitmap.asImage(),
                        isSampled = false,
                        dataSource = DataSource.DISK,
                    )
                }
            }

            // 4. Fallback to directory cover image sidecars (cover.jpg, folder.jpg, etc.)
            val folderBitmap = findFolderCover(context, uri, requestedPx)
            if (folderBitmap != null) {
                return@withContext ImageFetchResult(
                    image = folderBitmap.asImage(),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
            }

            null
        }.getOrNull()
    }

    private fun embeddedCacheKey(context: Context, uri: Uri, cleanUri: Uri): String {
        // Preload requests often arrive as MediaStore content:// URIs, while a
        // downloaded queue entry may later use the same file:// path. Key both
        // forms by their physical file identity when MediaStore exposes it, so
        // playback can reuse what startup preload already extracted.
        val filePath = if (cleanUri.scheme == "file") cleanUri.path else resolveFilePath(context, cleanUri)
        val file = filePath?.let(::File)?.takeIf { it.isFile }
        return if (file != null) {
            buildString {
                append("local-file:").append(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath))
                append("|modified=").append(file.lastModified())
                append("|length=").append(file.length())
            }
        } else {
            buildString {
                append(cleanUri.normalizeScheme())
                // If a provider does not expose a filesystem path, MediaStore's
                // modification marker still separates an edited file's cover.
                uri.getQueryParameter("t")?.let { append("|modified=").append(it) }
            }
        }
    }

    private fun extractEmbeddedCoverBytes(context: Context, uri: Uri): ByteArray? {
        // First try native TagLib (fast C++ tag parser)
        val pfd = openFileDescriptor(context, uri)
        if (pfd != null) {
            val tagLibBytes = pfd.use { descriptor ->
                runCatching {
                    val coverPicture = TagLib.getFrontCover(descriptor.dup().detachFd())
                    val bytes = coverPicture?.data ?: return@use null
                    bytes.takeIf(ByteArray::isNotEmpty)
                }.getOrNull()
            }
            if (tagLibBytes != null) return tagLibBytes
        }

        // Second fallback: MediaMetadataRetriever
        return runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                val cleanUri = if (uri.scheme == "content") uri.buildUpon().clearQuery().build() else uri
                if (cleanUri.scheme == "file") {
                    mmr.setDataSource(cleanUri.path)
                } else {
                    mmr.setDataSource(context, cleanUri)
                }
                val picBytes = mmr.embeddedPicture
                picBytes?.takeIf(ByteArray::isNotEmpty)
            } finally {
                mmr.release()
            }
        }.getOrNull()
    }

    private fun decodeCoverBitmap(bytes: ByteArray, maxDim: Int = 1200): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) return null

        var sampleSize = 1
        while (width / sampleSize > maxDim * 1.5 || height / sampleSize > maxDim * 1.5) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun openFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        val cleanUri = if (uri.scheme == "content") uri.buildUpon().clearQuery().build() else uri
        val pfd = runCatching {
            if (cleanUri.scheme == "file") {
                val file = File(cleanUri.path ?: return null)
                if (file.exists() && file.canRead()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else null
            } else {
                context.contentResolver.openFileDescriptor(cleanUri, "r")
            }
        }.getOrNull()
        if (pfd != null) return pfd

        val filePath = resolveFilePath(context, uri)
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                return runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }.getOrNull()
            }
        }
        return null
    }

    private suspend fun findFolderCover(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val filePath = resolveFilePath(context, uri) ?: return null
        val parent = File(filePath).parentFile ?: return null
        if (!parent.exists() || !parent.isDirectory) return null

        val candidates = listOf(
            "cover.jpg", "cover.png", "cover.jpeg",
            "folder.jpg", "folder.png", "folder.jpeg",
            "front.jpg", "front.png", "front.jpeg",
            "albumart.jpg", "albumart.png",
        )
        for (name in candidates) {
            val file = File(parent, name)
            if (file.exists() && file.isFile && file.canRead()) {
                val cacheKey = "folder:${file.absolutePath}|modified=${file.lastModified()}|length=${file.length()}"
                val bytes = LocalArtworkCache.embeddedBytes(context, cacheKey) {
                    runCatching { file.inputStream().use(::readBoundedArtworkBytes) }.getOrNull()
                }
                val bitmap = bytes?.let { encoded ->
                    LocalArtworkCache.embeddedBitmap(encoded, maxDim) {
                        decodeCoverBitmap(encoded, maxDim)
                    }
                } ?: decodeCoverFile(file, maxDim)
                if (bitmap != null) return bitmap
            }
        }
        return null
    }

    private fun resolveFilePath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        val cleanUri = uri.buildUpon().clearQuery().build()
        val cacheKey = cleanUri.normalizeScheme().toString()
        val now = SystemClock.elapsedRealtime()
        val cached = resolvedPathCache.compute(cacheKey) { _, previous ->
            when {
                previous?.path != null && File(previous.path).isFile -> previous
                previous?.path == null && previous != null && now - previous.cachedAtMs < NEGATIVE_PATH_CACHE_MS -> previous
                else -> {
                    val path = runCatching {
                        context.contentResolver.query(
                            cleanUri,
                            arrayOf(MediaStore.Audio.Media.DATA),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val index = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                                if (index >= 0) cursor.getString(index) else null
                            } else null
                        }
                    }.getOrNull()
                    CachedResolvedPath(path, now)
                }
            }
        }
        if (resolvedPathCache.size > MAX_RESOLVED_PATHS) {
            resolvedPathCache.keys.firstOrNull { it != cacheKey }?.let(resolvedPathCache::remove)
        }
        return cached?.path
    }

    /** Decode provider artwork without loading a full-size bitmap into memory. */
    private fun decodeCoverStream(context: Context, uri: Uri, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val decodeOptions = scaledOptions(bounds.outWidth, bounds.outHeight, maxDim)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }.getOrNull()

    private fun readCoverBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use(::readBoundedArtworkBytes)
    }.getOrNull()

    /** Avoid retaining unexpectedly large provider/sidecar files in app storage. */
    private fun readBoundedArtworkBytes(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_CACHED_ARTWORK_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf(ByteArray::isNotEmpty)
    }

    private fun decodeCoverFile(file: File, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        BitmapFactory.decodeFile(
            file.absolutePath,
            scaledOptions(bounds.outWidth, bounds.outHeight, maxDim),
        )
    }.getOrNull()

    private fun scaledOptions(width: Int, height: Int, maxDim: Int) = BitmapFactory.Options().apply {
        var sampleSize = 1
        while (width / sampleSize > maxDim * 1.5 || height / sampleSize > maxDim * 1.5) {
            sampleSize *= 2
        }
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    companion object {
        private const val MAX_RESOLVED_PATHS = 512
        private const val NEGATIVE_PATH_CACHE_MS = 30_000L
        private data class CachedResolvedPath(val path: String?, val cachedAtMs: Long)
        private val resolvedPathCache = ConcurrentHashMap<String, CachedResolvedPath>()

        fun isLocalAudioUri(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme == "content") {
                val authority = uri.authority?.lowercase(Locale.ROOT) ?: ""
                val path = uri.path?.lowercase(Locale.ROOT) ?: ""
                return (authority == "media" || authority.endsWith(".media") || authority == "com.android.providers.media.documents") &&
                    (path.contains("/audio/media") || path.contains("/audio/"))
            }
            if (scheme == "file") {
                val path = uri.path?.lowercase(Locale.ROOT) ?: ""
                return path.endsWith(".mp3") || path.endsWith(".flac") ||
                    path.endsWith(".m4a") || path.endsWith(".ogg") ||
                    path.endsWith(".opus") || path.endsWith(".wav") ||
                    path.endsWith(".aac") || path.endsWith(".webm") ||
                    path.endsWith(".3gp")
            }
            return false
        }
    }

    class CoilUriFactory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(
            data: coil3.Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val androidUri = data.toAndroidUri()
            if (isLocalAudioUri(androidUri)) {
                return LocalAudioArtworkFetcher(context, androidUri, options)
            }
            return null
        }
    }

    class AndroidUriFactory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            if (isLocalAudioUri(data)) {
                return LocalAudioArtworkFetcher(context, data, options)
            }
            return null
        }
    }
}
