package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri

/** Device-local SAF tree grant; intentionally excluded from portable app settings and backups. */
object LocalFolderGrantStore {
    private const val PREFS = "local_music_folder_grant"
    private const val TREE_URI = "tree_uri"

    fun set(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TREE_URI, uri.toString())
            .apply()
    }

    fun get(context: Context): Uri? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE_URI, null)
            ?.let(Uri::parse)
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TREE_URI).apply()
    }
}
