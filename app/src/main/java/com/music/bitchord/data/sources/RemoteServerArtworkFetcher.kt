package com.music.bitchord.data.sources

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import coil3.toAndroidUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches private server artwork without putting auth tokens in image URLs or media metadata. */
class RemoteServerArtworkFetcher(
    private val uri: Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        try {
            val bytes = SourceRegistry.loadRemoteServerArtwork(uri) ?: return@withContext null
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val requested = maxOf(options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 })
                .takeIf { it > 0 }
                ?.coerceIn(MIN_IMAGE_PX, MAX_IMAGE_PX)
                ?: DEFAULT_IMAGE_PX
            val decodeOptions = BitmapFactory.Options().apply {
                var sample = 1
                while (bounds.outWidth / sample > requested * 1.5 || bounds.outHeight / sample > requested * 1.5) {
                    sample *= 2
                }
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return@withContext null
            ImageFetchResult(image = bitmap.asImage(), isSampled = decodeOptions.inSampleSize > 1, dataSource = DataSource.NETWORK)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    class CoilUriFactory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uri = data.toAndroidUri()
            return if (isRemoteArt(uri)) RemoteServerArtworkFetcher(uri, options) else null
        }
    }

    class AndroidUriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (isRemoteArt(data)) RemoteServerArtworkFetcher(data, options) else null
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private const val DEFAULT_IMAGE_PX = 480
        private const val MIN_IMAGE_PX = 64
        private const val MAX_IMAGE_PX = 1600

        private fun isRemoteArt(uri: Uri) = uri.scheme == "bitchord" && uri.authority == "server-art"
    }
}
