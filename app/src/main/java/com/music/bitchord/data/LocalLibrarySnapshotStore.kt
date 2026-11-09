package com.music.bitchord.data

import android.content.Context
import android.util.AtomicFile
import com.music.bitchord.data.model.Song
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * A small, app-private snapshot of the local library used only for the first
 * frame after process restart. MediaStore remains authoritative: the snapshot
 * is replaced after each successful scan.
 */
internal object LocalLibrarySnapshotStore {
    private const val FILE_NAME = "local-library-snapshot.json"
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 12 * 1024 * 1024
    private val lock = Any()

    fun read(context: Context, fingerprint: String): List<Song>? = synchronized(lock) {
        runCatching {
            val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
            val bytes = file.openRead().use { input ->
                val output = ByteArray(MAX_FILE_BYTES + 1)
                var size = 0
                while (size < output.size) {
                    val count = input.read(output, size, output.size - size)
                    if (count < 0) break
                    size += count
                }
                require(size <= MAX_FILE_BYTES) { "Local library snapshot is too large" }
                output.copyOf(size)
            }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.optInt("version") != VERSION || root.optString("fingerprint") != fingerprint) {
                return@synchronized null
            }
            val rows = root.optJSONArray("songs") ?: return@synchronized null
            buildList {
                for (index in 0 until rows.length()) {
                    runCatching { rows.optJSONObject(index)?.toSong() }.getOrNull()?.let(::add)
                }
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    suspend fun write(context: Context, fingerprint: String, songs: List<Song>) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val atomicFile = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))
            var stream: FileOutputStream? = null
            runCatching {
                val payload = JSONObject()
                    .put("version", VERSION)
                    .put("fingerprint", fingerprint)
                    .put("songs", JSONArray().apply { songs.forEach { put(it.toJson()) } })
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                if (payload.size > MAX_FILE_BYTES) {
                    atomicFile.delete()
                    return@runCatching
                }
                stream = atomicFile.startWrite()
                stream!!.write(payload)
                atomicFile.finishWrite(stream)
                stream = null
            }.onFailure {
                stream?.let(atomicFile::failWrite)
            }
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching { AtomicFile(File(context.applicationContext.filesDir, FILE_NAME)).delete() }
        }
    }

    private fun Song.toJson() = JSONObject().apply {
        put("id", videoId)
        put("title", title)
        put("artist", artist)
        putNullable("art", thumbnailUrl)
        putNullable("duration", durationText)
        putNullable("artistId", artistId)
        putNullable("albumId", albumId)
        putNullable("album", albumName)
        putNullable("localUri", localUri)
        putNullable("downloadFormat", downloadFormat)
        putNullable("localPath", localPath)
        putNullable("folder", localFolderPath)
        putNullable("albumArtist", albumArtist)
        putNullable("sourceQuality", sourceQuality)
        localDateAddedSeconds?.let { put("added", it) }
        localDateModifiedSeconds?.let { put("modified", it) }
        trackNumber?.let { put("track", it) }
        if (isExplicit != null) put("explicit", isExplicit)
    }

    private fun JSONObject.toSong(): Song? {
        val id = optString("id").takeIf(String::isNotBlank) ?: return null
        return Song(
            videoId = id,
            title = optString("title"),
            artist = optString("artist"),
            thumbnailUrl = optNullableString("art"),
            durationText = optNullableString("duration"),
            artistId = optNullableString("artistId"),
            albumId = optNullableString("albumId"),
            albumName = optNullableString("album"),
            localUri = optNullableString("localUri"),
            downloadFormat = optNullableString("downloadFormat"),
            localPath = optNullableString("localPath"),
            localFolderPath = optNullableString("folder"),
            localDateAddedSeconds = if (has("added")) optLong("added") else null,
            localDateModifiedSeconds = if (has("modified")) optLong("modified") else null,
            albumArtist = optNullableString("albumArtist"),
            trackNumber = if (has("track")) optInt("track").takeIf { it > 0 } else null,
            sourceQuality = optNullableString("sourceQuality"),
            isExplicit = if (has("explicit")) optBoolean("explicit") else null,
        )
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)
}
