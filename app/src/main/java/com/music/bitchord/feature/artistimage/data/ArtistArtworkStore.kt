package com.music.bitchord.feature.artistimage.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.music.bitchord.feature.tageditor.data.TagLibWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/** User-selected artist portraits, kept in app-private storage and included in backups. */
object ArtistArtworkStore {
    private const val PREFS = "artist_artwork_overrides"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_IMAGE_PIXELS = 40_000_000L

    data class BackupArtwork(
        val artistName: String,
        val mimeType: String,
        val bytes: ByteArray,
        /** Stable remote artist identity; null keeps older name-keyed backups compatible. */
        val artistKey: String? = null,
    )

    private data class Entry(
        val artistName: String,
        val artistKey: String,
        val mimeType: String,
        val fileName: String,
    )

    private val lock = Any()
    private var appContext: Context? = null
    private var entries: Map<String, Entry> = emptyMap()
    private val _imageUris = MutableStateFlow<Map<String, String>>(emptyMap())
    val imageUris: StateFlow<Map<String, String>> = _imageUris.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            entries = readEntries(context)
            publishEntries(context)
        }
    }

    fun keyFor(artistName: String): String = artistName.trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    /** Remote artists with the same display name remain separate; local names keep legacy behavior. */
    fun identityKeyFor(artistName: String, browseId: String? = null): String {
        val remoteId = browseId?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("local:") }
        return remoteId?.let { "browse:$it" } ?: keyFor(artistName)
    }

    fun imageUriFor(
        artistName: String,
        uriMap: Map<String, String> = _imageUris.value,
        browseId: String? = null,
    ): String? {
        val remoteId = browseId?.takeIf { it.isNotBlank() && !it.startsWith("local:") }
        // A remote channel id is the artist identity. Falling back to a shared
        // display-name entry would attach one same-named artist's portrait to
        // another channel, the collision this store's identity keys prevent.
        return if (remoteId != null) {
            uriMap[identityKeyFor(artistName, remoteId)]
        } else {
            uriMap[keyFor(artistName)]
        }
    }

    suspend fun set(
        context: Context,
        artistName: String,
        source: Uri,
        artistKey: String? = null,
    ): Boolean {
        val name = artistName.trim().replace(Regex("\\s+"), " ")
        if (name.isBlank()) return false
        val artwork = TagLibWriter.prepareArtwork(context, source) ?: return false
        return withContext(Dispatchers.IO) {
            putBytes(context.applicationContext, name, artistKey ?: keyFor(name), artwork.mimeType, artwork.bytes)
        }
    }

    fun remove(context: Context, artistName: String, artistKey: String? = null): Boolean = synchronized(lock) {
        val key = artistKey ?: keyFor(artistName)
        val old = entries[key] ?: return false
        val updated = entries - key
        persist(context.applicationContext, updated)
        File(directory(context), old.fileName).delete()
        entries = updated
        publishEntries(context.applicationContext)
        true
    }

    /** Snapshot copied into the backup file; no content URI permissions are needed on restore. */
    suspend fun exportBackup(context: Context): List<BackupArtwork> = withContextIO {
        synchronized(lock) {
            val activeContext = context.applicationContext
            entries.mapNotNull { (_, entry) ->
                val file = File(directory(activeContext), entry.fileName)
                val bytes = runCatching { file.readBytes() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
                    ?: return@mapNotNull null
                BackupArtwork(entry.artistName, entry.mimeType, bytes, entry.artistKey)
            }
        }
    }

    /** Replaces overrides from a validated backup payload. */
    suspend fun importBackup(context: Context, artwork: List<BackupArtwork>) = withContext(Dispatchers.IO) {
        require(artwork.size <= 2000) { "Too many artist images in this backup" }
        val prepared = LinkedHashMap<String, Pair<Entry, ByteArray>>()
        var totalBytes = 0L
        artwork.forEach { item ->
            val name = item.artistName.trim().replace(Regex("\\s+"), " ")
            require(name.isNotBlank() && name.length <= 300) { "Invalid artist name in backup" }
            val key = item.artistKey?.trim()?.takeIf(String::isNotBlank) ?: keyFor(name)
            require(key.length <= 500) { "Invalid artist identity in backup" }
            require(item.mimeType == "image/jpeg" || item.mimeType == "image/png") {
                "Unsupported artist image format in backup"
            }
            validateImage(item.bytes)
            totalBytes += item.bytes.size
            require(totalBytes <= 96L * 1024 * 1024) { "Artist images exceed the backup size limit" }
            val extension = if (item.mimeType == "image/png") "png" else "jpg"
            prepared[key] = Entry(name, key, item.mimeType, fileName(key, item.bytes, extension)) to item.bytes
        }

        synchronized(lock) {
            val app = context.applicationContext
            val dir = directory(app).apply { mkdirs() }
            val replacement = LinkedHashMap<String, Entry>()
            prepared.forEach { (key, pair) ->
                val (entry, bytes) = pair
                writeAtomically(File(dir, entry.fileName), bytes)
                replacement[key] = entry
            }
            entries.values.forEach { old ->
                if (replacement.values.none { it.fileName == old.fileName }) {
                    File(dir, old.fileName).delete()
                }
            }
            persist(app, replacement)
            entries = replacement
            publishEntries(app)
        }
    }

    private fun putBytes(context: Context, artistName: String, artistKey: String, mimeType: String, bytes: ByteArray): Boolean =
        synchronized(lock) {
            if (mimeType !in setOf("image/jpeg", "image/png") || bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                return false
            }
            runCatching { validateImage(bytes) }.getOrElse { return false }
            val key = artistKey
            val extension = if (mimeType == "image/png") "png" else "jpg"
            // Give changed image bytes a new URI. Coil caches by URI, so
            // rewriting a stable artist.jpg path left the old portrait on
            // screen even though the new file had been saved successfully.
            val entry = Entry(artistName, key, mimeType, fileName(key, bytes, extension))
            val dir = directory(context).apply { mkdirs() }
            runCatching { writeAtomically(File(dir, entry.fileName), bytes) }.getOrElse { return false }
            val updated = entries + (key to entry)
            persist(context, updated)
            val previous = entries[key]
            entries = updated
            publishEntries(context)
            if (previous != null && previous.fileName != entry.fileName) {
                File(dir, previous.fileName).delete()
            }
            true
        }

    private fun validateImage(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "Artist image is too large" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && pixels <= MAX_IMAGE_PIXELS) {
            "Invalid artist image"
        }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Could not save artist image")
        }
    }

    private fun readEntries(context: Context): Map<String, Entry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyMap()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyMap()
        val dir = directory(context)
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("artistName").trim()
                val mime = item.optString("mimeType")
                val fileName = item.optString("fileName")
                val key = item.optString("artistKey").takeIf(String::isNotBlank) ?: keyFor(name)
                if (key.isBlank() || fileName.isBlank() || mime !in setOf("image/jpeg", "image/png")) continue
                if (File(dir, fileName).isFile) put(key, Entry(name, key, mime, fileName))
            }
        }
    }

    private fun persist(context: Context, values: Map<String, Entry>) {
        val array = JSONArray()
        values.values.forEach { entry ->
            array.put(
                JSONObject()
                    .put("artistName", entry.artistName)
                    .put("artistKey", entry.artistKey)
                    .put("mimeType", entry.mimeType)
                    .put("fileName", entry.fileName),
            )
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).commit()) {
            "Could not save artist image settings"
        }
    }

    private fun publishEntries(context: Context) {
        _imageUris.value = entries.mapValues { (_, entry) ->
            Uri.fromFile(File(directory(context), entry.fileName)).toString()
        }
    }

    private fun directory(context: Context): File = File(context.filesDir, "artist-artwork")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun fileName(artistKey: String, bytes: ByteArray, extension: String): String {
        val contentHash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return "${digest(artistKey)}-$contentHash.$extension"
    }

    private suspend fun <T> withContextIO(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
