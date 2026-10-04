package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.model.Song
import java.security.MessageDigest

/** Per-track resume bookmarks for long-form audio, independent of the saved queue snapshot. */
object LongTrackResumeStore {
    const val MIN_DURATION_MS = 15 * 60 * 1000L
    private const val PREFS_NAME = "bitchord_long_track_resume"
    private const val KEY_PREFIX = "position_"
    private const val END_CLEAR_MARGIN_MS = 5_000L

    fun positionMs(context: Context, song: Song): Long = prefs(context).getLong(key(song), 0L).coerceAtLeast(0L)

    fun save(context: Context, song: Song, durationMs: Long, positionMs: Long) {
        if (durationMs < MIN_DURATION_MS) return
        val normalizedPosition = positionMs.coerceIn(0L, durationMs)
        val editor = prefs(context).edit()
        if (normalizedPosition >= durationMs - END_CLEAR_MARGIN_MS) {
            editor.remove(key(song))
        } else if (normalizedPosition > 0L) {
            editor.putLong(key(song), normalizedPosition)
        }
        editor.apply()
    }

    fun clear(context: Context, song: Song) {
        prefs(context).edit().remove(key(song)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(song: Song): String {
        val stableId = song.localUri?.takeIf(String::isNotBlank)
            ?: song.localPath?.takeIf(String::isNotBlank)
            ?: song.videoId
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return KEY_PREFIX + digest
    }
}
