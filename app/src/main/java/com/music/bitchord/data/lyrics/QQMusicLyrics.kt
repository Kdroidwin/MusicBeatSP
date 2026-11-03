package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * QQ Music catalogue search and line/word-timed lyrics following SongSync's
 * provider flow (https://github.com/Lambada10/SongSync, GPL-3.0).
 */
object QQMusicLyrics {
    private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val LYRICS_URL = "https://paxsenix.alwaysdata.net/getQQLyrics.php"
    private const val MIN_TITLE_MATCH = 0.78
    private const val MIN_ARTIST_MATCH = 0.45

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        try {
            val candidates = search(title, artist)
            val selected = bestMatch(candidates, title, artist, durationMs, album)
            if (selected == null) {
                LyricsLog.i("QQ Music", "No sufficiently close catalogue result for '$title'")
                return@withContext null
            }
            LyricsLog.i("QQ Music", "Selected '${selected.title}' by ${selected.artists.joinToString()}")
            val payload = buildJsonObject {
                put("artist", buildJsonArray { selected.artists.forEach { addString(it) } })
                put("album", selected.album.orEmpty())
                put("id", selected.id)
                put("title", selected.title)
            }.toString()
            val response = lyricsPostJson(LYRICS_URL, payload)?.takeIf(String::isNotBlank)
                ?: return@withContext null
            parsePaxResponse(response)?.takeIf { lines -> lines.any { it.text.isNotBlank() } }
                ?.also { LyricsLog.s("QQ Music", "Returned ${it.size} lines") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LyricsLog.w("QQ Music", "Lookup failed (${error.javaClass.simpleName})")
            null
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addString(value: String) {
        add(kotlinx.serialization.json.JsonPrimitive(value))
    }

    private fun search(title: String, artist: String): List<Candidate> {
        val query = "$title $artist".trim()
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "utf8")
            .addQueryParameter("outCharset", "utf8")
            .addQueryParameter("platform", "yqq.json")
            .addQueryParameter("new_json", "1")
            .addQueryParameter("n", "20")
            .addQueryParameter("p", "1")
            .addQueryParameter("w", query)
            .build()
        val body = lyricsGet(
            url.toString(),
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Content-Type" to "application/json",
            ),
        ) ?: return emptyList()
        val root = runCatching { lyricsJson.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val songs = root.obj("data")?.obj("song")?.array("list") ?: return emptyList()
        return songs.mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            val id = song.long("id") ?: return@mapNotNull null
            val songTitle = song.string("title") ?: song.string("name") ?: return@mapNotNull null
            val artists = (song.array("singer") ?: song.array("artists")).orEmpty().mapNotNull { item ->
                (item as? JsonObject)?.string("name")?.takeIf(String::isNotBlank)
            }
            val albumObject = song.obj("album")
            val songAlbum = albumObject?.string("name") ?: song.string("album")
            val duration = song.long("interval")?.times(1_000L)
                ?: song.long("duration")?.let { if (it > 20_000L) it else it * 1_000L }
            Candidate(id, songTitle, artists, songAlbum, duration)
        }
    }

    private fun bestMatch(
        candidates: List<Candidate>,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): Candidate? {
        val targetTitle = title.normalized()
        if (targetTitle.isBlank()) return null
        val targetArtist = artist.normalizedNames()
        val targetAlbum = album.orEmpty().normalized()
        val targetDuration = durationMs.takeIf { it > 0L }
        val ranked = candidates.mapNotNull { candidate ->
            val titleScore = similarity(targetTitle, candidate.title.normalized())
            if (titleScore < MIN_TITLE_MATCH) return@mapNotNull null
            val artistScore = candidate.artists.joinToString(", ").normalizedNames().let { names ->
                if (targetArtist.isEmpty() || names.isEmpty()) null
                else targetArtist.maxOf { expected -> names.maxOf { actual -> similarity(expected, actual) } }
            }
            if (artistScore != null && artistScore < MIN_ARTIST_MATCH) return@mapNotNull null
            val durationDifference = if (targetDuration != null && candidate.durationMs != null) {
                abs(targetDuration - candidate.durationMs)
            } else null
            if (durationDifference != null && durationDifference > 20_000L &&
                !(titleScore >= 0.97 && (artistScore == null || artistScore >= 0.82))
            ) return@mapNotNull null
            val albumScore = if (targetAlbum.isNotBlank() && !candidate.album.isNullOrBlank()) {
                similarity(targetAlbum, candidate.album.normalized())
            } else null
            val parts = buildList {
                add(titleScore to 0.62)
                artistScore?.let { add(it to 0.28) }
                albumScore?.let { add(it to 0.06) }
                durationDifference?.let {
                    add((1.0 - (it.toDouble() / 20_000.0).coerceAtMost(1.0)) to 0.10)
                }
            }
            val weight = parts.sumOf { it.second }
            candidate to parts.sumOf { it.first * it.second } / weight
        }.sortedByDescending { it.second }

        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && best.second - runnerUp.second < 0.025 &&
            best.first.artists.joinToString().normalized() != runnerUp.first.artists.joinToString().normalized()
        ) return null
        return best.first
    }

    private fun parsePaxResponse(raw: String): List<LyricLine>? {
        val root = runCatching { lyricsJson.parseToJsonElement(raw) }.getOrNull()
        val type = root?.stringDeep("type")?.lowercase(Locale.ROOT)
        if (type == "line") return parseLineLyrics(root)
        // PaxSenix already understands SongSync's syllable-level `part` runs,
        // including word joins, syllable spans, and backing vocals.
        PaxSenix.parseTimedApple(raw)
            ?.takeIf { it.any(LyricLine::isWordSynced) }
            ?.map(::removeCjkSpacing)
            ?.let { return it }
        return ProviderLyrics.parse(raw)
    }

    /** QQ sometimes emits a timed run for every kana/kanji character. Keep each
     * timing run for the sweep, but do not insert Latin word spaces between CJK
     * characters: those spaces made short Japanese lines wrap vertically. */
    private fun removeCjkSpacing(line: LyricLine): LyricLine = line.copy(
        text = if (line.words.isEmpty()) {
            line.text
        } else if (line.words.none { word -> word.text.any { it.isUnspacedScript() } }) {
            line.words.joinToString(" ") { it.text }
        } else {
            line.words.foldIndexed(StringBuilder()) { index, text, word ->
                if (index > 0) {
                    val previous = line.words[index - 1].text.lastOrNull()
                    val next = word.text.firstOrNull()
                    if (previous != null && next != null &&
                        !previous.isUnspacedScript() && !next.isUnspacedScript()
                    ) text.append(' ')
                }
                text.append(word.text)
            }.toString()
        },
        background = line.background?.let(::removeCjkSpacing),
    )

    private fun parseLineLyrics(root: JsonElement): List<LyricLine>? {
        val rows = root.arrayDeep("content") ?: return null
        val lines = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val timestamp = row.long("timestamp") ?: return@mapNotNull null
            val words = row.array("text").orEmpty().mapNotNull { (it as? JsonObject)?.string("text") }
            val text = words.joinToString("").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val end = row.long("endtime")?.takeIf { it > timestamp }
            LyricLine(timeMs = timestamp, text = text, sungUntilMs = end)
        }.sortedBy(LyricLine::timeMs)
        return lines.withInstrumentalGaps().takeIf { it.isNotEmpty() }
    }

    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement.array(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

    private fun JsonElement.stringDeep(key: String): String? = when (this) {
        is JsonObject -> this[key]?.jsonPrimitive?.contentOrNull
            ?: values.firstNotNullOfOrNull { it.stringDeep(key) }
        is JsonArray -> firstNotNullOfOrNull { it.stringDeep(key) }
        else -> null
    }

    private fun JsonElement.arrayDeep(key: String): JsonArray? = when (this) {
        is JsonObject -> (this[key] as? JsonArray) ?: values.firstNotNullOfOrNull { it.arrayDeep(key) }
        is JsonArray -> firstNotNullOfOrNull { it.arrayDeep(key) }
        else -> null
    }

    private fun String.normalized(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun Char.isUnspacedScript(): Boolean =
        this in '\u3400'..'\u9FFF' || // Han
            this in '\u3040'..'\u30FF' || // hiragana and katakana
            this in '\u3000'..'\u303F' || // CJK punctuation
            this in '\uFF00'..'\uFFEF' || // full-width forms
            this in '\u0E00'..'\u0E7F' // Thai

    private fun String.normalizedNames(): List<String> = split(Regex("[,，、/&＆]+|\\b(?:feat(?:uring)?|ft)\\b", RegexOption.IGNORE_CASE))
        .map { it.normalized() }
        .filter(String::isNotBlank)

    private fun similarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (minOf(left.length, right.length) >= 4 && (left.contains(right) || right.contains(left))) {
            return 0.92 + 0.08 * minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length)
        }
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        left.forEachIndexed { leftIndex, leftChar ->
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            current.copyInto(previous)
        }
        return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
    }

    private data class Candidate(
        val id: Long,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )
}
