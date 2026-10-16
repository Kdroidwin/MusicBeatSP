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
import android.widget.RemoteViews
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** A separate, mini-player-shaped home-screen widget. */
class MediaWidgetMini : AppWidgetProvider() {

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
        private const val RENDER_TIMEOUT_MS = 7_500L
        private const val ART_SIZE_DP = 44
        private const val DEFAULT_WIDTH_DP = 250
        private const val DEFAULT_HEIGHT_DP = 76
        private const val MAX_BITMAP_PX = 1_000f
        private const val MAX_ART_CACHE_KB = 512
        private const val REQUEST_OPEN_PLAYER = 2
        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90
        private const val NO_ARTWORK = "mini-widget-no-artwork"
        private val DEFAULT_SURFACE = 0xFF303442.toInt()
        private val DEFAULT_FOREGROUND = Color.WHITE
        private val DEFAULT_ACCENT = 0xFF8B91A4.toInt()

        private data class CachedArtwork(
            val bitmap: Bitmap?,
            val surface: Int,
            val foreground: Int,
            val accent: Int,
        )

        private val artworkCache = object : LruCache<String, CachedArtwork>(MAX_ART_CACHE_KB) {
            override fun sizeOf(key: String, value: CachedArtwork): Int =
                (value.bitmap?.byteCount?.div(1024) ?: 1).coerceAtLeast(1)
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Refresh only instances of this newly added provider. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, MediaWidgetMini::class.java))
                }.getOrNull() ?: return@launch
                if (ids.isNotEmpty()) withTimeoutOrNull(RENDER_TIMEOUT_MS) { render(app, ids) }
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val key = snapshot.artworkUrl?.takeIf(String::isNotBlank) ?: NO_ARTWORK
            val art = artworkCache.get(key) ?: loadArtwork(context, snapshot.artworkUrl).also {
                // A missing local file or a transient network failure must not
                // pin the fallback palette for this artwork until process death.
                if (it.bitmap != null || key == NO_ARTWORK) artworkCache.put(key, it)
            }

            ids.forEach { id ->
                val size = measure(context, manager, id)
                val views = RemoteViews(context.packageName, R.layout.widget_media_mini)
                views.setImageViewBitmap(
                    R.id.widget_mini_surface,
                    roundedSurface(size.widthPx, size.heightPx, art.surface, art.accent),
                )
                art.bitmap?.let { views.setImageViewBitmap(R.id.widget_mini_art, it) }
                views.setTextViewText(
                    R.id.widget_mini_title,
                    if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
                )
                views.setTextViewText(R.id.widget_mini_artist, snapshot.artist)
                views.setTextColor(R.id.widget_mini_title, art.foreground)
                views.setTextColor(R.id.widget_mini_artist, ColorUtils.setAlphaComponent(art.foreground, 205))
                views.setImageViewResource(
                    R.id.widget_mini_toggle,
                    if (snapshot.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                )
                for (button in TRANSPORT) {
                    views.setInt(button, "setColorFilter", art.foreground)
                }
                views.setImageAlpha(
                    R.id.widget_mini_previous,
                    if (snapshot.hasPrevious) ALPHA_ENABLED else ALPHA_DISABLED,
                )
                views.setImageAlpha(
                    R.id.widget_mini_next,
                    if (snapshot.hasNext) ALPHA_ENABLED else ALPHA_DISABLED,
                )
                views.setContentDescription(
                    R.id.widget_mini_toggle,
                    context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play),
                )

                val openPlayer = openPlayer(context)
                views.setOnClickPendingIntent(R.id.widget_mini_root, openPlayer)
                if (snapshot.hasTrack) {
                    views.setOnClickPendingIntent(
                        R.id.widget_mini_previous,
                        MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_PREVIOUS),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_mini_toggle,
                        MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_TOGGLE),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_mini_next,
                        MediaWidgetActions.pendingIntent(context, MediaWidgetActions.ACTION_NEXT),
                    )
                } else {
                    TRANSPORT.forEach { button ->
                        views.setImageAlpha(button, ALPHA_DISABLED)
                        views.setOnClickPendingIntent(button, openPlayer)
                    }
                }
                runCatching { manager.updateAppWidget(id, views) }
            }
        }

        private suspend fun loadArtwork(context: Context, url: String?): CachedArtwork {
            val sizePx = (ART_SIZE_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
            val bitmap = url?.let { runCatching { MediaWidgetArt.loadSquareCover(context, it, sizePx) }.getOrNull() }
            val colors = bitmap?.let {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val palette = Palette.from(it).maximumColorCount(20).generate()
                        val sample = (palette.darkMutedSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch)
                            ?.rgb ?: DEFAULT_SURFACE
                        val vibrant = (palette.lightVibrantSwatch ?: palette.vibrantSwatch ?: palette.dominantSwatch)
                            ?.rgb ?: DEFAULT_ACCENT
                        val surface = ColorUtils.blendARGB(sample, Color.BLACK, 0.34f)
                        CachedArtwork(
                            bitmap = it,
                            surface = surface,
                            foreground = if (ColorUtils.calculateContrast(Color.WHITE, surface) >= 4.5) {
                                Color.WHITE
                            } else {
                                Color.BLACK
                            },
                            accent = ColorUtils.blendARGB(vibrant, surface, 0.38f),
                        )
                    }.getOrNull()
                }
            }
            return colors ?: CachedArtwork(
                bitmap = null,
                surface = DEFAULT_SURFACE,
                foreground = DEFAULT_FOREGROUND,
                accent = DEFAULT_ACCENT,
            )
        }

        private fun roundedSurface(width: Int, height: Int, color: Int, accent: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val radius = height / 2f
            canvas.drawRoundRect(
                bounds,
                radius,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
            )
            val strokeWidth = (height * 0.012f).coerceAtLeast(1f)
            val inset = strokeWidth / 2f
            canvas.drawRoundRect(
                RectF(inset, inset, width - inset, height - inset),
                radius,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = ColorUtils.setAlphaComponent(accent, 150)
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                },
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
            val rawWidth = widthDp * density
            val rawHeight = heightDp * density
            val longest = maxOf(rawWidth, rawHeight)
            val scale = if (longest > MAX_BITMAP_PX) MAX_BITMAP_PX / longest else 1f
            return Size(
                widthPx = (rawWidth * scale).roundToInt().coerceAtLeast(1),
                heightPx = (rawHeight * scale).roundToInt().coerceAtLeast(1),
            )
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

        private val TRANSPORT = intArrayOf(
            R.id.widget_mini_previous,
            R.id.widget_mini_toggle,
            R.id.widget_mini_next,
        )

        private data class Size(val widthPx: Int, val heightPx: Int)
    }
}
