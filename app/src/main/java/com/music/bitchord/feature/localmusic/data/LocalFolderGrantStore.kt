package com.music.bitchord.feature.localmusic.data

import android.content.Context
import android.net.Uri

/** Device-local SAF tree grant; intentionally excluded from portable app settings and backups. */
object LocalFolderGrantStore {
    private const val PREFS = "local_music_folder_grant"
    private const val TREE_URI = "tree_uri"
    private const val TREE_URIS = "tree_uris"

    fun set(context: Context, uri: Uri) {
        save(context, listOf(uri))
    }

    fun add(context: Context, uri: Uri) {
        save(context, getAll(context) + uri)
    }

    fun getAll(context: Context): List<Uri> = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = preferences.getStringSet(TREE_URIS, null)
        (saved?.mapNotNull { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
            ?: listOfNotNull(preferences.getString(TREE_URI, null)?.let(Uri::parse)))
            .distinctBy(Uri::toString)
    }.getOrDefault(emptyList())

    fun get(context: Context): Uri? = getAll(context).lastOrNull()

    fun removeRoot(context: Context, rootPath: String) {
        val normalizedRoot = normalizePath(rootPath)
        val remaining = getAll(context).filterNot { uri ->
            normalizePath(LocalFolderRootResolver.resolve(context, uri).orEmpty()) == normalizedRoot
        }
        save(context, remaining)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(TREE_URI)
            .remove(TREE_URIS)
            .apply()
    }

    private fun save(context: Context, uris: List<Uri>) {
        val values = uris.distinctBy(Uri::toString).map(Uri::toString).toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(TREE_URI)
            .putStringSet(TREE_URIS, values)
            .apply()
    }

    private fun normalizePath(path: String): String = path
        .replace('\\', '/')
        .trimEnd('/')
        .ifEmpty { "/" }
}
