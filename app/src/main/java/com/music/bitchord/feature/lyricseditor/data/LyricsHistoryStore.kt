package com.music.bitchord.feature.lyricseditor.data

import android.content.Context
import android.util.AtomicFile
import com.music.bitchord.data.model.Song
import com.music.bitchord.feature.lyricseditor.domain.model.LyricsEditorSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** A saved lyric revision for the editor's local, per-track history. */
data class LyricsHistoryRevision(
    val id: String,
    val savedAtMs: Long,
    val text: String,
)

/**
 * Small, private, bounded revision log for lyrics edited in the app.
 *
 * Revisions are kept per track and destination. The current lyric file/tag remains
 * the source of truth; this store is only the editor's undoable saved history.
 */
object LyricsHistoryStore {
    private const val FILE_NAME = "lyrics_history_v1.json"
    private const val MAX_REVISIONS_PER_SOURCE = 30
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_REVISION_CHARS = 250_000
    private const val KEY = "key"
    private const val SOURCE = "source"
    private const val SAVED_AT = "savedAt"
    private const val TEXT = "text"
    private val lock = Any()

    fun read(
        context: Context,
        song: Song,
        source: LyricsEditorSource,
    ): List<LyricsHistoryRevision> = synchronized(lock) {
        val key = historyKey(song, source)
        readEntries(context).asRevisions(key, source.name)
    }

    /** Records a committed revision; identical consecutive saves are ignored. */
    fun record(
        context: Context,
        song: Song,
        source: LyricsEditorSource,
        text: String,
    ) = synchronized(lock) {
        if (text.length > MAX_REVISION_CHARS) return@synchronized
        val key = historyKey(song, source)
        val entries = readEntries(context)
        val previous = entries.asRevisions(key, source.name).lastOrNull()
        if (previous?.text == text) return@synchronized
        entries.put(
            JSONObject()
                .put(KEY, key)
                .put(SOURCE, source.name)
                .put(SAVED_AT, System.currentTimeMillis())
                .put("id", UUID.randomUUID().toString())
                .put(TEXT, text),
        )
        trimTrack(entries, key, source.name)
        trimTotalSize(entries)
        // Revision history is auxiliary. A full or unavailable storage device
        // must never turn a successful lyric save into a failed editor action.
        runCatching { writeEntries(context, entries) }
    }

    private fun historyKey(song: Song, source: LyricsEditorSource): String {
        val trackIdentity = song.localUri?.takeIf(String::isNotBlank)
            ?: song.localPath?.takeIf(String::isNotBlank)
            ?: song.videoId
        val identity = "$trackIdentity\u0000${source.name}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun readEntries(context: Context): JSONArray {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile || file.length() > MAX_FILE_BYTES) JSONArray()
            else JSONArray(file.readText(Charsets.UTF_8))
        }.getOrElse { JSONArray() }
    }

    private fun JSONArray.asRevisions(key: String, source: String): List<LyricsHistoryRevision> =
        (0 until length()).mapNotNull { index ->
            val entry = optJSONObject(index) ?: return@mapNotNull null
            if (entry.optString(KEY) != key || entry.optString(SOURCE) != source) return@mapNotNull null
            LyricsHistoryRevision(
                id = entry.optString("id").ifBlank { "${entry.optLong(SAVED_AT)}-$index" },
                savedAtMs = entry.optLong(SAVED_AT),
                text = entry.optString(TEXT),
            )
        }.sortedBy(LyricsHistoryRevision::savedAtMs)

    private fun trimTrack(entries: JSONArray, key: String, source: String) {
        val matching = (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            index.takeIf { entry.optString(KEY) == key && entry.optString(SOURCE) == source }
        }
        matching.take((matching.size - MAX_REVISIONS_PER_SOURCE).coerceAtLeast(0))
            .asReversed()
            .forEach(entries::remove)
    }

    private fun trimTotalSize(entries: JSONArray) {
        while (entries.toString().toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES && entries.length() > 1) {
            val oldestIndex = (0 until entries.length()).minByOrNull { entries.optJSONObject(it)?.optLong(SAVED_AT) ?: 0L }
                ?: return
            entries.remove(oldestIndex)
        }
    }

    private fun writeEntries(context: Context, entries: JSONArray) {
        val atomic = AtomicFile(File(context.filesDir, FILE_NAME))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(entries.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }
}
