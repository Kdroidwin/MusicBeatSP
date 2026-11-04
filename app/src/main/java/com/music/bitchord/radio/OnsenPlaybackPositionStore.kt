package com.music.bitchord.radio

import android.content.Context

/** Keeps the last position of each on-demand Onsen episode on this device. */
internal object OnsenPlaybackPositionStore {
    private const val PREFS = "onsen_playback_positions"
    private const val KEY_PREFIX = "episode_"

    fun get(context: Context, episodeId: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PREFIX + episodeId, 0L)
            .coerceAtLeast(0L)

    fun save(context: Context, episodeId: String, positionMs: Long) {
        if (episodeId.isBlank() || positionMs < 0L) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PREFIX + episodeId, positionMs)
            .apply()
    }
}
