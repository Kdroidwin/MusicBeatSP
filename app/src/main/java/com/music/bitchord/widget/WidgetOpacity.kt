package com.music.bitchord.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.RemoteViews
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Applies the independent whole-widget and background-only opacity settings. */
internal object WidgetOpacity {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshLock = Any()
    private var refreshJob: Job? = null

    fun apply(views: RemoteViews, rootViewId: Int) {
        val alpha = (AppSettings.widgetOpacity.value / 100f).coerceIn(0f, 1f)
        views.setFloat(rootViewId, "setAlpha", alpha)
    }

    /** Changes only an ImageView used as a background surface, leaving foreground content intact. */
    fun applyBackground(views: RemoteViews, backgroundViewId: Int) {
        val alpha = (AppSettings.widgetBackgroundOpacity.value / 100f * 255f).toInt().coerceIn(0, 255)
        views.setInt(backgroundViewId, "setImageAlpha", alpha)
    }

    /**
     * Applies background-only opacity to the bitmap used by RemoteViews.
     * Some launcher/Android combinations ignore ImageView's reflected
     * setImageAlpha action after a bitmap update, so bake the alpha into this
     * decorative layer. The cached source bitmap stays untouched.
     */
    fun backgroundBitmap(bitmap: Bitmap, includeWholeWidgetOpacity: Boolean = false): Bitmap {
        val wholeOpacity = if (includeWholeWidgetOpacity) AppSettings.widgetOpacity.value / 100f else 1f
        val opacity = AppSettings.widgetBackgroundOpacity.value / 100f * wholeOpacity
        val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        if (alpha == 255) return bitmap
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            bitmap,
            0f,
            0f,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha },
        )
        return output
    }

    fun combinedBackgroundAlpha(): Int = (
        AppSettings.widgetBackgroundOpacity.value / 100f *
            AppSettings.widgetOpacity.value / 100f * 255f
        ).toInt().coerceIn(0, 255)

    /** Debounced redraw of every widget layout that reads the shared opacity setting. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        synchronized(refreshLock) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                delay(180L)
                MediaWidget.refresh(app)
                // MediaWidget.refresh also fans out to both Now Playing
                // layouts. A second asynchronous pass could race and replace
                // the freshly drawn opacity state on some launchers.
            }
        }
    }
}
