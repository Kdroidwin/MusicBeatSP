package com.music.bitchord.widget

import android.widget.RemoteViews
import com.music.bitchord.data.settings.AppSettings

/** Applies the shared opacity to the complete RemoteViews tree. */
internal object WidgetOpacity {
    fun apply(views: RemoteViews, rootViewId: Int) {
        val alpha = (AppSettings.widgetOpacity.value / 100f).coerceIn(0f, 1f)
        views.setFloat(rootViewId, "setAlpha", alpha)
    }
}
