package com.music.bitchord.data.innertube

import android.content.Context
import android.webkit.CookieManager
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.innertube.potoken.PoTokenGenerator
import com.music.bitchord.data.innertube.potoken.PoTokenWebView
import com.music.bitchord.data.settings.AppSettings

/** Installs BitChord's stream resolver only after YouTube Music was opted into. */
object YoutubeStreamBootstrap {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initializeIfAllowed(context: Context) {
        if (!AppSettings.youtubeMusicNetworkAllowed) return

        StreamResolver.maxKbps = { AppSettings.effectiveAudioQuality.maxKbps }
        InnerTubeXResolver.debugLogging = BuildConfig.DEBUG

        if (initialized) {
            InnerTubeXResolver.prewarm()
            return
        }

        val app = context.applicationContext
        val prefs = app.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
        val poTokens = PoTokenGenerator(
            createMinter = { PoTokenWebView.getNewPoTokenGenerator(app) },
            available = { webViewSupported },
        )
        InnerTubeXResolver.init(
            filesDir = app.filesDir,
            store = object : InnerTubeXResolver.ConfigStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getLong(key: String): Long? =
                    if (prefs.contains(key)) prefs.getLong(key, 0L) else null

                override fun putString(key: String, value: String) {
                    prefs.edit().putString(key, value).apply()
                }

                override fun putLong(key: String, value: Long) {
                    prefs.edit().putLong(key, value).apply()
                }
            },
            poTokenProvider = poTokens.asTokenProvider(),
        )
        initialized = true
    }
}
