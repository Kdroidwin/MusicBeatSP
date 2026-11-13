package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
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

/** Upstream BitChord's circular-cover 4×1 Now Playing widget. */
class MediaWidgetNowPlaying : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        NowPlayingWidgetRenderer.renderAsync(this, context, ids, NowPlayingWidgetStyle.CIRCLE)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) = NowPlayingWidgetRenderer.renderAsync(this, context, intArrayOf(id), NowPlayingWidgetStyle.CIRCLE)

    override fun onDeleted(context: Context, ids: IntArray) =
        NowPlayingWidgetRenderer.forget(ids, NowPlayingWidgetStyle.CIRCLE)

    companion object {
        /** Refreshes both 4×1 variants when playback state changes. */
        internal fun refresh(context: Context) = NowPlayingWidgetRenderer.refresh(context)
    }
}

/** Separate launcher choice with a rounded-rectangle body and rounded-square cover. */
class MediaWidgetNowPlayingRounded : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        NowPlayingWidgetRenderer.renderAsync(this, context, ids, NowPlayingWidgetStyle.ROUNDED_SQUARE)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) = NowPlayingWidgetRenderer.renderAsync(this, context, intArrayOf(id), NowPlayingWidgetStyle.ROUNDED_SQUARE)

    override fun onDeleted(context: Context, ids: IntArray) =
        NowPlayingWidgetRenderer.forget(ids, NowPlayingWidgetStyle.ROUNDED_SQUARE)
}

private enum class NowPlayingWidgetStyle(
    val provider: Class<out AppWidgetProvider>,
    val layoutId: Int,
    val roundedSquareCover: Boolean,
) {
    CIRCLE(MediaWidgetNowPlaying::class.java, R.layout.widget_media_now_playing, false),
    ROUNDED_SQUARE(
        MediaWidgetNowPlayingRounded::class.java,
        R.layout.widget_media_now_playing_rounded,
        true,
    ),
}

/** Shared transport, artwork and rendering implementation for the two launcher choices. */
private object NowPlayingWidgetRenderer {
    private const val FALLBACK_WIDTH_DP = 250
    private const val MAX_WIDTH_PX = 1_600
    private const val RENDER_TIMEOUT_MS = 8_000L
    private const val ALPHA_ENABLED = 255
    private const val ALPHA_DISABLED = 90
    private const val REQUEST_OPEN_PLAYER = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastCover = ConcurrentHashMap<String, Bitmap>()
    private val lastBody = ConcurrentHashMap<String, Bitmap>()

