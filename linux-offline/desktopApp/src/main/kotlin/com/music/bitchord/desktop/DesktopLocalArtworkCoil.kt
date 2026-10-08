package com.music.bitchord.desktop

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/** Lets the shared Coil-based player render the desktop's lazily extracted local artwork URIs. */
internal class DesktopLocalArtworkFetcher(
    private val reference: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = withContext(Dispatchers.IO) { DesktopLocalArtwork.load(reference) } ?: return null
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = options.fileSystem,
            ),
            mimeType = imageMimeType(bytes),
            dataSource = DataSource.DISK,
        )
    }

    internal class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.scheme.equals("local-artwork", ignoreCase = true)) return null
            return DesktopLocalArtworkFetcher(data.toString(), options)
        }
    }

    private companion object {
        fun imageMimeType(bytes: ByteArray): String = when {
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            bytes.startsWithAscii("GIF8") -> "image/gif"
            bytes.size >= 12 && bytes.startsWithAscii("RIFF", 0) && bytes.startsWithAscii("WEBP", 8) -> "image/webp"
            else -> "image/*"
        }

        fun ByteArray.startsWith(vararg expected: Int): Boolean =
            size >= expected.size && expected.indices.all { (this[it].toInt() and 0xFF) == expected[it] }

        fun ByteArray.startsWithAscii(value: String, offset: Int = 0): Boolean =
            size >= offset + value.length && value.indices.all { this[offset + it].toInt().toChar() == value[it] }
    }
}
