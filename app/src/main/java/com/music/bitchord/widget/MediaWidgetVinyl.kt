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
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/** Separate vinyl-record widget; all pre-existing widget providers keep their own layouts. */
class MediaWidgetVinyl : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = renderAsync(context, ids)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) = renderAsync(context, intArrayOf(id))

    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) { render(context.applicationContext, ids) }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        private const val RENDER_TIMEOUT_MS = 8_000L
        private const val DISC_SIZE_DP = 164
        private const val MAX_BITMAP_PX = 1_000f
        private const val REQUEST_OPEN_PLAYER = 81
        private const val NO_ART = "vinyl-widget-no-art"
        private const val FALLBACK_START = 0xFF7B708C.toInt()
        private const val FALLBACK_END = 0xFF4E465B.toInt()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@launch
                val ids = runCatching {
                    manager.getAppWidgetIds(ComponentName(app, MediaWidgetVinyl::class.java))
                }.getOrNull() ?: return@launch
                if (ids.isNotEmpty()) withTimeoutOrNull(RENDER_TIMEOUT_MS) { render(app, ids) }
            }
        }

        private suspend fun render(context: Context, ids: IntArray) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            val artKey = snapshot.artworkUrl?.takeIf(String::isNotBlank) ?: NO_ART
            for (id in ids) {
                val density = context.resources.displayMetrics.density
                val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
                val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    ?.takeIf { it > 0 } ?: DEFAULT_WIDTH_DP
                val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    ?.takeIf { it > 0 } ?: DEFAULT_HEIGHT_DP
                val widthRaw = widthDp * density
                val heightRaw = heightDp * density
                val scale = minOf(1f, MAX_BITMAP_PX / max(widthRaw, heightRaw))
                val widthPx = (widthRaw * scale).roundToInt().coerceAtLeast(1)
                val heightPx = (heightRaw * scale).roundToInt().coerceAtLeast(1)
                val discPx = (DISC_SIZE_DP * density * scale).roundToInt().coerceAtLeast(1)
                val cover = MediaWidgetArt.loadSquareCover(context, snapshot.artworkUrl, discPx)
                val artwork = cover?.let { circle(it, (discPx * 0.43f).roundToInt().coerceAtLeast(1)) }
                val palette = cover?.let { runCatching { Palette.from(it).generate() }.getOrNull() }
                val primary = palette?.getVibrantColor(palette.getMutedColor(FALLBACK_START)) ?: FALLBACK_START
                val secondary = palette?.getDarkVibrantColor(
                    ColorUtils.blendARGB(primary, FALLBACK_END, 0.48f),
                ) ?: FALLBACK_END
                val views = RemoteViews(context.packageName, R.layout.widget_media_vinyl)
                views.setImageViewBitmap(
                    R.id.widget_vinyl_surface,
                    surface(widthPx, heightPx, primary, secondary, density * scale),
                )
                if (artwork != null) {
                    views.setImageViewBitmap(R.id.widget_vinyl_art, artwork)
                } else {
                    views.setImageViewResource(R.id.widget_vinyl_art, R.drawable.widget_preview_art)
                }
                views.setTextViewText(
                    R.id.widget_vinyl_title,
                    if (snapshot.hasTrack) snapshot.title else context.getString(R.string.widget_nothing_played),
                )
                views.setTextViewText(R.id.widget_vinyl_artist, snapshot.artist)
                views.setOnClickPendingIntent(R.id.widget_vinyl_root, openPlayer(context))
                WidgetOpacity.apply(views, R.id.widget_vinyl_root)
                runCatching { manager.updateAppWidget(id, views) }
            }
        }

        private fun circle(source: Bitmap, size: Int): Bitmap {
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val path = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(path)
            val scale = max(size / source.width.toFloat(), size / source.height.toFloat())
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - source.width * scale) / 2f, (size - source.height * scale) / 2f)
            }
            canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            return output
        }

        private fun surface(width: Int, height: Int, start: Int, end: Int, density: Float): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val inset = (1.5f * density).coerceAtLeast(1f)
            val bounds = RectF(inset, inset, width - inset, height - inset)
            val radius = 26f * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), start, end, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(bounds, radius, radius, paint)
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(Color.WHITE, 38)
                style = Paint.Style.STROKE
                strokeWidth = inset
            }
            canvas.drawRoundRect(bounds, radius, radius, edge)
            return bitmap
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

        private const val DEFAULT_WIDTH_DP = 220
        private const val DEFAULT_HEIGHT_DP = 240
    }
}
