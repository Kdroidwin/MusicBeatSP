package com.music.bitchord.feature.localsongactions.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.localsongactions.domain.model.LocalPlayStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages local song playback and skip statistics.
 */
object LocalPlayStatsStore {

    private const val PREFS_NAME = "bitchord_local_play_stats"
    private const val KEY_PLAY_COUNT = "play_"
    private const val KEY_SKIP_COUNT = "skip_"
    private const val KEY_LAST_PLAYED = "last_"
    private const val KEY_SONG_VIDEO_ID = "song_video_id_"
    private const val KEY_SONG_TITLE = "song_title_"
    private const val KEY_SONG_ARTIST = "song_artist_"
    private const val KEY_SONG_THUMBNAIL = "song_thumbnail_"
    private const val KEY_SONG_DURATION = "song_duration_"
    private const val KEY_SONG_ARTIST_ID = "song_artist_id_"
    private const val KEY_SONG_ALBUM_ID = "song_album_id_"
    private const val KEY_SONG_ALBUM_NAME = "song_album_name_"
    private const val KEY_SONG_IS_VIDEO = "song_is_video_"
    private const val KEY_SONG_IS_VIDEO_ORIGIN = "song_is_video_origin_"

    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    private fun notifyChanged() {
        _revision.update { it + 1L }
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getStats(context: Context, songId: String): LocalPlayStats {
        return snapshot(context)[songId] ?: LocalPlayStats.Empty
    }

    /**
     * Reads both current and historical identifiers for a local track.
     * Persisted stats may be keyed by videoId while the library now prefers
     * localUri; using both keeps existing play history visible.
     */
    fun getStats(context: Context, song: Song): LocalPlayStats {
        return getStats(song, snapshot(context))
    }

    /** One consistent read for the entire library instead of per-row preference reads. */
    fun snapshot(context: Context): Map<String, LocalPlayStats> {
        val values = getPrefs(context).all
        val ids = values.keys.mapNotNull { key ->
            when {
                key.startsWith(KEY_PLAY_COUNT) -> key.removePrefix(KEY_PLAY_COUNT)
                key.startsWith(KEY_SKIP_COUNT) -> key.removePrefix(KEY_SKIP_COUNT)
                key.startsWith(KEY_LAST_PLAYED) -> key.removePrefix(KEY_LAST_PLAYED)
                else -> null
            }.takeIf { !it.isNullOrBlank() }
        }.toSet()
        return ids.associateWith { id ->
            LocalPlayStats(
                playedCount = values[KEY_PLAY_COUNT + id].asSafeInt(),
                skippedCount = values[KEY_SKIP_COUNT + id].asSafeInt(),
                lastPlayedTimestamp = values[KEY_LAST_PLAYED + id].asSafeLong(),
                songVideoId = values[KEY_SONG_VIDEO_ID + id].asSafeString(),
                songTitle = values[KEY_SONG_TITLE + id].asSafeString(),
                songArtist = values[KEY_SONG_ARTIST + id].asSafeString(),
                songThumbnailUrl = values[KEY_SONG_THUMBNAIL + id].asSafeString(),
                songDurationText = values[KEY_SONG_DURATION + id].asSafeString(),
                songArtistId = values[KEY_SONG_ARTIST_ID + id].asSafeString(),
                songAlbumId = values[KEY_SONG_ALBUM_ID + id].asSafeString(),
                songAlbumName = values[KEY_SONG_ALBUM_NAME + id].asSafeString(),
                songIsVideo = values[KEY_SONG_IS_VIDEO + id].asSafeBoolean(),
                songIsVideoOrigin = values[KEY_SONG_IS_VIDEO_ORIGIN + id].asSafeBoolean(),
            )
        }
    }

    fun getStats(song: Song, snapshot: Map<String, LocalPlayStats>): LocalPlayStats {
        val ids = listOfNotNull(song.localUri, song.videoId, song.localPath)
            .filter(String::isNotBlank)
            .distinct()
        val matches = ids.mapNotNull(snapshot::get)
        return LocalPlayStats(
            playedCount = matches.maxOfOrNull { it.playedCount } ?: 0,
            skippedCount = matches.maxOfOrNull { it.skippedCount } ?: 0,
            lastPlayedTimestamp = matches.maxOfOrNull { it.lastPlayedTimestamp } ?: 0L,
        )
    }

    fun recordPlay(context: Context, songId: String): LocalPlayStats {
        return recordPlay(context, listOf(songId), null)
    }

    /**
     * Records one play under every identifier a library row can use for this
     * track. Playback can see a downloaded `content://` URI while the library
     * row still identifies the same song by its catalogue id (or original
     * local URI). Writing the same aggregate to each alias keeps both views
     * attached to one history entry; [getStats] reads the maximum, so aliases
     * never multiply the count.
     */
    fun recordPlay(context: Context, song: Song): LocalPlayStats =
        recordPlay(
            context,
            listOfNotNull(song.localUri, song.videoId, song.localPath)
                .filter(String::isNotBlank)
                .distinct(),
            song,
        )

    @Synchronized
    private fun recordPlay(context: Context, songIds: List<String>, song: Song?): LocalPlayStats {
        val ids = songIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return LocalPlayStats.Empty
        val prefs = getPrefs(context)
        // A track may already have history under one alias from an older app
        // version. Advance from the largest count, then bring all aliases into
        // sync so switching between a stream URI and a local URI cannot make a
        // play count appear to go backwards or disappear from the library.
        val existing = snapshot(context)
        val currentPlay = (ids.maxOfOrNull { existing[it]?.playedCount ?: 0 } ?: 0)
            .coerceAtMost(Int.MAX_VALUE - 1) + 1
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        ids.forEach { id ->
            editor.putInt(KEY_PLAY_COUNT + id, currentPlay)
            editor.putLong(KEY_LAST_PLAYED + id, now)
            song?.let { track ->
                // Store the source id and display fields beside each alias so
                // the library can recover one row even when the source track
                // (for example a YouTube Music playlist video) is not in the
                // device's MediaStore song list. Keep already-known optional
                // metadata when a later player round-trip omits it.
                editor.putString(KEY_SONG_VIDEO_ID + id, track.videoId)
                track.title.takeIf(String::isNotBlank)?.let { editor.putString(KEY_SONG_TITLE + id, it) }
                track.artist.takeIf(String::isNotBlank)?.let { editor.putString(KEY_SONG_ARTIST + id, it) }
                track.thumbnailUrl?.takeIf(String::isNotBlank)?.let { editor.putString(KEY_SONG_THUMBNAIL + id, it) }
                track.durationText?.takeIf(String::isNotBlank)?.let { editor.putString(KEY_SONG_DURATION + id, it) }
                track.artistId?.takeIf(String::isNotBlank)?.let { editor.putString(KEY_SONG_ARTIST_ID + id, it) }
                track.albumId?.takeIf(String::isNotBlank)?.let { editor.putString(KEY_SONG_ALBUM_ID + id, it) }
                track.albumName?.takeIf(String::isNotBlank)?.let { editor.putString(KEY_SONG_ALBUM_NAME + id, it) }
                editor.putBoolean(KEY_SONG_IS_VIDEO + id, track.isVideo)
                editor.putBoolean(KEY_SONG_IS_VIDEO_ORIGIN + id, track.isVideoOrigin)
            }
        }
        // A play is recorded only once per track, after the audible-time
        // threshold. Commit this small aggregate before notifying the library:
        // an async apply could leave the UI showing it in memory while an app
        // process exit loses it from disk.
        val saved = editor.commit()
        if (!saved) {
            Log.e(TAG, "Could not persist local play stats")
            return LocalPlayStats.Empty
        }
        notifyChanged()
        val skipCount = ids.maxOfOrNull { existing[it]?.skippedCount ?: 0 } ?: 0
        return LocalPlayStats(
            playedCount = currentPlay,
            skippedCount = skipCount,
            lastPlayedTimestamp = now,
            songVideoId = song?.videoId,
            songTitle = song?.title,
            songArtist = song?.artist,
            songThumbnailUrl = song?.thumbnailUrl,
            songDurationText = song?.durationText,
            songArtistId = song?.artistId,
            songAlbumId = song?.albumId,
            songAlbumName = song?.albumName,
            songIsVideo = song?.isVideo,
            songIsVideoOrigin = song?.isVideoOrigin,
        )
    }

    @Synchronized
    fun recordSkip(context: Context, songId: String): LocalPlayStats {
        if (songId.isBlank()) return LocalPlayStats.Empty
        val prefs = getPrefs(context)
        val existing = snapshot(context)[songId] ?: LocalPlayStats.Empty
        val currentSkip = existing.skippedCount.coerceAtMost(Int.MAX_VALUE - 1) + 1
        val lastPlayed = existing.lastPlayedTimestamp
        val saved = prefs.edit()
            .putInt(KEY_SKIP_COUNT + songId, currentSkip)
            .commit()
        if (!saved) {
            Log.e(TAG, "Could not persist local skip stats")
            return LocalPlayStats(existing.playedCount, existing.skippedCount, lastPlayed)
        }
        notifyChanged()
        return LocalPlayStats(existing.playedCount, currentSkip, lastPlayed)
    }

    @Synchronized
    fun resetStats(context: Context, songId: String): LocalPlayStats {
        val prefs = getPrefs(context)
        val saved = prefs.edit()
            .remove(KEY_PLAY_COUNT + songId)
            .remove(KEY_SKIP_COUNT + songId)
            .remove(KEY_LAST_PLAYED + songId)
            .remove(KEY_SONG_VIDEO_ID + songId)
            .remove(KEY_SONG_TITLE + songId)
            .remove(KEY_SONG_ARTIST + songId)
            .remove(KEY_SONG_THUMBNAIL + songId)
            .remove(KEY_SONG_DURATION + songId)
            .remove(KEY_SONG_ARTIST_ID + songId)
            .remove(KEY_SONG_ALBUM_ID + songId)
            .remove(KEY_SONG_ALBUM_NAME + songId)
            .remove(KEY_SONG_IS_VIDEO + songId)
            .remove(KEY_SONG_IS_VIDEO_ORIGIN + songId)
            .commit()
        if (saved) notifyChanged() else Log.e(TAG, "Could not reset local play stats")
        return LocalPlayStats.Empty
    }

    fun exportStats(context: Context): Map<String, LocalPlayStats> {
        return snapshot(context)
    }

    @Synchronized
    fun importStats(context: Context, incoming: Map<String, LocalPlayStats>) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        editor.clear()
        incoming.forEach { (songId, stats) ->
            if (stats.playedCount > 0) editor.putInt(KEY_PLAY_COUNT + songId, stats.playedCount)
            if (stats.skippedCount > 0) editor.putInt(KEY_SKIP_COUNT + songId, stats.skippedCount)
            if (stats.lastPlayedTimestamp > 0L) editor.putLong(KEY_LAST_PLAYED + songId, stats.lastPlayedTimestamp)
            stats.songVideoId?.let { editor.putString(KEY_SONG_VIDEO_ID + songId, it) }
            stats.songTitle?.let { editor.putString(KEY_SONG_TITLE + songId, it) }
            stats.songArtist?.let { editor.putString(KEY_SONG_ARTIST + songId, it) }
            stats.songThumbnailUrl?.let { editor.putString(KEY_SONG_THUMBNAIL + songId, it) }
            stats.songDurationText?.let { editor.putString(KEY_SONG_DURATION + songId, it) }
            stats.songArtistId?.let { editor.putString(KEY_SONG_ARTIST_ID + songId, it) }
            stats.songAlbumId?.let { editor.putString(KEY_SONG_ALBUM_ID + songId, it) }
            stats.songAlbumName?.let { editor.putString(KEY_SONG_ALBUM_NAME + songId, it) }
            stats.songIsVideo?.let { editor.putBoolean(KEY_SONG_IS_VIDEO + songId, it) }
            stats.songIsVideoOrigin?.let { editor.putBoolean(KEY_SONG_IS_VIDEO_ORIGIN + songId, it) }
        }
        if (editor.commit()) {
            notifyChanged()
        } else {
            Log.e(TAG, "Could not persist imported local play stats")
        }
    }

    private const val TAG = "LocalPlayStats"
}

private fun Any?.asSafeInt(): Int = when (this) {
    is Number -> toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    is String -> toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    else -> 0
}

private fun Any?.asSafeLong(): Long = when (this) {
    is Number -> toLong().coerceAtLeast(0L)
    is String -> toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    else -> 0L
}

private fun Any?.asSafeString(): String? = this as? String

private fun Any?.asSafeBoolean(): Boolean? = this as? Boolean
