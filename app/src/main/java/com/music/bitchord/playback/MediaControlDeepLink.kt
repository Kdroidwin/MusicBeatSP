package com.music.bitchord.playback

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Destinations opened by the configurable secondary Android media-control button. */
enum class MediaControlDestination { PLAYLISTS, LYRICS, QUEUE }

/** Bridges a notification action into the Compose navigation/player state. */
object MediaControlDeepLink {
    const val EXTRA_DESTINATION = "bitchord.mediaControlDestination"

    private val _pending = MutableStateFlow<MediaControlDestination?>(null)
    val pending: StateFlow<MediaControlDestination?> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        val value = intent?.let { runCatching { it.getStringExtra(EXTRA_DESTINATION) }.getOrNull() } ?: return false
        val destination = runCatching { MediaControlDestination.valueOf(value) }.getOrNull() ?: return false
        runCatching { intent.removeExtra(EXTRA_DESTINATION) }
        _pending.value = destination
        return true
    }

    fun handled() {
        _pending.value = null
    }
}
