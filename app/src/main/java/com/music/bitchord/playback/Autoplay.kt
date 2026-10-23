package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Finds the YouTube id that should seed AutoPlay for a song. Module tracks do not
 * carry YouTube ids, so they are matched on YouTube before the radio request.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) {
        // A content URI or a MediaStore id is not a YouTube id. Avoid turning
        // local playback into an unrelated or malformed online lookup.
        if (song.isLiveRadio || song.isExternalPreview ||
            song.videoId.startsWith("content://") || song.videoId.startsWith("file://")
        ) return null
        return song.videoId.takeIf(YOUTUBE_VIDEO_ID::matches)
    }
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
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
): Result<List<Song>> {
    if (limit <= 0 || !AppSettings.youtubeMusicNetworkAllowed) {
        return Result.success(emptyList())
    }
    return try {
        val seedId = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
        val candidates = YtMusicRepository.radio(seedId).getOrElse { return Result.failure(it) }
        Result.success(QueueBuilder.extend(existing, candidates, limit))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
