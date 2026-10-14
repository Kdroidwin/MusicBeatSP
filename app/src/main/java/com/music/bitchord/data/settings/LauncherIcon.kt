package com.music.bitchord.data.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The launcher artwork variants which ship with the app. */
enum class LauncherIcon {
    PINK,
    BLACK,
    GREEN,
}

/** Switches launcher aliases without restarting or disabling the app itself. */
internal object LauncherIconManager {
    private val aliases = mapOf(
        LauncherIcon.PINK to ".LauncherPink",
        LauncherIcon.BLACK to ".LauncherBlack",
        LauncherIcon.GREEN to ".LauncherGreen",
    )

    fun apply(context: Context, selected: LauncherIcon) {
        val packageManager = context.packageManager
        val states = aliases.map { (icon, className) ->
            val component = ComponentName(context.packageName, context.packageName + className)
            val state = if (icon == selected) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            component to state
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val applied = runCatching {
                packageManager.setComponentEnabledSettings(
                    states.map { (component, state) ->
                        PackageManager.ComponentEnabledSetting(
                            component,
                            state,
                            PackageManager.DONT_KILL_APP,
                        )
                    },
                )
            }.isSuccess
            if (applied) return
        }

        // On older Android releases there is no atomic multi-component update.
        // Enable the destination first so the launcher never briefly loses the app.
        states.firstOrNull { it.second == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
            ?.let { (component, state) ->
                packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        states.filter { it.second != PackageManager.COMPONENT_ENABLED_STATE_ENABLED }.forEach { (component, state) ->
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
