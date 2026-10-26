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
        LyricsSource("lrc_red", "lrc.red", "Apple timings matched by recording", wordSynced = true),
        LyricsSource("bini_lyrics", "BiniLyrics", "Apple timings matched by recording", wordSynced = true),
        LyricsSource("better_lyrics", "BetterLyrics", "Apple Music word timings", wordSynced = true),
        LyricsSource("better_lyrics_portato", "BetterLyrics Portato", "QQ Music karaoke timings", wordSynced = true),
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
        LyricsSource("lrclib", "LRCLIB", "Open, line-synced lyrics database"),
        LyricsSource("musixmatch", "Musixmatch", "Line-synced lyrics from Musixmatch"),
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
        "lrc_red" -> LrcRed.lyrics(title, artist, durationMs)
        "bini_lyrics" -> BiniLyrics.lyrics(title, artist, durationMs, album)?.lines
        "better_lyrics" -> BetterLyrics.lyrics(title, artist, durationMs, album)
        "better_lyrics_portato" -> BetterLyrics.portato(title, artist, durationMs, album)
        "paxsenix" -> PaxSenix.lyrics(title, artist, durationMs, album)
        "paxsenix_spotify" -> PaxSenix.spotifyLyrics(title, artist, durationMs)
        "paxsenix_musixmatch" -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
        "lyricsplus" -> LyricsPlus.lyrics(title, artist, durationMs, album)
        "unison" -> Unison.lyrics(title, artist, durationMs, album)
        "youtube_transcript" -> YouTubeTranscriptLyrics.lyrics(videoId)
        "youtube_music" -> YouTubeMusicLyrics.lyrics(videoId)
        "kugou" -> KuGou.lyrics(title, artist, durationMs, album)
        "lrclib" -> LrcLib.lyrics(title, artist, durationMs)
        "musixmatch" -> Musixmatch.lyrics(title, artist, durationMs)
        "genius" -> Genius.lyrics(title, artist)
        else -> null
    }
}
