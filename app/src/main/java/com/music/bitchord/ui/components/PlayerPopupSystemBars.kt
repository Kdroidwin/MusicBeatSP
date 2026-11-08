package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.music.bitchord.data.settings.AppSettings

/** Keep a player popup's dialog window aligned with the player's status-bar setting. */
@Composable
fun PlayerPopupSystemBars(active: Boolean) {
    if (!active) return
    val showStatusBar by AppSettings.showPlayerStatusBar.collectAsStateWithLifecycle()
    val view = LocalView.current

    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}
