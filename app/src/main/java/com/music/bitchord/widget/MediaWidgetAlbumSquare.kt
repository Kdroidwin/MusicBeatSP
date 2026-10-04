package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.RemoteViews
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Independent provider for the title + square cover + four-control widget. */
class MediaWidgetAlbumSquare : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) {
        renderAsync(context, intArrayOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach(lastArtwork::remove)
    }

    override fun onDisabled(context: Context) {
        lastArtwork.clear()
    }

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) { render(app, ids) }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        private const val RENDER_TIMEOUT_MS = 8_000L
        private const val ART_SIZE_DP = 110
        private const val REQUEST_OPEN_PLAYER = 1
        private const val ALPHA_ENABLED = 255
        private const val ALPHA_INACTIVE = 185
        private const val ALPHA_DISABLED = 90

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lastArtwork = ConcurrentHashMap<Int, Pair<String, Bitmap>>()
        private val transport = intArrayOf(
            R.id.widget_album_shuffle,
            R.id.widget_album_previous,
            R.id.widget_album_toggle,
            R.id.widget_album_next,
        )

        /** Redraws only this provider; the two original providers keep their original renderer. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, MediaWidgetAlbumSquare::class.java))
                }.getOrNull() ?: return@launch
                if (ids.isNotEmpty()) withTimeoutOrNull(RENDER_TIMEOUT_MS) { render(app, ids) }
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val key = snapshot.artworkUrl ?: NO_ARTWORK
            val sizePx = (ART_SIZE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
            for (id in ids) {
                val previous = lastArtwork[id]
                val cached = previous?.takeIf { it.first == key }?.second
                val interim = cached ?: previous?.second.takeIf { !snapshot.artworkUrl.isNullOrBlank() }
                val views = views(context, snapshot, interim)
                manager.push(id, views)

                val artwork = cached ?: MediaWidgetArt.loadSquareCover(context, snapshot.artworkUrl, sizePx)
                if (artwork != null) {
                    lastArtwork[id] = key to artwork
                } else if (snapshot.artworkUrl.isNullOrBlank()) {
                    lastArtwork.remove(id)
                }
                manager.push(id, views(context, snapshot, artwork ?: interim))
            }
        }

        private fun views(context: Context, snapshot: MediaWidgetSnapshot, art: Bitmap?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_media_album_square)
            views.setTextViewText(
                R.id.widget_album_title,
                if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
            )
            art?.let { views.setImageViewBitmap(R.id.widget_album_art, it) }
            views.setOnClickPendingIntent(R.id.widget_album_root, openPlayer(context))
            views.setImageViewResource(
                R.id.widget_album_toggle,
                if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_album_toggle,
                context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
            )
            views.setContentDescription(
                R.id.widget_album_shuffle,
                context.getString(if (snapshot.shuffleEnabled) R.string.widget_shuffle_on else R.string.widget_shuffle),
            )
            views.setImageAlpha(
                R.id.widget_album_shuffle,
                if (snapshot.shuffleEnabled) ALPHA_ENABLED else ALPHA_INACTIVE,
            )

            if (snapshot.hasTrack) {
                views.setImageAlpha(R.id.widget_album_toggle, ALPHA_ENABLED)
                views.setImageAlpha(
                    R.id.widget_album_previous,
                    if (snapshot.hasPrevious) ALPHA_ENABLED else ALPHA_DISABLED,
                )
                views.setImageAlpha(
                    R.id.widget_album_next,
                    if (snapshot.hasNext) ALPHA_ENABLED else ALPHA_DISABLED,
                )
                views.setOnClickPendingIntent(
                    R.id.widget_album_shuffle,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_SHUFFLE),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_album_previous,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_PREVIOUS),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_album_toggle,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_album_next,
                    MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_NEXT),
                )
            } else {
                transport.forEach { button ->
                    views.setImageAlpha(button, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(button, openPlayer(context))
                }
            }
            return views
        }

        private fun openPlayer(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN_PLAYER,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun RemoteViews.setImageAlpha(viewId: Int, alpha: Int) =
            setInt(viewId, "setImageAlpha", alpha)

        private fun AppWidgetManager.push(id: Int, views: RemoteViews) {
            runCatching { updateAppWidget(id, views) }
        }

        private const val NO_ARTWORK = "no-artwork"
    }
}
