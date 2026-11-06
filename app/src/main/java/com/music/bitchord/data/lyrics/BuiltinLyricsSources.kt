package com.music.bitchord.data.lyrics

/**
 * Native lyric providers carried by the current BitChord upstream.
 *
 * These sources are part of the APK and do not execute downloaded scripts.
 * The two providers upstream marks hidden (SimpMusic and Megalobiz) are kept
 * in the source tree but deliberately are not offered or queried here.
 */
internal object BuiltinLyricsSources {
    val sources: List<LyricsSource> = listOf(
        LyricsSource("petit_lyrics", "PetitLyrics", "プチリリ — 日本語曲・アニソンに強い文字単位の同期歌詞", wordSynced = true),
        LyricsSource("lrc_red", "lrc.red", "Apple timings matched by recording", wordSynced = true),
        LyricsSource("bini_lyrics", "BiniLyrics", "Apple timings matched by recording", wordSynced = true),
        LyricsSource("better_lyrics", "BetterLyrics", "Apple Music word timings", wordSynced = true),
        LyricsSource("better_lyrics_portato", "BetterLyrics Portato", "QQ Music karaoke timings", wordSynced = true),
        LyricsSource("qq_music", "QQ Music", "Line- and word-synced QQ Music lyrics", wordSynced = true),
        LyricsSource("paxsenix", "PaxSenix", "Apple Music timings through PaxSenix", wordSynced = true),
        LyricsSource("paxsenix_spotify", "PaxSenix: Spotify", "Spotify lyrics; API key required"),
        LyricsSource("paxsenix_musixmatch", "PaxSenix: Musixmatch", "Musixmatch timings; API key required", wordSynced = true),
        LyricsSource("lyricsplus", "LyricsPlus", "Syllable-timed lyrics from community mirrors", wordSynced = true),
        LyricsSource("unison", "Unison", "Community-contributed lyrics", wordSynced = true),
        LyricsSource("youtube_transcript", "YouTube captions", "Timed captions for the playing video"),
        LyricsSource("youtube_music", "YouTube Music", "Lyrics from the playing video's Lyrics tab"),
        // These three IDs replace the older script-backed providers while
        // preserving existing settings and source-order entries.
        LyricsSource("kugou", "KuGou", "Synced lyrics from KuGou's catalogue"),
        LyricsSource("kugeci", "Kugeci", "Synced lyrics from 酷歌词"),
        LyricsSource("followlyrics", "Followlyrics", "Line-synced lyrics from Follow Lyrics"),
        LyricsSource("netease", "NetEase Cloud Music", "Line- and word-synced lyrics", wordSynced = true),
        LyricsSource("lrclib", "LRCLIB", "Open, line-synced lyrics database"),
        LyricsSource("musixmatch", "Musixmatch", "Line-synced lyrics from Musixmatch"),
        LyricsSource("jlyric", "J-Lyric", "Japanese plain-text lyrics from J-Lyric"),
        LyricsSource("utaten", "UtaTen", "Japanese lyrics with native furigana support"),
        LyricsSource("utanet", "歌ネット (Uta-Net)", "Japanese lyrics from Uta-Net"),
        LyricsSource("joysound", "JOYSOUND", "Japanese plain-text lyrics from JOYSOUND"),
        LyricsSource("ctc_align", "Heuristic Sync", "Estimated timing generated from plain lyrics"),
        LyricsSource("genius", "Genius", "Plain-text fallback from Genius"),
    )

    val nativeIds: Set<String> = sources.mapTo(mutableSetOf()) { it.id }
    val youtubeIds: Set<String> = setOf("youtube_transcript", "youtube_music")

    suspend fun fetch(
        id: String,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? = when (id) {
        "petit_lyrics" -> PetitLyrics.lyrics(title, artist, durationMs, album)
        "lrc_red" -> LrcRed.lyrics(title, artist, durationMs)
        "bini_lyrics" -> BiniLyrics.lyrics(title, artist, durationMs, album)?.lines
        "better_lyrics" -> BetterLyrics.lyrics(title, artist, durationMs, album)
        "better_lyrics_portato" -> BetterLyrics.portato(title, artist, durationMs, album)
        "qq_music" -> QQMusicLyrics.lyrics(title, artist, durationMs, album)
        "paxsenix" -> PaxSenix.lyrics(title, artist, durationMs, album)
        "paxsenix_spotify" -> PaxSenix.spotifyLyrics(title, artist, durationMs)
        "paxsenix_musixmatch" -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
        "lyricsplus" -> LyricsPlus.lyrics(title, artist, durationMs, album)
        "unison" -> Unison.lyrics(title, artist, durationMs, album)
        "youtube_transcript" -> YouTubeTranscriptLyrics.lyrics(videoId)
        "youtube_music" -> YouTubeMusicLyrics.lyrics(videoId)
        "kugou" -> KuGou.lyrics(title, artist, durationMs, album)
        "kugeci" -> KugeciLyrics.lyrics(title, artist, durationMs)
        "followlyrics" -> FollowLyrics.lyrics(title, artist, durationMs)
        "netease" -> NetEaseLyrics.lyrics(title, artist, durationMs, album)
        "lrclib" -> LrcLib.lyrics(title, artist, durationMs)
        "musixmatch" -> Musixmatch.lyrics(title, artist, durationMs)
        "jlyric", "utaten", "utanet", "joysound", "ctc_align" ->
            com.music.bitchord.data.lyrics.providers.NativeLyricsProviders
                .getLyrics(id, title, artist, album, durationMs)
                ?.let(ProviderLyrics::parse)
        "genius" -> Genius.lyrics(title, artist)
        else -> null
    }

    /** Search routes ported from Tinque. Playback keeps the existing provider implementations. */
    fun nativeSearchId(id: String): String? = when (id) {
        "netease", "kugou", "musixmatch", "jlyric", "utaten", "utanet", "joysound", "ctc_align" -> id
        "petit_lyrics" -> "petitlyrics"
        else -> null
    }

    suspend fun search(
        id: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): List<com.music.bitchord.data.lyrics.providers.NativeSearchCandidate>? {
        val nativeId = nativeSearchId(id) ?: return null
        return com.music.bitchord.data.lyrics.providers.NativeLyricsProviders.searchLyrics(
            providerId = nativeId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        )
    }
}
