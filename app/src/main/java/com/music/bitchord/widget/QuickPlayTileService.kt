package com.music.bitchord.widget

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.bitchord.MainActivity
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.toMediaItem
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** One tap resumes the saved queue in the background; the next stops it and closes the UI task. */
class QuickPlayTileService : TileService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        renderTile(QuickPlayTileState.isActive(this))
    }

    override fun onClick() {
        super.onClick()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller == null) {
                    MediaController.releaseFuture(future)
                    QuickPlayTileState.setActive(this, false)
                    return@addListener
                }
                if (controller.playWhenReady) {
                    stopPlayback(controller, future)
                } else if (controller.mediaItemCount > 0) {
                    resumeQueue(controller)
                    MediaController.releaseFuture(future)
                } else {
                    startFirstLocalTrack(controller, future)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun resumeQueue(controller: MediaController) {
        if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0L)
        if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
        controller.play()
        QuickPlayTileState.setActive(this, true)
    }

    private fun startFirstLocalTrack(controller: MediaController, future: com.google.common.util.concurrent.ListenableFuture<MediaController>) {
        tileScope.launch {
            val firstSong = LocalMediaRepository.getLocalMusic(applicationContext).firstOrNull()
            if (firstSong == null) {
                MediaController.releaseFuture(future)
                QuickPlayTileState.setActive(this@QuickPlayTileService, false)
                openAppForSetup()
                return@launch
            }
            controller.setMediaItems(listOf(firstSong.toMediaItem()), 0, 0L)
            controller.prepare()
            controller.play()
            QuickPlayTileState.setActive(this@QuickPlayTileService, true)
            MediaController.releaseFuture(future)
        }
    }

    private fun stopPlayback(
        controller: MediaController,
        future: com.google.common.util.concurrent.ListenableFuture<MediaController>,
    ) {
        controller.pause()
        QuickPlayTileState.setActive(this, false)
        QuickPlayTileState.closeVisibleActivity()
        // Keep the queue and playhead bookmark so the next tile tap resumes at
        // the same place. Stopping the service removes its media notification
        // without killing the app process or affecting other apps.
        mainHandler.postDelayed({
            runCatching { stopService(Intent(this, PlaybackService::class.java)) }
            MediaController.releaseFuture(future)
        }, SERVICE_STOP_DELAY_MS)
    }

    private fun openAppForSetup() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun renderTile(active: Boolean) {
        qsTile?.let { tile ->
            tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.contentDescription = getString(
                if (active) com.music.bitchord.R.string.quick_play_tile_stop
                else com.music.bitchord.R.string.quick_play_tile_start,
            )
            tile.updateTile()
        }
    }

    private companion object {
        const val SERVICE_STOP_DELAY_MS = 300L
    }
}

/** Tile state and a weak handle used only to close an already visible app task. */
object QuickPlayTileState {
    private const val PREFS = "quick_play_tile"
    private const val KEY_ACTIVE = "active"
    @Volatile private var visibleActivity = WeakReference<Activity>(null)

    fun attach(activity: Activity) {
        visibleActivity = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (visibleActivity.get() === activity) visibleActivity.clear()
    }

    fun closeVisibleActivity() {
        val activity = visibleActivity.get() ?: return
        activity.runOnUiThread {
            if (!activity.isFinishing) activity.finishAndRemoveTask()
        }
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, active).apply()
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, QuickPlayTileService::class.java),
            )
        }
    }
}
