package com.music.bitchord.data.lyrics.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * High-speed native Kotlin lyrics dispatcher supporting all 15 built-in providers.
 *
 * Bypasses the single-threaded QuickJS mutex for built-in providers so all active
 * providers execute truly in parallel over OkHttp's shared connection pool.
 */
object NativeLyricsProviders {

    val NATIVE_PROVIDER_IDS = setOf(
        "apple_music",
        "lyricsplus",
        "better_lyrics",
        "bini_lrc_red",
        "netease",
        "qqmusic",
        "kugou",
        "petitlyrics",
        "musixmatch",
        "lrclib",
        "utanet",
        "utaten",
        "jlyric",
        "genius",
        "ctc_align",
    )

    fun isNativeProvider(id: String): Boolean =
        id.lowercase() in NATIVE_PROVIDER_IDS

    suspend fun getLyrics(
        providerId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): String? {
        val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        return when (providerId.lowercase()) {
            "apple_music" -> AppleMusicPaxClient.getLyrics(title, artist, album, durationSec)
            "lyricsplus" -> LyricsPlusClient.getLyrics(title, artist, album, durationSec)
            "better_lyrics" -> BetterLyricsClient.getLyrics(title, artist, album, durationSec)
            "bini_lrc_red" -> BiniAndLrcRedClient.getLyrics(title, artist, album, durationSec)
            "netease" -> NetEaseLyricsClient.getLyrics(title, artist, album, durationSec)
            "qqmusic" -> QqMusicLyricsClient.getLyrics(title, artist, album, durationSec)
            "kugou" -> KugouLyricsClient.getLyrics(title, artist, album, durationSec)
            "petitlyrics" -> PetitLyricsClient.getLyrics(title, artist, album, durationSec)
            "musixmatch" -> MusixmatchLyricsClient.getLyrics(title, artist, album, durationSec)
            "lrclib" -> LrcLibClient.getLyrics(title, artist, album, durationSec)
            "utanet" -> UtaNetLyricsClient.getLyrics(title, artist)
            "utaten" -> UtaTenLyricsClient.getLyrics(title, artist)
            "jlyric" -> JLyricClient.getLyrics(title, artist)
            "genius" -> GeniusLyricsClient.getLyrics(title, artist)
            "ctc_align" -> fetchCtcAlignedLyrics(title, artist, durationSec)
            else -> null
        }
    }

    suspend fun searchLyrics(
        providerId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): List<NativeSearchCandidate> {
        val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        return when (providerId.lowercase()) {
            "apple_music" -> AppleMusicPaxClient.searchLyrics(title, artist, album, durationSec)
            "lyricsplus" -> LyricsPlusClient.searchLyrics(title, artist, album, durationSec)
            "better_lyrics" -> BetterLyricsClient.searchLyrics(title, artist, album, durationSec)
            "bini_lrc_red" -> BiniAndLrcRedClient.searchLyrics(title, artist, album, durationSec)
            "netease" -> NetEaseLyricsClient.searchLyrics(title, artist, album, durationSec)
            "qqmusic" -> QqMusicLyricsClient.searchLyrics(title, artist, album, durationSec)
            "kugou" -> KugouLyricsClient.searchLyrics(title, artist, album, durationSec)
            "petitlyrics" -> PetitLyricsClient.searchLyrics(title, artist, album, durationSec)
            "musixmatch" -> MusixmatchLyricsClient.searchLyrics(title, artist, album, durationSec)
            "lrclib" -> LrcLibClient.searchLyrics(title, artist, album, durationSec)
            "utanet" -> UtaNetLyricsClient.searchLyrics(title, artist, album, durationSec)
            "utaten" -> UtaTenLyricsClient.searchLyrics(title, artist, album, durationSec)
            "jlyric" -> JLyricClient.searchLyrics(title, artist, album, durationSec)
            "genius" -> GeniusLyricsClient.searchLyrics(title, artist, album, durationSec)
            "ctc_align" -> searchCtcAlignedLyrics(title, artist, album, durationSec)
            else -> emptyList()
        }
    }

    private suspend fun fetchCtcAlignedLyrics(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? = coroutineScope {
        val utatenJob = async(Dispatchers.IO) { UtaTenLyricsClient.getLyrics(title, artist) }
        val utanetJob = async(Dispatchers.IO) { UtaNetLyricsClient.getLyrics(title, artist) }
        val jlyricJob = async(Dispatchers.IO) { JLyricClient.getLyrics(title, artist) }
        val geniusJob = async(Dispatchers.IO) { GeniusLyricsClient.getLyrics(title, artist) }

        val plain = utatenJob.await()?.takeIf { it.isNotBlank() }
            ?: utanetJob.await()?.takeIf { it.isNotBlank() }
            ?: jlyricJob.await()?.takeIf { it.isNotBlank() }
            ?: geniusJob.await()?.takeIf { it.isNotBlank() }
            ?: return@coroutineScope null

        utatenJob.cancel()
        utanetJob.cancel()
        jlyricJob.cancel()
        geniusJob.cancel()

        CtcLrcGenerator.generateAutoSyncedLrc(plain, durationSec)
    }

    private suspend fun searchCtcAlignedLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
    ): List<NativeSearchCandidate> {
        val aligned = fetchCtcAlignedLyrics(title, artist, durationSec) ?: return emptyList()
        return listOf(
            NativeSearchCandidate(
                id = "ctc_align_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = "Heuristic Sync",
                syncedLyrics = aligned,
            )
        )
    }
}
