package com.music.bitchord.radio.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RssFeed(val id: String, val url: String, val title: String)

data class RssEpisode(
    val id: String,
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val description: String,
    val publishedAt: String?,
    val audioUrl: String,
    val imageUrl: String?,
    val durationMs: Long?,
)

object RssFeedStore {
    private const val PREFS = "rss_radio_feeds"
    private const val KEY_FEEDS = "feeds"

    @Synchronized
    fun feeds(context: Context): List<RssFeed> = runCatching {
        val rows = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FEEDS, "[]"))
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val url = row.optString("url").trim()
                if (!isHttpUrl(url)) continue
                add(
                    RssFeed(
                        id = row.optString("id").takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
                        url = url,
                        title = row.optString("title").takeIf(String::isNotBlank) ?: Uri.parse(url).host.orEmpty(),
                    ),
                )
            }
        }.distinctBy(RssFeed::url)
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, rawUrl: String): List<RssFeed> {
        val url = rawUrl.trim()
        require(isHttpUrl(url)) { "Enter a valid HTTP or HTTPS feed URL." }
        val existing = feeds(context)
        require(existing.none { it.url.equals(url, ignoreCase = true) }) { "That feed has already been added." }
        return (existing + RssFeed(UUID.randomUUID().toString(), url, Uri.parse(url).host.orEmpty())).also {
            write(context, it)
        }
    }

    @Synchronized
    fun remove(context: Context, id: String): List<RssFeed> = feeds(context).filterNot { it.id == id }
        .also { write(context, it) }

    @Synchronized
    fun updateTitle(context: Context, id: String, title: String): List<RssFeed> = feeds(context).map {
        if (it.id == id && title.isNotBlank()) it.copy(title = title.trim().take(120)) else it
    }.also { write(context, it) }

    private fun write(context: Context, feeds: List<RssFeed>) {
        val rows = JSONArray().apply {
            feeds.forEach { feed ->
                put(JSONObject().put("id", feed.id).put("url", feed.url).put("title", feed.title))
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FEEDS, rows.toString()).apply()
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
