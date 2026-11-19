package com.music.bitchord.ui.screens

import androidx.lifecycle.ViewModel

/** Holds an export password only in process memory while the system picker is open. */
internal class BackupPasswordVault : ViewModel() {
    private var password: CharArray? = null

    @Synchronized
    fun put(value: CharArray) {
        clear()
        password = value
    }

    @Synchronized
    fun take(): CharArray? = password.also { password = null }

    @Synchronized
    fun clear() {
        password?.fill('\u0000')
        password = null
    }

    override fun onCleared() {
        clear()
    }
}
