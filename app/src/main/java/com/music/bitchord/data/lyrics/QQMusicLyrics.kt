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
import kotlinx.serialization.json.booleanOrNull
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
        val rows = root?.paxRows()
        if (rows != null) {
            if (type != "line") {
                // Handles object responses while retaining SongSync's part,
                // syllable and backing-vocal timings when they are available.
                PaxSenix.parseTimedApple(raw)
                    ?.takeIf { lines -> lines.any { it.isWordSynced && it.text.isNotBlank() } }
                    ?.map(::removeCjkSpacing)
                    ?.coalesceCjkCharacterRows()
                    ?.let { return it }
            }
            val hasWordTiming = type != "line" && rows.any { element ->
                (element as? JsonObject)?.array("text")?.any { chunk ->
                    (chunk as? JsonObject)?.long("timestamp") != null
                } == true
            }
            parseLineLyrics(rows, useWordTiming = hasWordTiming)?.let { return it }
            // Do not flatten a structured QQ response through ProviderLyrics:
            // that turns character chunks into untimed, one-character rows.
            if (type in setOf("line", "syllable") || rows.any { (it as? JsonObject)?.long("timestamp") != null }) {
                return null
            }
        }
        return ProviderLyrics.parse(raw)
    }

    /** QQ sometimes emits a timed run for every kana/kanji character. Keep each
     * timing run for the sweep, but do not insert Latin word spaces between CJK
     * characters: those spaces made short Japanese lines wrap vertically. */
    private fun removeCjkSpacing(line: LyricLine): LyricLine = line.copy(
        text = if (line.words.isEmpty()) {
            line.text.withoutCjkRunSpaces()
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

    private fun String.withoutCjkRunSpaces(): String = buildString {
        for (index in indices) {
            val character = this@withoutCjkRunSpaces[index]
            if (character.isWhitespace() && lastOrNull()?.isUnspacedScript() == true) {
                val next = this@withoutCjkRunSpaces.drop(index + 1).firstOrNull { !it.isWhitespace() }
                if (next?.isUnspacedScript() == true) continue
            }
            append(character)
        }
    }

    private fun JsonElement.paxRows(): JsonArray? = when (this) {
        is JsonArray -> this
        else -> arrayDeep("content")
    }

    private fun parseLineLyrics(rows: JsonArray, useWordTiming: Boolean): List<LyricLine>? {
        val lines = rows.mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: return@mapIndexedNotNull null
            val chunks = row.textChunks("text")
            val nextLineTime = (rows.getOrNull(index + 1) as? JsonObject)?.long("timestamp")
            val lineEnd = row.long("endtime")
            val timestamp = row.long("timestamp")
                ?: chunks.mapNotNull(PaxText::timestampMs).minOrNull()
                ?: return@mapIndexedNotNull null
            val words = if (useWordTiming) timedWords(chunks, lineEnd, nextLineTime) else emptyList()
            val text = if (words.isNotEmpty()) renderWords(words) else renderPaxText(chunks)
            if (text.isBlank()) return@mapIndexedNotNull null
            val backgroundChunks = row.textChunks("backgroundText")
            val background = if (row.boolean("background") == true && backgroundChunks.isNotEmpty()) {
                val backgroundWords = timedWords(backgroundChunks, lineEnd, nextLineTime)
                val backgroundText = if (backgroundWords.isNotEmpty()) renderWords(backgroundWords)
                else renderPaxText(backgroundChunks)
                backgroundText.takeIf(String::isNotBlank)?.let { textValue ->
                    LyricLine(
                        timeMs = backgroundChunks.mapNotNull(PaxText::timestampMs).minOrNull() ?: timestamp,
                        text = textValue,
                        words = backgroundWords,
                    )
                }
            } else null
            LyricLine(
                timeMs = timestamp,
                text = text,
                words = words,
                sungUntilMs = lineEnd?.takeIf { it > timestamp },
                background = background,
                alignment = if (row.boolean("oppositeTurn") == true) LyricAlignment.End else LyricAlignment.Start,
            )
        }.sortedBy(LyricLine::timeMs)
        return lines.coalesceCjkCharacterRows().withInstrumentalGaps().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.textChunks(key: String): List<PaxText> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonObject -> element.string("text")?.let { text ->
                    PaxText(
                        text = text,
                        timestampMs = element.long("timestamp"),
                        endMs = element.long("endtime"),
                        part = element.boolean("part"),
                    )
                }
                is JsonPrimitive -> element.contentOrNull?.let { PaxText(it, null, null, null) }
                else -> null
            }
        }.ifEmpty {
            string(key)?.let { listOf(PaxText(it, null, null, null)) }.orEmpty()
        }

    private fun timedWords(
        chunks: List<PaxText>,
        lineEndMs: Long?,
        nextLineMs: Long?,
    ): List<LyricWord> {
        if (chunks.isEmpty() || chunks.any { it.timestampMs == null }) return emptyList()
        val runs = chunks.mapIndexedNotNull { index, chunk ->
            val text = chunk.text.trim().takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
            val start = chunk.timestampMs ?: return@mapIndexedNotNull null
            val end = chunk.endMs
                ?: chunks.getOrNull(index + 1)?.timestampMs
                ?: lineEndMs
                ?: nextLineMs
                ?: (start + 800L)
            TimedRun(start, maxOf(start, end), if (chunk.part == true) text else "$text ")
        }
        return wordsFromRuns(runs, spacingIsExplicit = true)
    }

    private fun renderWords(words: List<LyricWord>): String {
        if (words.none { word -> word.text.any { it.isUnspacedScript() } }) {
            return words.joinToString(" ") { it.text }
        }
        return words.foldIndexed(StringBuilder()) { index, text, word ->
            if (index > 0) {
                val previous = words[index - 1].text.lastOrNull()
                val next = word.text.firstOrNull()
                if (previous != null && next != null &&
                    !previous.isUnspacedScript() && !next.isUnspacedScript()
                ) text.append(' ')
            }
            text.append(word.text)
        }.toString()
    }

    private fun renderPaxText(chunks: List<PaxText>): String = buildString {
        var previous: PaxText? = null
        chunks.forEach { chunk ->
            val text = chunk.text.trim()
            if (text.isEmpty()) return@forEach
            val before = previous
            val previousChar = before?.text?.trim()?.lastOrNull()
            val nextChar = text.firstOrNull()
            val separatedText = previousChar != null && nextChar != null &&
                !previousChar.isUnspacedScript() && !nextChar.isUnspacedScript()
            if (isNotEmpty() && separatedText && before?.part != true) append(' ')
            append(text)
            previous = chunk
        }
    }

    private fun List<LyricLine>.coalesceCjkCharacterRows(): List<LyricLine> {
        if (count { it.isCjkCharacterRow() } < 2) return this
        val result = mutableListOf<LyricLine>()
        var index = 0
        while (index < size) {
            val first = this[index]
            if (!first.isCjkCharacterRow()) {
                result += first
                index++
                continue
            }
            val group = mutableListOf(first)
            var next = index + 1
            while (next < size) {
                val candidate = this[next]
                val previous = group.last()
                if (!candidate.isCjkCharacterRow() ||
                    candidate.timeMs - previous.timeMs > MAX_CJK_CHARACTER_GAP_MS
                ) break
                group += candidate
                next++
            }
            if (group.size == 1) {
                result += first
            } else {
                val words = group.mapIndexed { wordIndex, line ->
                    val end = group.getOrNull(wordIndex + 1)?.timeMs
                        ?: line.sungUntilMs
                        ?: (line.timeMs + 800L)
                    LyricWord(line.timeMs, maxOf(line.timeMs, end), line.text)
                }
                result += LyricLine(first.timeMs, group.joinToString("") { it.text }, words)
            }
            index = next.coerceAtLeast(index + 1)
        }
        return result
    }

    private fun LyricLine.isCjkCharacterRow(): Boolean =
        text.length == 1 && text.first().isUnspacedScript() &&
            background == null && alignment == LyricAlignment.Start

    private data class PaxText(
        val text: String,
        val timestampMs: Long?,
        val endMs: Long?,
        val part: Boolean?,
    )

    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement.array(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

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

    private const val MAX_CJK_CHARACTER_GAP_MS = 2_500L
}
