package com.music.bitchord.widget

import android.content.Context
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

    /** Debounced redraw of every widget layout that reads the shared opacity setting. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        synchronized(refreshLock) {
            refreshJob?.cancel()
            refreshJob = scope.launch {
                delay(180L)
                MediaWidget.refresh(app)
                // MediaWidget.refresh redraws the original layouts and already
                // fans out to the mini, album-square, pill, and vinyl providers.
                // The two Now Playing layouts are the only providers outside
                // that refresh path.
                MediaWidgetNowPlaying.refresh(app)
            }
        }
    }
}
