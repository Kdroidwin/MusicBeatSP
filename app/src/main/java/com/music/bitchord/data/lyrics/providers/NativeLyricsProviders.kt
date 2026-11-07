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
        "joysound",
        "genius",
        "ctc_align",
        "ctc_align_character",
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
            "joysound" -> JoysoundLyricsClient.getLyrics(title, artist)
            "genius" -> GeniusLyricsClient.getLyrics(title, artist)
            "ctc_align" -> fetchCtcAlignedLyrics(title, artist, durationSec, characterByCharacter = false)
            "ctc_align_character" -> fetchCtcAlignedLyrics(title, artist, durationSec, characterByCharacter = true)
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
            "joysound" -> JoysoundLyricsClient.searchLyrics(title, artist, album, durationSec)
            "genius" -> GeniusLyricsClient.searchLyrics(title, artist, album, durationSec)
            "ctc_align" -> searchCtcAlignedLyrics(title, artist, album, durationSec, characterByCharacter = false)
            "ctc_align_character" -> searchCtcAlignedLyrics(title, artist, album, durationSec, characterByCharacter = true)
            else -> emptyList()
        }
    }

    private suspend fun fetchCtcAlignedLyrics(
        title: String,
        artist: String,
        durationSec: Int,
        characterByCharacter: Boolean,
    ): String? {
        val plain = fetchHeuristicSourceLyrics(title, artist) ?: return null

        return if (characterByCharacter) {
            CtcLrcGenerator.generateCharacterSyncedLrc(plain, durationSec)
        } else {
            CtcLrcGenerator.generateAutoSyncedLrc(plain, durationSec)
        }
    }

    /**
     * The same best plain-lyrics candidate used by the Heuristic Sync sources.
     * CTC Forced Alignment uses this when its embedded lyric field is empty.
     */
    suspend fun fetchHeuristicSyncPlainLyrics(
        title: String,
        artist: String,
    ): String? {
        return fetchHeuristicSourceLyrics(title, artist)
    }

    private suspend fun fetchHeuristicSourceLyrics(
        title: String,
        artist: String,
    ): String? = coroutineScope {
        val utatenJob = async(Dispatchers.IO) { UtaTenLyricsClient.getLyrics(title, artist) }
        val utanetJob = async(Dispatchers.IO) { UtaNetLyricsClient.getLyrics(title, artist) }
        val jlyricJob = async(Dispatchers.IO) { JLyricClient.getLyrics(title, artist) }
        val joysoundJob = async(Dispatchers.IO) { JoysoundLyricsClient.getLyrics(title, artist) }
        val geniusJob = async(Dispatchers.IO) { GeniusLyricsClient.getLyrics(title, artist) }

        val candidates = listOf(
            utatenJob.await(),
            utanetJob.await(),
            jlyricJob.await(),
            joysoundJob.await(),
            geniusJob.await(),
        ).filterNotNull().filter(String::isNotBlank)
        val plain = candidates.reduceOrNull { best, candidate ->
            val bestLineCount = best.lineSequence().count(String::isNotBlank)
            val candidateLineCount = candidate.lineSequence().count(String::isNotBlank)
            if (candidateLineCount > bestLineCount ||
                candidateLineCount == bestLineCount && candidate.length > best.length
            ) candidate else best
        } ?: return@coroutineScope null
        plain
    }

    private suspend fun searchCtcAlignedLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int,
        characterByCharacter: Boolean,
    ): List<NativeSearchCandidate> {
        val aligned = fetchCtcAlignedLyrics(title, artist, durationSec, characterByCharacter) ?: return emptyList()
        return listOf(
            NativeSearchCandidate(
                id = "ctc_align_${title.hashCode()}",
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                provider = if (characterByCharacter) "Heuristic Sync · 1文字" else "Heuristic Sync",
                syncedLyrics = aligned,
            )
        )
    }
}