    fun renderAsync(
        provider: AppWidgetProvider,
        context: Context,
        ids: IntArray,
        style: NowPlayingWidgetStyle,
    ) {
        val pending = provider.goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                render(app, ids, style)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    fun forget(ids: IntArray, style: NowPlayingWidgetStyle) {
        ids.forEach { id ->
            val key = cacheKey(style, id)
            lastCover.remove(key)
            lastBody.remove(key)
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
            for (style in NowPlayingWidgetStyle.entries) {
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, style.provider))
                }.getOrNull()
                if (ids != null && ids.isNotEmpty()) render(app, ids, style)
            }
        }
    }

    private suspend fun render(context: Context, ids: IntArray, style: NowPlayingWidgetStyle) {
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
        val snapshot = MediaWidgetSnapshot.load(context)
        val key = snapshot.artworkUrl?.takeIf(String::isNotBlank)
        val resources = context.resources
        val coverPx = resources.getDimensionPixelSize(R.dimen.widget_pill_art)
        val panelHeightPx = resources.getDimensionPixelSize(R.dimen.widget_pill_height)
        val cornerRadiusPx = if (style.roundedSquareCover) {
            resources.getDimension(R.dimen.widget_now_playing_corner_radius)
        } else {
            panelHeightPx / 2f
        }
        for (id in ids) {
            val cacheKey = cacheKey(style, id)
            val widthPx = widthPx(context, manager, id)
            val cachedCover = key?.let {
                if (style.roundedSquareCover) MediaWidgetArt.peekRoundedSquare(it, coverPx)
                else MediaWidgetArt.peekCircle(it, coverPx)
            }
            val cachedBody = key?.let {
                MediaWidgetArt.peekPill(it, widthPx, panelHeightPx, cornerRadiusPx)
            }
            if (cachedCover == null || cachedBody == null) {
                // Keep the last complete artwork while a new cover is fetched.
                manager.push(id, views(context, snapshot, style, cachedCover ?: lastCover[cacheKey], cachedBody ?: lastBody[cacheKey]))
            }
            val drawn = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                val cover = cachedCover ?: key?.let {
                    if (style.roundedSquareCover) {
                        MediaWidgetArt.roundedSquare(context, snapshot.artworkUrl, coverPx, it)
                    } else {
                        MediaWidgetArt.circle(context, snapshot.artworkUrl, coverPx, it)
                    }
                }
                val body = cachedBody ?: MediaWidgetArt.pill(
                    context = context,
                    artworkUrl = snapshot.artworkUrl,
                    widthPx = widthPx,
                    heightPx = panelHeightPx,
                    key = key,
                    cornerRadiusPx = cornerRadiusPx,
                )
                cover to body
            } ?: continue
            val (cover, body) = drawn
            if (cover == null) lastCover.remove(cacheKey) else lastCover[cacheKey] = cover
            lastBody[cacheKey] = body
            manager.push(id, views(context, snapshot, style, cover, body))
        }
    }

    private fun views(
        context: Context,
        snapshot: MediaWidgetSnapshot,
        style: NowPlayingWidgetStyle,
        cover: Bitmap?,
        body: Bitmap?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, style.layoutId)
        cover?.let { views.setImageViewBitmap(R.id.widget_art, it) }
        body?.let {
            views.setImageViewBitmap(R.id.widget_pill_bg, it)
            // The fallback panel would show through the body's transparent corners.
            views.setInt(R.id.widget_pill_bg, "setBackgroundResource", 0)
        }

        views.setTextViewText(
            R.id.widget_title,
            when {
                !snapshot.hasTrack -> context.getString(R.string.widget_nothing_played)
                snapshot.artist.isBlank() -> snapshot.title
                else -> "${snapshot.title} - ${snapshot.artist}"
            },
        )
        views.setImageViewResource(
            R.id.widget_toggle,
            if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
        views.setContentDescription(
            R.id.widget_toggle,
            context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
        )
        views.setViewVisibility(R.id.widget_like, if (snapshot.canLike) View.VISIBLE else View.GONE)
        views.setImageViewResource(
            R.id.widget_like,
            if (snapshot.isLiked) R.drawable.ic_widget_star_filled else R.drawable.ic_widget_star,
        )
        views.setContentDescription(
            R.id.widget_like,
            context.getString(if (snapshot.isLiked) R.string.remove_from_liked else R.string.like),
        )
        views.setInt(
            R.id.widget_shuffle,
            "setBackgroundResource",
            if (snapshot.shuffleEnabled) R.drawable.widget_toggle_on else R.drawable.widget_transport_button,
        )
        views.setContentDescription(
            R.id.widget_shuffle,
            context.getString(if (snapshot.shuffleEnabled) R.string.shuffle_on else R.string.shuffle_off),
        )

        val open = openPlayer(context)
        views.setOnClickPendingIntent(R.id.widget_pill, open)
        if (snapshot.hasTrack) {
            views.setImageAlpha(R.id.widget_toggle, ALPHA_ENABLED)
            views.setImageAlpha(R.id.widget_like, ALPHA_ENABLED)
            views.setImageAlpha(R.id.widget_shuffle, ALPHA_ENABLED)
            views.setImageAlpha(R.id.widget_previous, if (snapshot.hasPrevious) ALPHA_ENABLED else ALPHA_DISABLED)
            views.setImageAlpha(R.id.widget_next, if (snapshot.hasNext) ALPHA_ENABLED else ALPHA_DISABLED)
            for ((button, action) in ACTIONS) {
                views.setOnClickPendingIntent(button, MediaWidgetActions.pendingIntent(context, action))
            }
        } else {
            for ((button, _) in ACTIONS) {
                views.setImageAlpha(button, ALPHA_DISABLED)
                views.setOnClickPendingIntent(button, open)
            }
        }
        return views
    }

    private fun widthPx(context: Context, manager: AppWidgetManager, id: Int): Int {
        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val widthDp = options?.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
            else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
        )?.takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
        return (widthDp * context.resources.displayMetrics.density).roundToInt().coerceIn(1, MAX_WIDTH_PX)
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

    private fun RemoteViews.setImageAlpha(viewId: Int, alpha: Int) = setInt(viewId, "setImageAlpha", alpha)

    private fun AppWidgetManager.push(id: Int, views: RemoteViews) {
        runCatching { updateAppWidget(id, views) }
    }

    private fun cacheKey(style: NowPlayingWidgetStyle, id: Int) = "${style.name}:$id"

    private val ACTIONS = listOf(
        R.id.widget_like to MediaWidgetActions.ACTION_LIKE,
        R.id.widget_previous to MediaWidgetActions.ACTION_PREVIOUS,
        R.id.widget_toggle to MediaWidgetActions.ACTION_TOGGLE,
        R.id.widget_next to MediaWidgetActions.ACTION_NEXT,
        R.id.widget_shuffle to MediaWidgetActions.ACTION_SHUFFLE,
    )
}
