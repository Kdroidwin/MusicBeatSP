package com.music.bitchord.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** A new, wide pill widget; existing widget providers and layouts remain unchanged. */
class MediaWidgetPill : AppWidgetProvider() {

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

    override fun onDisabled(context: Context) {
        artworkCache.evictAll()
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
        private const val RENDER_TIMEOUT_MS = 7_000L
        private const val ART_SIZE_DP = 62
        private const val DEFAULT_WIDTH_DP = 260
        private const val DEFAULT_HEIGHT_DP = 76
        private const val MAX_BITMAP_PX = 1_000f
        private const val MAX_ART_CACHE_KB = 384
        private const val REQUEST_OPEN_PLAYER = 3
        private const val NO_ARTWORK = "pill-widget-no-artwork"
        private val DEFAULT_SURFACE = 0xFF716967.toInt()
        private val DEFAULT_TEXT = Color.WHITE
        private val refreshLock = Any()
        private var refreshJob: Job? = null

        private data class Artwork(
            val bitmap: Bitmap?,
            val surface: Int,
        )

        private val artworkCache = object : LruCache<String, Artwork>(MAX_ART_CACHE_KB) {
            override fun sizeOf(key: String, value: Artwork): Int =
                (value.bitmap?.byteCount?.div(1024) ?: 1).coerceAtLeast(1)
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Coalesces slider and playback updates so one gesture cannot flood the widget host. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            synchronized(refreshLock) {
                refreshJob?.cancel()
                refreshJob = scope.launch {
                    delay(180L)
                    val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                    val ids = runCatching {
                        manager.getAppWidgetIds(ComponentName(app, MediaWidgetPill::class.java))
                    }.getOrNull() ?: return@launch
                    if (ids.isNotEmpty()) withTimeoutOrNull(RENDER_TIMEOUT_MS) { render(app, ids) }
                }
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val key = snapshot.artworkUrl?.takeIf(String::isNotBlank) ?: NO_ARTWORK
            val artwork = artworkCache.get(key) ?: loadArtwork(context, snapshot.artworkUrl).also {
                // Retry on the next refresh after a transient/missing cover.
                if (it.bitmap != null || key == NO_ARTWORK) artworkCache.put(key, it)
            }
            val opacity = AppSettings.pillWidgetOpacity.value.roundToInt().coerceIn(0, 100)
            ids.forEach { id ->
                val size = measure(context, manager, id)
                val views = RemoteViews(context.packageName, R.layout.widget_media_pill)
                views.setImageViewBitmap(
                    R.id.widget_pill_surface,
                    roundedSurface(size.widthPx, size.heightPx, artwork.surface),
                )
                views.setImageAlpha(R.id.widget_pill_surface, (opacity * 255 / 100).coerceIn(0, 255))
                artwork.bitmap?.let { views.setImageViewBitmap(R.id.widget_pill_art, it) }
                views.setTextViewText(
                    R.id.widget_pill_title,
                    if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
                )
                views.setTextColor(R.id.widget_pill_title, DEFAULT_TEXT)
                views.setImageViewResource(
                    R.id.widget_pill_toggle,
                    if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                )
                TRANSPORT.forEach { views.setInt(it, "setColorFilter", DEFAULT_TEXT) }
                views.setImageAlpha(R.id.widget_pill_previous, if (snapshot.hasPrevious) 255 else 90)
                views.setImageAlpha(R.id.widget_pill_next, if (snapshot.hasNext) 255 else 90)
                views.setContentDescription(
                    R.id.widget_pill_toggle,
                    context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
                )

                val openPlayer = openPlayer(context)
                views.setOnClickPendingIntent(R.id.widget_pill_root, openPlayer)
                if (snapshot.hasTrack) {
                    views.setOnClickPendingIntent(
                        R.id.widget_pill_previous,
                        MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_PREVIOUS),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_pill_toggle,
                        MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_pill_next,
                        MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_NEXT),
                    )
                } else {
                    TRANSPORT.forEach {
                        views.setImageAlpha(it, 90)
                        views.setOnClickPendingIntent(it, openPlayer)
                    }
                }
                runCatching { manager.updateAppWidget(id, views) }
            }
        }

        private suspend fun loadArtwork(context: Context, url: String?): Artwork {
            val px = (ART_SIZE_DP * context.resources.displayMetrics.density).roundToInt().coerceIn(1, 512)
            val bitmap = url?.let { runCatching { MediaWidgetArt.loadSquareCover(context, it, px) }.getOrNull() }
            val surface = bitmap?.let {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val palette = Palette.from(it).maximumColorCount(16).generate()
                        val sample = (palette.mutedSwatch ?: palette.darkMutedSwatch ?: palette.dominantSwatch)
                            ?.rgb ?: DEFAULT_SURFACE
                        // Keep a mid-tone surface close to the artwork hue, as in the reference pill.
                        ColorUtils.blendARGB(sample, 0xFF777270.toInt(), 0.28f)
                    }.getOrDefault(DEFAULT_SURFACE)
                }
            } ?: DEFAULT_SURFACE
            return Artwork(bitmap, surface)
        }

        private fun roundedSurface(width: Int, height: Int, color: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                height / 2f,
                height / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
            )
            return bitmap
        }

        private fun measure(context: Context, manager: AppWidgetManager, id: Int): Size {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                ?.takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
            val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                ?.takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
            val density = context.resources.displayMetrics.density
            val width = widthDp * density
            val height = heightDp * density
            val scale = minOf(1f, MAX_BITMAP_PX / maxOf(width, height))
            return Size((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
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

        private val TRANSPORT = intArrayOf(
            R.id.widget_pill_previous,
            R.id.widget_pill_toggle,
            R.id.widget_pill_next,
        )

        private data class Size(val widthPx: Int, val heightPx: Int)
    }
}
