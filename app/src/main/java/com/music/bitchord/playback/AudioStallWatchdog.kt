package com.music.bitchord.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlin.math.abs

/**
 * Gets playback out of an audio output that has stopped taking data.
 *
 * ## What it is for
 *
 * On an external output (a USB DAC, Bluetooth) the AudioTrack can stop
 * draining while the decoder still has plenty buffered. Media3 1.11 then
 * flips READY ⇄ BUFFERING about every 0.13 s forever: while playing, its
 * position estimate runs up to what was written and calls the sink empty;
 * paused, the estimate falls back behind it and calls it full again. Its own
 * stuck-track reset only runs on Android 10 and below. A seek (or a track
 * change) ends it at once, because that flushes the sink and opens a fresh
 * AudioTrack — see docs/playback_stall_fix_plan.md.
 *
 * So: when the listener wants sound, READY → BUFFERING happens at least
 * [FLIPS] times inside [WINDOW_MS], and the position has barely moved over
 * them, seek to where it is. Real buffering does not flip back to READY every
 * few milliseconds, and real playback moves the position, so neither trips it.
 *
 * Bounded: once per [COOLDOWN_MS], at most [MAX_PER_TRACK] times a track.
 * An output that stays dead after that is left alone rather than seeked
 * forever.
 */
class AudioStallWatchdog(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit,
) {
    private val flips = ArrayDeque<Pair<Long, Long>>() // (elapsedRealtime, positionMs) at each READY → BUFFERING
    private var lastState = Player.STATE_IDLE
    private var lastRecoveryAt = Long.MIN_VALUE
    private var recoveriesThisTrack = 0
    private var trackId: String? = null

    /**
     * Call from onPlaybackStateChanged with the player it fired on.
     * [busy] is true while something else owns the player's position (a
     * crossfade into the standby player): the watchdog then only watches.
     */
    fun onPlaybackStateChanged(player: Player, state: Int, busy: Boolean) {
        val id = player.currentMediaItem?.mediaId
        if (id != trackId) {
            trackId = id
            recoveriesThisTrack = 0
            flips.clear()
        }
        val previous = lastState
        lastState = state
        if (!(previous == Player.STATE_READY && state == Player.STATE_BUFFERING)) return
        if (!player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
            flips.clear()
            return
        }
        val now = clock()
        flips.addLast(now to player.currentPosition)
        while (flips.isNotEmpty() && now - flips.first().first > WINDOW_MS) flips.removeFirst()
        if (flips.size < FLIPS) return

        val delta = player.currentPosition - flips.first().second
        if (abs(delta) > STILL_MS) {
            flips.clear()
            return
        }
        // The end of a track buffers too; nothing is stuck there.
        val duration = player.duration
        if (duration > 0 && duration - player.currentPosition < 1_500) return
        if (busy) return
        if (recoveriesThisTrack > 0 && now - lastRecoveryAt < COOLDOWN_MS) return
        if (recoveriesThisTrack >= MAX_PER_TRACK) {
            if (recoveriesThisTrack == MAX_PER_TRACK) {
                log("audio output stalled again; leaving it after $MAX_PER_TRACK recoveries on this track")
                recoveriesThisTrack++
            }
            return
        }
        lastRecoveryAt = now
        recoveriesThisTrack++
        val at = player.currentPosition.coerceAtLeast(0L)
        val windowElapsed = now - flips.first().first
        val flipCount = flips.size
        flips.clear()
        if (recoveriesThisTrack == 1) {
            // Media3 ignores seeks whose millisecond position equals the current
            // playback position; offset by 1ms so ExoPlayer actually flushes and
            // re-opens the sink.
            val target = if (at > 0L) at - 1L else 1L
            log(
                "audio output stalled ($flipCount ready/buffering flips in ${windowElapsed}ms, " +
                    "position delta ${delta}ms): re-opening the output at ${target}ms",
            )
            player.seekTo(player.currentMediaItemIndex, target)
        } else {
            // A second stall after a flush-seek points to a wedged source or
            // decoder; stop and prepare again at the same position to rebuild it.
            log(
                "audio output stalled again ($flipCount ready/buffering flips in ${windowElapsed}ms, " +
                    "position delta ${delta}ms): re-preparing source at ${at}ms",
            )
            val index = player.currentMediaItemIndex
            player.stop()
            player.seekTo(index, at)
            player.prepare()
        }
    }

    private companion object {
        const val FLIPS = 4
        const val WINDOW_MS = 1_500L
        const val STILL_MS = 300L
        const val COOLDOWN_MS = 10_000L
        const val MAX_PER_TRACK = 3
    }
}
