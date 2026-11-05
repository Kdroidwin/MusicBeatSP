package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AutoplaySource
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.random.Random

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Finds the YouTube id that should seed AutoPlay for a song. A YouTube track is
 * its own seed; anything else — a file on the device, a module track — is
 * matched on YouTube Music by its title and artist first.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    val isYouTubeTrack = SourceRegistry.parseTrackKey(song.videoId) == null &&
        song.localUri == null && song.localPath == null && "://" !in song.videoId
    if (isYouTubeTrack) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch, from YouTube Music's
 * radio for the song or from the songs on the device. The playback service is
 * the only caller for AutoPlay itself; the player UI's explicit radio start
 * uses the YouTube Music path for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
    source: AutoplaySource = AutoplaySource.YOUTUBE_MUSIC,
    context: Context? = null,
): Result<List<Song>> {
    val extra = when (source) {
        AutoplaySource.OFF -> return Result.success(emptyList())
        AutoplaySource.YOUTUBE_MUSIC -> {
            val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
            val related = YtMusicRepository.radio(seed).getOrElse { return Result.failure(it) }
            QueueBuilder.extend(existing, related, limit)
        }
        AutoplaySource.DEVICE -> {
            val library = context?.let { DeviceLibraryCache.songs(it) }.orEmpty()
            QueueBuilder.extend(existing, rankDeviceCandidates(seedSong, library), limit)
        }
    }
    if (extra.isEmpty()) return Result.success(emptyList())
    return Result.success(extra.map {
        it.copy(fromAutoplay = true, radioName = seedSong.radioName)
    })
}

/**
 * The device's songs, closest to [seed] first: the same artist, then the same
 * album artist or folder, then the same album; everything else after, so the
 * queue keeps going once the close ones run out. A little randomness within
 * each band, so the same song does not always lead to the same next ones.
 * [QueueBuilder.extend] then drops what is already queued and keeps any one
 * artist from taking over.
 */
internal fun rankDeviceCandidates(seed: Song, library: List<Song>): List<Song> {
    val seedArtists = QueueBuilder.artistSet(seed.artist)
    val seedAlbumArtist = seed.albumArtist?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val seedFolder = seed.localPath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
    val seedAlbum = seed.albumName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return library
        .asSequence()
        .filter { it.videoId != seed.videoId && !it.isExternalPreview }
        .map { song ->
            var score = 0.0
            if (QueueBuilder.artistSet(song.artist).any { it in seedArtists }) score += 4.0
            if (seedAlbumArtist != null && song.albumArtist?.trim()?.lowercase() == seedAlbumArtist) score += 2.0
            if (seedFolder != null && song.localPath?.substringBeforeLast('/', "") == seedFolder) score += 2.0
            if (seedAlbum != null && song.albumName?.trim()?.lowercase() == seedAlbum) score += 1.0
            song to score + Random.nextDouble(0.0, 1.5)
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .toList()
}

/**
 * The device library for AutoPlay, scanned at most every few minutes rather
 * than on every track change: a full MediaStore pass is the slow part.
 */
private object DeviceLibraryCache {
    private const val MAX_AGE_MS = 5 * 60_000L
    private var cached: List<Song> = emptyList()
    private var scannedAt = 0L

    suspend fun songs(context: Context): List<Song> {
        val now = System.currentTimeMillis()
        if (cached.isEmpty() || now - scannedAt > MAX_AGE_MS) {
            cached = runCatching { LocalMediaRepository.getLocalMusic(context.applicationContext) }
                .getOrDefault(cached)
            scannedAt = now
        }
        return cached
    }
}
