package com.music.bitchord.feature.artistimage.coil

import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.Options
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.feature.artistimage.data.DeezerArtistService
import com.music.bitchord.feature.artistimage.model.ArtistImage

/**
 * Coil 3 Fetcher for [ArtistImage] that resolves the artist through YouTube Music,
 * with optional Deezer and track-art fallbacks.
 */
class ArtistImageFetcher(
    private val loader: ImageLoader,
    private val options: Options,
    private val image: ArtistImage,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val customArtworkUrl = image.customArtworkUrl?.takeIf(String::isNotBlank)
        // Hybrid/online artist thumbnails come from YouTube Music. The Deezer
        // switch controls only the optional Deezer fallback and must not gate
        // YouTube Music artwork. User-selected images remain the first choice.
        val youtubeMusicUrl = if (
            customArtworkUrl == null &&
            image.allowOnlineLookup &&
            AppSettings.youtubeMusicNetworkAllowed
        ) {
            YtMusicRepository.findArtistPage(image.name).getOrNull()?.thumbnailUrl
        } else {
            null
        }
        val deezerUrl = if (customArtworkUrl == null && youtubeMusicUrl == null &&
            image.allowOnlineLookup && AppSettings.artistDeezerArtworkEnabled.value
        ) {
            DeezerArtistService.getArtistImageUrl(image.name, image.isLarge)
        } else null
        // Local/old artist mode must stay with local artwork. A fallback may
        // itself be a remote thumbnail, so suppress both HTTP schemes when
        // online lookup is disabled.
        val fallbackUrl = image.fallbackUrl?.takeIf {
            image.allowOnlineLookup || (
                !it.startsWith("https://", ignoreCase = true) &&
                    !it.startsWith("http://", ignoreCase = true)
                )
        }
        val target = customArtworkUrl ?: youtubeMusicUrl ?: deezerUrl ?: fallbackUrl

        if (!target.isNullOrBlank()) {
            val mapped = loader.components.map(target, options)
            val output = loader.components.newFetcher(mapped, options, loader)
            val fetcher = output?.first
            if (fetcher != null) {
                return fetcher.fetch()
            }
        }
        return null
    }

    class Factory : Fetcher.Factory<ArtistImage> {
        override fun create(
            data: ArtistImage,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return ArtistImageFetcher(imageLoader, options, data)
        }
    }
}
