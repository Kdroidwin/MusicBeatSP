package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ABRepeatPhase { OFF, MARK_B, REPEATING }

data class ABRepeatState(
    val mediaId: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val phase: ABRepeatPhase = ABRepeatPhase.OFF,
)

/** In-memory A-B marks; never persists identifiers or resumes a loop after restart. */
object ABRepeatController {
    private val _state = MutableStateFlow(ABRepeatState())
    val state = _state.asStateFlow()

    /** Polls the active player only while a loop is armed; polling stops when the service scope ends. */
    fun observe(scope: CoroutineScope, player: () -> Player?) {
        scope.launch {
            while (isActive) {
                if (!AppSettings.abRepeatEnabled.value) {
                    clear()
                    delay(250L)
                    continue
                }
                val currentState = _state.value
                val active = player()
                val currentId = active?.currentMediaItem?.mediaId
                val end = currentState.endMs
                if (active?.isPlaying == true && currentState.phase == ABRepeatPhase.REPEATING &&
                    currentState.mediaId == currentId && end != null && active.currentPosition >= end
                ) {
                    active.seekTo(currentState.startMs ?: 0L)
                    delay(120L)
                } else {
                    clearForDifferentTrack(currentId)
                    delay(if (active?.isPlaying == true) 60L else 250L)
                }
            }
        }
    }

    @Synchronized
    fun markAt(mediaId: String, positionMs: Long) {
        val id = mediaId.takeIf { it.isNotBlank() && it.length <= 2048 } ?: return
        val position = positionMs.coerceAtLeast(0L)
        val current = _state.value
        when {
            current.mediaId == id && current.phase == ABRepeatPhase.REPEATING -> _state.value = ABRepeatState()
            current.mediaId == id && current.phase == ABRepeatPhase.MARK_B -> {
                val start = current.startMs ?: position
                if (position >= start + MIN_LOOP_MS) {
                    _state.value = current.copy(endMs = position, phase = ABRepeatPhase.REPEATING)
                } else {
                    _state.value = ABRepeatState(id, position, null, ABRepeatPhase.MARK_B)
                }
            }
            else -> _state.value = ABRepeatState(id, position, null, ABRepeatPhase.MARK_B)
        }
    }

    @Synchronized
    fun clearForDifferentTrack(mediaId: String?) {
        val state = _state.value
        if (state.phase != ABRepeatPhase.OFF && state.mediaId != mediaId) _state.value = ABRepeatState()
    }

    @Synchronized
    fun clear() {
        _state.value = ABRepeatState()
    }

    private const val MIN_LOOP_MS = 350L
}
