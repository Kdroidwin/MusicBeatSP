package com.music.bitchord.feature.localmusic.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.bouncycastle.crypto.engines.BlowfishEngine
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.charset.StandardCharsets

data class MusicoletPlaylistEntry(
    val location: String,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val durationMillis: Long? = null,
)

data class ParsedMusicoletPlaylist(
    val name: String,
    val entries: List<MusicoletPlaylistEntry>,
)

/** Reads only Musicolet's playlist members. Backup internals are version-sensitive. */
object MusicoletBackupParser {
    private val encryptionKey = "JSTMUSIC_2".toByteArray(StandardCharsets.US_ASCII)

    fun parsePlaylist(memberName: String, encryptedData: ByteArray): ParsedMusicoletPlaylist? {
        if (!memberName.substringAfterLast('/').endsWith(".mpl", ignoreCase = true)) return null
        if (encryptedData.isEmpty() || encryptedData.size % 8 != 0) return null

        val plaintext = runCatching { decrypt(encryptedData) }.getOrNull() ?: return null
        val clean = plaintext.toString(StandardCharsets.UTF_8)
            .trimEnd('\u0000', '\u0008', '\r', '\n', '\t', ' ')
        val root = runCatching { Json.parseToJsonElement(clean).jsonObject }.getOrNull() ?: return null
        val paths = root.stringArray("S_P") ?: return null
        val titles = root.stringArray("S_T").orEmpty()
        val albums = root.stringArray("S_AL").orEmpty()
        val artists = root.stringArray("S_AR").orEmpty()
        val durations = root.longArray("S_D").orEmpty()

        val entries = paths.mapIndexedNotNull { index, location ->
            location.takeIf(String::isNotBlank)?.let {
                MusicoletPlaylistEntry(
                    location = it,
                    title = titles.getOrNull(index)?.takeIf(String::isNotBlank),
                    album = albums.getOrNull(index)?.takeIf(String::isNotBlank),
                    artist = artists.getOrNull(index)?.takeIf(String::isNotBlank),
                    durationMillis = durations.getOrNull(index)?.takeIf { value -> value > 0L },
                )
            }
        }
        val suggestedName = memberName.substringAfterLast('/').substringBeforeLast('.')
            .trim().ifBlank { "Imported Playlist" }
        return ParsedMusicoletPlaylist(suggestedName, entries)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val cipher = BlowfishEngine()
        cipher.init(false, KeyParameter(encryptionKey))
        val plain = ByteArray(data.size)
        for (offset in data.indices step cipher.blockSize) {
            cipher.processBlock(data, offset, plain, offset)
        }
        cipher.reset()
        if (plain.isEmpty()) return plain
        val paddingLength = plain.last().toInt() and 0xff
        return if (paddingLength in 1..8 && plain.takeLast(paddingLength).all { (it.toInt() and 0xff) == paddingLength }) {
            plain.copyOf(plain.size - paddingLength)
        } else {
            plain
        }
    }

    private fun JsonObject.stringArray(key: String): List<String>? =
        (this[key] as? JsonArray)?.map { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull().orEmpty()
        }

    private fun JsonObject.longArray(key: String): List<Long?>? =
        (this[key] as? JsonArray)?.map { element -> runCatching { element.jsonPrimitive.longOrNull }.getOrNull() }
}
