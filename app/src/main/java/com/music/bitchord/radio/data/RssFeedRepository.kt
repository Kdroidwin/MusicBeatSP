package com.music.bitchord.radio.data

import android.net.Uri
import com.music.bitchord.data.Http
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.security.MessageDigest

object RssFeedRepository {
    private const val MAX_FEED_BYTES = 6 * 1024 * 1024

    fun fetch(feed: RssFeed): Pair<String, List<RssEpisode>> {
        val request = Request.Builder()
            .url(feed.url)
            .header("User-Agent", "MusicBeat/2.9 RSS reader")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .build()
        Http.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Feed returned HTTP ${response.code}" }
            val xml = Http.readBoundedUtf8(response.body, MAX_FEED_BYTES)
                ?: error("Feed response was empty")
            val document = Jsoup.parse(xml, feed.url, Parser.xmlParser())
            val root = document.children().firstOrNull() ?: error("Feed XML has no root element")
            val channel = root.directChild("channel") ?: root
            val title = channel.directChildText("title")?.takeIf(String::isNotBlank) ?: feed.title
            val feedImage = channel.descendantsAndSelf()
                .firstOrNull { it.localTag() == "image" && it.hasAttr("href") }
                ?.attr("href")
                ?: channel.directChild("image")?.directChildText("url")
                ?: channel.directChildText("image")
            val episodes = (document.getElementsByTag("item") + document.getElementsByTag("entry"))
                .distinct()
                .mapNotNull { item -> parseEpisode(item, feed, title, feedImage) }
                .distinctBy(RssEpisode::id)
                .take(500)
            return title to episodes
        }
    }

    private fun parseEpisode(item: Element, feed: RssFeed, feedTitle: String, feedImage: String?): RssEpisode? {
        val enclosure = item.directChild("enclosure")
        val atomEnclosure = item.children().firstOrNull {
            it.localTag() == "link" && it.attr("rel").equals("enclosure", ignoreCase = true)
        }
        val mediaContent = item.descendantsAndSelf().firstOrNull {
            it.localTag() == "content" && isAudioType(it.attr("type")) && it.hasAttr("url")
        }
        val audioUrl = sequenceOf(
            enclosure?.attr("url"), atomEnclosure?.attr("href"), mediaContent?.attr("url"),
        ).filterNotNull().firstOrNull(::isHttpUrl) ?: return null
        val audioMimeType = sequenceOf(
            enclosure?.attr("type"), atomEnclosure?.attr("type"), mediaContent?.attr("type"),
        ).filterNotNull().firstOrNull { it.startsWith("audio/", ignoreCase = true) }
        val title = item.directChildText("title")?.trim().orEmpty().ifBlank {
            item.directChildText("summary")?.trim().orEmpty()
        }.ifBlank { Uri.parse(audioUrl).lastPathSegment.orEmpty() }
        val description = item.directChildText("encoded", "content:encoded", "description", "summary", "subtitle")
            ?.let { Jsoup.parse(it).text() }
            .orEmpty()
            .take(12_000)
        val image = item.descendantsAndSelf()
            .firstOrNull { it.localTag() == "image" && it.hasAttr("href") }
            ?.attr("href")
            ?: item.descendantsAndSelf().firstOrNull { it.localTag() == "thumbnail" && it.hasAttr("url") }
                ?.attr("url")
            ?: feedImage
        val published = item.directChildText("pubDate", "published", "updated", "date")?.trim()?.takeIf(String::isNotBlank)
        val guid = item.directChildText("guid", "id", "link")?.trim()?.takeIf(String::isNotBlank) ?: audioUrl
        val durationMs = item.descendantsAndSelf().firstOrNull { it.localTag() == "duration" }
            ?.text()?.trim()?.let(::parseDuration)
        return RssEpisode(
            id = "rss-${sha256(feed.id + "\u0000" + guid)}",
            feedId = feed.id,
            feedTitle = feedTitle,
            title = title.take(240),
            description = description,
            publishedAt = published,
            audioUrl = audioUrl,
            imageUrl = image?.takeIf(::isHttpUrl),
            durationMs = durationMs,
            audioMimeType = audioMimeType,
        )
    }

    private fun Element.directChild(name: String): Element? = children().firstOrNull { it.localTag() == name.substringAfter(':') }

    private fun Element.directChildText(vararg names: String): String? {
        val wanted = names.map { it.substringAfter(':').lowercase() }.toSet()
        return children().firstOrNull { it.localTag() in wanted }?.text()?.takeIf(String::isNotBlank)
    }

    private fun Element.localTag(): String = nodeName().substringAfter(':').lowercase()
    private fun Element.descendantsAndSelf(): Sequence<Element> = sequenceOf(this) + select("*").asSequence()

    private fun isAudioType(type: String): Boolean = type.startsWith("audio/", ignoreCase = true)

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun parseDuration(value: String): Long? = runCatching {
        val parts = value.split(':').map(String::toLong)
        val seconds = when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        (seconds * 1000L).takeIf { it > 0 }
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }
}
