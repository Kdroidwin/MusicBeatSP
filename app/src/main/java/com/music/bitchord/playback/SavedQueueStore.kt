package com.music.bitchord.playback

import android.content.Context
import android.util.AtomicFile
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** A user-named snapshot of the player queue. Tracks remain independent of playlist records. */
data class SavedQueue(
    val id: String,
    val name: String,
    val songs: List<Song>,
    val currentIndex: Int,
    val positionMs: Long,
)

/** Small, app-private and atomic persistence for multiple independent playback queues. */
object SavedQueueStore {
    val queues = MutableStateFlow<List<SavedQueue>>(emptyList())

    private val lock = Any()
    @Volatile private var file: AtomicFile? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            file = AtomicFile(File(context.applicationContext.filesDir, "saved-queues.json"))
            queues.value = readLocked()
        }
    }

    fun saveAs(name: String, songs: List<Song>, currentIndex: Int, positionMs: Long): Boolean = synchronized(lock) {
        val cleanName = name.trim().take(MAX_NAME_LENGTH)
        if (cleanName.isBlank() || songs.isEmpty() || songs.size > MAX_SONGS || queues.value.size >= MAX_QUEUES ||
            queues.value.any { it.name.equals(cleanName, ignoreCase = true) }
        ) {
            return@synchronized false
        }
        val updated = queues.value + SavedQueue(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            songs = songs.toList(),
            currentIndex = currentIndex.coerceIn(songs.indices),
            positionMs = positionMs.coerceAtLeast(0L),
        )
        writeLocked(updated)
    }

    fun replace(id: String, songs: List<Song>, currentIndex: Int, positionMs: Long): Boolean = synchronized(lock) {
        if (songs.isEmpty() || songs.size > MAX_SONGS) return@synchronized false
        if (queues.value.none { it.id == id }) return@synchronized false
        val updated = queues.value.map { queue ->
            if (queue.id == id) queue.copy(
                songs = songs.toList(),
                currentIndex = currentIndex.coerceIn(songs.indices),
                positionMs = positionMs.coerceAtLeast(0L),
            ) else queue
        }
        writeLocked(updated)
    }

    fun delete(id: String) = synchronized(lock) {
        val updated = queues.value.filterNot { it.id == id }
        if (updated.size != queues.value.size) writeLocked(updated)
    }

    fun exportQueues(): List<SavedQueue> = synchronized(lock) { queues.value.toList() }

    /** Restore bounded, validated queue data without trusting the backup file's contents. */
    fun importQueues(incoming: List<SavedQueue>): Boolean = synchronized(lock) {
        val ids = HashSet<String>()
        val names = HashSet<String>()
        val normalized = incoming.asSequence()
            .take(MAX_QUEUES)
            .mapNotNull { queue ->
                val name = queue.name.trim().take(MAX_NAME_LENGTH)
                val songs = queue.songs.asSequence()
                    .filter { it.videoId.isNotBlank() }
                    .take(MAX_SONGS)
                    .toList()
                if (name.isBlank() || songs.isEmpty() || !names.add(name.lowercase())) return@mapNotNull null
                val id = queue.id.takeIf { it.isNotBlank() && ids.add(it) } ?: UUID.randomUUID().toString().also(ids::add)
                queue.copy(
                    id = id,
                    name = name,
                    songs = songs,
                    currentIndex = queue.currentIndex.coerceIn(songs.indices),
                    positionMs = queue.positionMs.coerceAtLeast(0L),
                )
            }
            .toList()
        writeLocked(normalized)
    }

    private fun readLocked(): List<SavedQueue> {
        val atomicFile = file ?: return emptyList()
        return runCatching {
            val bytes = atomicFile.openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_FILE_BYTES) { "Saved queue file is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.isEmpty()) return@runCatching emptyList()
            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length().coerceAtMost(MAX_QUEUES)) {
                    runCatching { array.optJSONObject(index)?.toSavedQueue() }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(value: List<SavedQueue>): Boolean {
        val atomicFile = file ?: return false
        return runCatching {
            val payload = JSONArray().apply { value.forEach { put(it.toJson()) } }
                .toString()
                .toByteArray(Charsets.UTF_8)
            if (payload.size > MAX_FILE_BYTES) return false
            var stream: FileOutputStream? = null
            try {
                stream = atomicFile.startWrite()
                stream.write(payload)
                atomicFile.finishWrite(stream)
                queues.value = value
                true
            } catch (error: Exception) {
                stream?.let(atomicFile::failWrite)
                throw error
            }
        }.getOrDefault(false)
    }

    private fun SavedQueue.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("index", currentIndex)
        put("position", positionMs)
        put("songs", JSONArray().apply { songs.take(MAX_SONGS).forEach { put(it.toJson()) } })
    }

    private fun JSONObject.toSavedQueue(): SavedQueue? {
        val id = optString("id").takeIf(String::isNotBlank) ?: return null
        val name = optString("name").trim().take(MAX_NAME_LENGTH).takeIf(String::isNotBlank) ?: return null
        val array = optJSONArray("songs") ?: return null
        val songs = buildList {
            for (index in 0 until array.length().coerceAtMost(MAX_SONGS)) {
                runCatching { array.optJSONObject(index)?.toSong() }.getOrNull()?.let(::add)
            }
        }
        if (songs.isEmpty()) return null
        return SavedQueue(
            id = id,
            name = name,
            songs = songs,
            currentIndex = optInt("index", 0).coerceIn(songs.indices),
            positionMs = optLong("position", 0L).coerceAtLeast(0L),
        )
    }

    private fun Song.toJson() = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        putNullable("thumbnailUrl", thumbnailUrl)
        putNullable("durationText", durationText)
        putNullable("artistId", artistId)
        putNullable("albumId", albumId)
        putNullable("albumName", albumName)
        put("isVideo", isVideo)
        put("isVideoOrigin", isVideoOrigin)
        putNullable("setVideoId", setVideoId)
        put("fromAutoplay", fromAutoplay)
        putNullable("radioName", radioName)
        putNullable("localUri", localUri)
        putNullable("localPath", localPath)
        putNullable("downloadFormat", downloadFormat)
        if (isExplicit != null) put("isExplicit", isExplicit)
    }

    private fun JSONObject.toSong(): Song? {
        val id = optString("videoId").takeIf(String::isNotBlank) ?: return null
        return Song(
            videoId = id,
            title = optString("title"),
            artist = optString("artist"),
            thumbnailUrl = optNullableString("thumbnailUrl"),
            durationText = optNullableString("durationText"),
            artistId = optNullableString("artistId"),
            albumId = optNullableString("albumId"),
            albumName = optNullableString("albumName"),
            isVideo = optBoolean("isVideo", false),
            isVideoOrigin = optBoolean("isVideoOrigin", optBoolean("isVideo", false)),
            setVideoId = optNullableString("setVideoId"),
            fromAutoplay = optBoolean("fromAutoplay", false),
            radioName = optNullableString("radioName"),
            localUri = optNullableString("localUri"),
            localPath = optNullableString("localPath"),
            downloadFormat = optNullableString("downloadFormat"),
            isExplicit = if (has("isExplicit")) optBoolean("isExplicit") else null,
        )
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private const val MAX_NAME_LENGTH = 80
    private const val MAX_QUEUES = 20
    private const val MAX_SONGS = 1_000
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
}
