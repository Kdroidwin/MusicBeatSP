package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/**
 * Equal-power crossfade using the service's two identically configured players.
 * The idle player is prepared silently, then the MediaSession changes owners at
 * the midpoint; the old player is released from its queue only after the tail
 * has faded out.
 */
@UnstableApi
class CrossfadeController(
    private val scope: CoroutineScope,
    private val activePlayer: () -> ExoPlayer?,
    private val standbyPlayer: () -> ExoPlayer?,
    private val fadeSeconds: () -> Int,
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    private val onPrepareIncoming: (MediaItem) -> Unit,
    private val canCrossfade: (MediaItem) -> Boolean,
) {
    private enum class Phase { ARMING, FADING }

    private data class Transition(
        val outgoing: ExoPlayer,
        val incoming: ExoPlayer,
        val outgoingId: String,
        val incomingId: String,
        val queueIds: List<String>,
        val outgoingIndex: Int,
        val requestedFadeMs: Long,
        var phase: Phase = Phase.ARMING,
        var fadeStartPositionMs: Long = 0L,
        var fadeDurationMs: Long = 0L,
        var handedOff: Boolean = false,
        var lastPositionMs: Long = 0L,
    )

    private var transition: Transition? = null
    private var watcher: Job? = null

    fun start() {
        if (watcher?.isActive == true) return
        watcher = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                runCatching { tick() }
                delay(TICK_MS)
            }
        }
    }

    fun isTransitioning(): Boolean = transition != null

    /** Cancels an overlap after a manual seek/skip or a queue change. */
    fun cancelTransition() {
        val current = transition ?: return
        transition = null
        runCatching {
            val sessionPlayer = if (current.handedOff) current.incoming else current.outgoing
            val inactivePlayer = if (current.handedOff) current.outgoing else current.incoming
            sessionPlayer.volume = 1f
            inactivePlayer.playWhenReady = false
            inactivePlayer.stop()
            inactivePlayer.clearMediaItems()
            inactivePlayer.volume = 1f
        }
    }

    fun release() {
        watcher?.cancel()
        watcher = null
        cancelTransition()
    }

    private fun tick() {
        val current = transition
        if (current == null) {
            maybeArm()
            return
        }
        if (current.handedOff) {
            if (activePlayer() !== current.incoming ||
                current.incoming.currentMediaItem?.mediaId != current.incomingId
            ) {
                finish(current)
                return
            }
        } else {
            val active = activePlayer()
            if (active !== current.outgoing ||
                active.currentMediaItem?.mediaId != current.outgoingId ||
                active.currentMediaItemIndex != current.outgoingIndex ||
                queueIds(active) != current.queueIds
            ) {
                cancelTransition()
                return
            }
        }

        val session = if (current.handedOff) current.incoming else current.outgoing
        if (!session.playWhenReady) {
            current.outgoing.pause()
            current.incoming.pause()
            return
        }

        if (current.phase == Phase.ARMING) {
            val remaining = current.outgoing.duration - current.outgoing.currentPosition
            if (remaining <= READY_DEADLINE_MS) {
                cancelTransition()
                return
            }
            if (current.incoming.playbackState != Player.STATE_READY ||
                current.incoming.currentMediaItem?.mediaId != current.incomingId
            ) return

            val outDuration = current.outgoing.duration
            val inDuration = current.incoming.duration
            var fadeMs = min(current.requestedFadeMs, outDuration / 2)
            if (inDuration > 0 && inDuration != C.TIME_UNSET) {
                fadeMs = min(fadeMs, inDuration / 2)
            }
            // Preparing the incoming decoder can finish several seconds before
            // the overlap should begin. Keep it silent and ready until the
            // outgoing track reaches the actual fade window; beginning as soon
            // as it is READY would cut the old track short by up to ARM_AHEAD_MS.
            if (remaining > fadeMs) return
            fadeMs = min(fadeMs, remaining)
            if (fadeMs < MIN_FADE_MS) {
                cancelTransition()
                return
            }
            current.fadeDurationMs = fadeMs
            current.fadeStartPositionMs = current.outgoing.currentPosition
            current.lastPositionMs = current.fadeStartPositionMs
            current.phase = Phase.FADING
            current.incoming.volume = 0f
            current.incoming.playWhenReady = true
        }

        // If the incoming decoder re-buffers after the midpoint, hold the old
        // player at its current position and level until it is ready again.
        // This avoids fading the only audible track down while the next one is
        // waiting for bytes.
        if (current.incoming.playbackState == Player.STATE_BUFFERING) {
            current.outgoing.pause()
            return
        }
        if (current.incoming.playbackState != Player.STATE_READY) {
            cancelTransition()
            return
        }
        current.outgoing.playWhenReady = true
        current.incoming.playWhenReady = true

        val position = current.outgoing.currentPosition
        if (position + SEEK_BACK_ABORT_MS < current.lastPositionMs) {
            cancelTransition()
            return
        }
        current.lastPositionMs = position
        val progress = ((position - current.fadeStartPositionMs).toFloat() /
            current.fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        current.outgoing.volume = cos(progress * HALF_PI).coerceIn(0f, 1f)
        current.incoming.volume = sin(progress * HALF_PI).coerceIn(0f, 1f)

        if (!current.handedOff && progress >= HANDOFF_AT) {
            current.handedOff = true
            try {
                onHandoff(current.outgoing, current.incoming)
            } catch (_: Exception) {
                current.handedOff = false
                cancelTransition()
                return
            }
        }
        if (progress >= 1f) finish(current)
    }

    private fun maybeArm() {
        val seconds = fadeSeconds().coerceIn(0, MAX_FADE_SECONDS)
        if (seconds == 0) return
        val outgoing = activePlayer() ?: return
        val incoming = standbyPlayer() ?: return
        if (outgoing === incoming || !outgoing.isPlaying || outgoing.shuffleModeEnabled ||
            outgoing.repeatMode == Player.REPEAT_MODE_ONE || outgoing.mediaItemCount < 2
        ) return
        if (incoming.mediaItemCount > 0 || incoming.playbackState != Player.STATE_IDLE) return

        val item = outgoing.currentMediaItem ?: return
        if (!canCrossfade(item)) return
        val duration = outgoing.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return
        val fadeMs = min(seconds * 1000L, duration / 2)
        if (fadeMs < MIN_FADE_MS) return
        val remaining = duration - outgoing.currentPosition
        if (remaining > fadeMs + ARM_AHEAD_MS) return
        if (remaining <= READY_DEADLINE_MS) return

        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex !in 0 until outgoing.mediaItemCount) return
        val items = List(outgoing.mediaItemCount) { outgoing.getMediaItemAt(it) }
        val incomingItem = items.getOrNull(nextIndex) ?: return
        val snapshot = queueIds(outgoing)

        incoming.volume = 0f
        incoming.repeatMode = outgoing.repeatMode
        incoming.shuffleModeEnabled = false
        incoming.playbackParameters = outgoing.playbackParameters
        incoming.skipSilenceEnabled = outgoing.skipSilenceEnabled
        incoming.playWhenReady = false
        incoming.stop()
        incoming.clearMediaItems()
        incoming.setMediaItems(items, nextIndex, 0L)
        incoming.playlistMetadata = outgoing.playlistMetadata
        incoming.prepare()

        transition = Transition(
            outgoing = outgoing,
            incoming = incoming,
            outgoingId = item.mediaId,
            incomingId = incomingItem.mediaId,
            queueIds = snapshot,
            outgoingIndex = outgoing.currentMediaItemIndex,
            requestedFadeMs = fadeMs,
        )
        onPrepareIncoming(incomingItem)
    }

    private fun finish(current: Transition) {
        if (transition !== current) return
        transition = null
        current.incoming.volume = 1f
        current.outgoing.volume = 1f
        current.outgoing.playWhenReady = false
        current.outgoing.stop()
        current.outgoing.clearMediaItems()
    }

    private fun queueIds(player: ExoPlayer): List<String> =
        List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

    private companion object {
        const val MAX_FADE_SECONDS = 12
        const val MIN_FADE_MS = 250L
        const val ARM_AHEAD_MS = 4_000L
        const val READY_DEADLINE_MS = 350L
        const val SEEK_BACK_ABORT_MS = 500L
        const val HANDOFF_AT = 0.5f
        const val TICK_MS = 40L
        const val HALF_PI = Math.PI.toFloat() / 2f
    }
}
