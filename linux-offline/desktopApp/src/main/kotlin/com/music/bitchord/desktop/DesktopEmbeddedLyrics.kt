package com.music.bitchord.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.util.Locale

/**
 * The lyrics already sitting inside a local file.
 *
 * A track that was downloaded had its lyrics fetched once and written into the file; asking four
 * servers for them again on every play is a network round trip to arrive at a string already on
 * disk, and it is why a downloaded song showed nothing at all with the connection off.
 *
 * Two fields are read, in this order:
 *
 *  - `BITCHORD_LYRICS`, this app's own, holding the enhanced A2 form with the word timings intact.
 *  - the container's standard lyrics field, holding plain `[mm:ss.xx]` LRC.
 *
 * The second is what every other player writes, so it is the fallback rather than the exception —
 * which also means a library tagged by something else is read correctly here.
 *
 * Never throws. A file that is not one of the three containers, or is one and has no lyrics in it,
 * is a null, and the caller falls back to the network.
 */
internal object DesktopEmbeddedLyrics {

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: the metadata region is small in all three containers, but its
     * length is stated *by the file*, so a corrupt one could claim any number at all.
     */
    private const val MAX_TAG_BYTES = 16 * 1024 * 1024
    private const val MAX_MP4_MOOV_BYTES = 32 * 1024 * 1024
    private const val MAX_CONTAINER_SCAN_BYTES = 128L * 1024 * 1024

    /** The raw LRC text inside [path], or null when it has none. */
    fun read(path: Path?): String? {
        if (path == null || !Files.isRegularFile(path)) return null
        sidecar(path)?.let { return it }
        // FLAC comments can follow a large PICTURE block, and M4A files commonly put `moov` after
        // the audio payload. Read those structures by offset instead of assuming every useful tag
        // is near byte zero.
        flacFile(path)?.let { return it }
        mp4File(path)?.let { return it }
        matroskaTail(path)?.let { return it }
        val head = runCatching {
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(MAX_TAG_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        }.getOrNull() ?: return null
        return id3v2(head) ?: ogg(head) ?: fromBytes(head)
    }

    /** A normal same-name `.lrc` sidecar, or the lyrics file next to an exported playlist. */
    internal fun sidecar(path: Path): String? {
        val parent = path.parent ?: return null
        val baseName = path.fileName.toString().substringBeforeLast('.', path.fileName.toString())
        val sameName = runCatching {
            Files.newDirectoryStream(parent).use { stream ->
                stream.firstOrNull { candidate ->
                    Files.isRegularFile(candidate) && candidate.fileName.toString()
                        .substringBeforeLast('.', candidate.fileName.toString()).equals(baseName, ignoreCase = true) &&
                        candidate.fileName.toString().substringAfterLast('.', "").equals("lrc", ignoreCase = true)
                }
            }
        }.getOrNull()
        val playlistLyrics = if (path.fileName.toString().endsWith(".m3u8", ignoreCase = true)) {
            parent.resolve("lyrics.lrc").takeIf(Files::isRegularFile)
        } else {
            null
        }
        val file = sameName ?: playlistLyrics ?: return null
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size !in 1..MAX_TAG_BYTES.toLong()) return null
        return runCatching { Files.readString(file, StandardCharsets.UTF_8).removePrefix("\uFEFF") }
            .getOrNull()?.takeIf(String::isNotBlank)
    }

    /** MP3/ID3v2 USLT or a user-text LYRICS frame, including v2.2 tags. */
    private fun id3v2(bytes: ByteArray): String? {
        if (bytes.size < 10 || !bytes.startsWith(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))) return null
        val version = bytes[3].toInt() and 0xFF
        if (version !in 2..4) return null
        val tagSize = syncSafeInt(bytes, 6) ?: return null
        val end = (10L + tagSize).coerceAtMost(bytes.size.toLong()).toInt()
        var position = 10
        val flags = bytes[5].toInt() and 0xFF
        if (flags and 0x40 != 0) {
            if (position + 4 > end) return null
            val extended = if (version == 4) syncSafeInt(bytes, position) else readU32(bytes, position).toInt()
            if (extended == null || extended < 0) return null
            position += if (version == 4) extended else 4 + extended
        }

        while (position < end) {
            val headerSize = if (version == 2) 6 else 10
            if (position + headerSize > end || bytes[position] == 0.toByte()) break
            val frameId = String(bytes, position, if (version == 2) 3 else 4, StandardCharsets.ISO_8859_1)
            val frameSize = when (version) {
                2 -> ((bytes[position + 3].toInt() and 0xFF) shl 16) or
                    ((bytes[position + 4].toInt() and 0xFF) shl 8) or (bytes[position + 5].toInt() and 0xFF)
                4 -> syncSafeInt(bytes, position + 4) ?: break
                else -> readU32(bytes, position + 4).toInt()
            }
            if (frameSize <= 0 || frameSize > end - position - headerSize) break
            val bodyStart = position + headerSize
            val body = bytes.copyOfRange(bodyStart, bodyStart + frameSize)
            val lyric = when (frameId) {
                "USLT", "ULT" -> unsynchronizedLyrics(body)
                "TXXX", "TXX" -> userLyrics(body)
                else -> null
            }
            if (!lyric.isNullOrBlank()) return lyric
            position = bodyStart + frameSize
        }
        return null
    }

    private fun unsynchronizedLyrics(body: ByteArray): String? {
        if (body.size < 5) return null // encoding, three-byte language and a description terminator
        val encoding = body[0].toInt() and 0xFF
        val terminator = textTerminator(body, 4, encoding) ?: return null
        val start = terminator.first + terminator.second
        return decodeText(body, start, body.size, encoding)?.trim('\u0000', '\uFEFF', '\r', '\n', ' ')
            ?.takeIf(String::isNotBlank)
    }

    private fun userLyrics(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val encoding = body[0].toInt() and 0xFF
        val description = textTerminator(body, 1, encoding) ?: return null
        val label = decodeText(body, 1, description.first, encoding)?.trim('\u0000', '\uFEFF')
        if (!label.equals("lyrics", ignoreCase = true) && !label.equals("unsyncedlyrics", ignoreCase = true)) return null
        val start = description.first + description.second
        return decodeText(body, start, body.size, encoding)?.trim('\u0000', '\uFEFF', '\r', '\n', ' ')
            ?.takeIf(String::isNotBlank)
    }

    private fun textTerminator(bytes: ByteArray, from: Int, encoding: Int): Pair<Int, Int>? {
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        var index = from
        while (index + width <= bytes.size) {
            if (bytes[index] == 0.toByte() && (width == 1 || bytes[index + 1] == 0.toByte())) return index to width
            index += width
        }
        return null
    }

    private fun decodeText(bytes: ByteArray, start: Int, end: Int, encoding: Int): String? {
        if (start !in 0..end || end > bytes.size) return null
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return null
        }
        return runCatching { String(bytes, start, end - start, charset) }.getOrNull()
    }

    /** Vorbis comments in Ogg/Vorbis and the OpusTags packet in Ogg/Opus. */
    private fun ogg(bytes: ByteArray): String? {
        var page = 0
        val packet = ByteArrayOutputStream()
        while (page + 27 <= bytes.size) {
            val capture = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
            val at = bytes.indexOf(capture, page, bytes.size) ?: return null
            if (at + 27 > bytes.size) return null
            val segments = bytes[at + 26].toInt() and 0xFF
            val table = at + 27
            if (table + segments > bytes.size) return null
            var body = table + segments
            for (index in 0 until segments) {
                val length = bytes[table + index].toInt() and 0xFF
                if (body + length > bytes.size) return null
                packet.write(bytes, body, length)
                body += length
                if (length < 255) {
                    vorbisLyrics(packet.toByteArray())?.let { return it }
                    packet.reset()
                }
            }
            page = body
        }
        return null
    }

    private fun vorbisLyrics(packet: ByteArray): String? {
        val prefix = when {
            packet.startsWith(byteArrayOf(3) + "vorbis".toByteArray(StandardCharsets.US_ASCII)) -> 7
            packet.startsWith("OpusTags".toByteArray(StandardCharsets.US_ASCII)) -> 8
            else -> return null
        }
        var position = prefix
        fun littleInt(): Int? {
            if (position + 4 > packet.size) return null
            val value = (packet[position].toInt() and 0xFF) or
                ((packet[position + 1].toInt() and 0xFF) shl 8) or
                ((packet[position + 2].toInt() and 0xFF) shl 16) or
                ((packet[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }
        val vendorLength = littleInt()?.takeIf { it >= 0 && it <= packet.size - position } ?: return null
        position += vendorLength
        val count = littleInt()?.coerceIn(0, 4_096) ?: return null
        repeat(count) {
            val length = littleInt()?.takeIf { it >= 0 && it <= packet.size - position } ?: return null
            val entry = String(packet, position, length, StandardCharsets.UTF_8)
            position += length
            val name = entry.substringBefore('=').uppercase(Locale.ROOT)
            val value = entry.substringAfter('=', "")
            if (name in setOf("LYRICS", "UNSYNCEDLYRICS", WORD_LYRICS_FIELD) && value.isNotBlank()) return value
        }
        return null
    }

    /** FLAC's metadata chain is small; skip picture blocks without loading them into memory. */
    private fun flacFile(path: Path): String? = runCatching {
        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            val signature = ByteArray(4)
            if (input.readNBytes(signature, 0, signature.size) != signature.size || !signature.contentEquals(FLAC_MAGIC)) return null
            var scanned = 4L
            var last = false
            while (!last && scanned < MAX_CONTAINER_SCAN_BYTES) {
                val first = input.read()
                if (first < 0) return null
                val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
                if (b1 < 0 || b2 < 0 || b3 < 0) return null
                scanned += 4
                last = first and 0x80 != 0
                val type = first and 0x7F
                val length = (b1 shl 16) or (b2 shl 8) or b3
                if (length < 0 || scanned + length > MAX_CONTAINER_SCAN_BYTES) return null
                if (type == FLAC_VORBIS_COMMENT) {
                    val body = input.readNBytes(length)
                    if (body.size != length) return null
                    return vorbisComment(body, 0, body.size)
                }
                input.skipNBytes(length.toLong())
                scanned += length
            }
            null
        }
    }.getOrNull()

    /** Read a tail-positioned `moov` without loading the audio payload. */
    private fun mp4File(path: Path): String? = runCatching {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val fileSize = channel.size()
            var position = 0L
            while (position + 8 <= fileSize) {
                val header = readAt(channel, position, 8) ?: return null
                val declared = readU32(header, 0)
                val type = String(header, 4, 4, StandardCharsets.ISO_8859_1)
                val headerSize: Long
                val boxSize: Long
                if (declared == 1L) {
                    headerSize = 16L
                    val extendedSize = readAt(channel, position + 8, 8) ?: return null
                    boxSize = readU64(extendedSize, 0)
                } else {
                    headerSize = 8L
                    boxSize = if (declared == 0L) fileSize - position else declared
                }
                if (boxSize < headerSize || position + boxSize > fileSize) return null
                if (type == "moov") {
                    if (boxSize > MAX_MP4_MOOV_BYTES || boxSize > Int.MAX_VALUE) return null
                    val moov = readAt(channel, position, boxSize.toInt()) ?: return null
                    return mp4(moov)
                }
                position += boxSize
            }
            null
        }
    }.getOrNull()

    private fun readAt(channel: FileChannel, offset: Long, size: Int): ByteArray? {
        val buffer = ByteBuffer.allocate(size)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) return null
            position += read
        }
        return buffer.array()
    }

    /** EBML `Tags` may be written after the clusters, well beyond the initial metadata window. */
    private fun matroskaTail(path: Path): String? = runCatching {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val length = minOf(channel.size(), MAX_TAG_BYTES.toLong()).toInt()
            if (length <= 0) return null
            val tail = readAt(channel, channel.size() - length, length) ?: return null
            matroska(tail)
        }
    }.getOrNull()

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        var result = 0
        repeat(4) { index ->
            val byte = bytes[offset + index].toInt() and 0xFF
            if (byte and 0x80 != 0) return null
            result = (result shl 7) or byte
        }
        return result
    }

    /**
     * The raw LRC text in [head], whichever of the three containers it is.
     *
     * Split from [read] so the parsing can be checked against bytes a tagger just produced — the
     * round trip is the only thing that proves a reader and a writer agree.
     */
    internal fun fromBytes(head: ByteArray): String? {
        val found = when {
            head.startsWith(FLAC_MAGIC) -> flac(head)
            head.startsWith(MATROSKA_MAGIC) -> matroska(head)
            head.isMp4() -> mp4(head)
            else -> null
        }
        return found?.takeIf(String::isNotBlank)
    }

    // ── MP4 / M4A ────────────────────────────────────────────────────────

    private fun mp4(bytes: ByteArray): String? {
        val moov = topLevelBox(bytes, "moov") ?: return null
        // Exclusive, deliberately: the lyrics are the last item written into `ilst`, so their value
        // ends exactly on `moov`'s own end, and an inclusive bound rejects the one atom sought.
        val end = moov.last + 1
        return ilstText(bytes, moov.first, end, freeform = true)
            ?: ilstText(bytes, moov.first, end, freeform = false)
    }

    private fun topLevelBox(bytes: ByteArray, type: String): IntRange? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val declared = readU32(bytes, pos)
            var headerLen = 8
            var size = declared
            if (declared == 1L) {
                if (pos + 16 > bytes.size) return null
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (declared == 0L) {
                size = (bytes.size - pos).toLong()
            }
            if (size < headerLen || size > Int.MAX_VALUE) return null
            val end = (pos + size).toInt().coerceAtMost(bytes.size)
            if (String(bytes, pos + 4, 4, StandardCharsets.ISO_8859_1) == type) return pos until end
            pos += size.toInt()
        }
        return null
    }

    private fun ilstText(bytes: ByteArray, from: Int, endExclusive: Int, freeform: Boolean): String? {
        val marker = if (freeform) WORD_LYRICS_FIELD.toByteArray(StandardCharsets.UTF_8) else LYR_ATOM
        var at = from
        while (true) {
            val found = bytes.indexOf(marker, at, endExclusive) ?: return null
            // The value is the first `data` box after the name in both layouts: a freeform item is
            // mean/name/data, a standard one is type/data.
            val data = bytes.indexOf(DATA_ATOM, found, endExclusive) ?: return null
            dataText(bytes, data, endExclusive)?.let { return it }
            at = found + marker.size
        }
    }

    private fun dataText(bytes: ByteArray, dataAt: Int, endExclusive: Int): String? {
        val start = dataAt - 4
        if (start < 0 || dataAt + 12 > endExclusive) return null
        val size = readU32(bytes, start).toInt()
        if (size <= 16 || start + size > endExclusive) return null
        // Type indicator 1 is UTF-8 text; a cover's 13/14 is the other thing a `data` box holds,
        // and decoding a JPEG as a string is not a lyric.
        if (readU32(bytes, dataAt + 4).toInt() != 1) return null
        return String(bytes, dataAt + 12, start + size - (dataAt + 12), StandardCharsets.UTF_8)
    }

    // ── FLAC ─────────────────────────────────────────────────────────────

    private fun flac(bytes: ByteArray): String? {
        var pos = FLAC_MAGIC.size
        while (pos + 4 <= bytes.size) {
            val flags = bytes[pos].toInt() and 0xFF
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val start = pos + 4
            if (start + length > bytes.size) return null
            if (flags and 0x7F == FLAC_VORBIS_COMMENT) return vorbisComment(bytes, start, start + length)
            if (flags and 0x80 != 0) return null
            pos = start + length
        }
        return null
    }

    private fun vorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        fun u32(): Int? {
            if (pos + 4 > end) return null
            val value = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return value
        }
        val vendor = u32()?.takeIf { it >= 0 && pos.toLong() + it <= end.toLong() } ?: return null
        pos += vendor
        val count = u32()?.coerceIn(0, 4_096) ?: return null
        var plain: String? = null
        repeat(count) {
            val length = u32() ?: return plain
            if (length < 0 || pos + length > end) return plain
            val entry = String(bytes, pos, length, StandardCharsets.UTF_8)
            pos += length
            val name = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            // This app's own field wins outright; the standard one is held in case it is the only
            // one there.
            if (name == WORD_LYRICS_FIELD && value.isNotBlank()) return value
            if (name in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS") && plain == null && value.isNotBlank()) plain = value
        }
        return plain
    }

    // ── Matroska / WebM ──────────────────────────────────────────────────

    private fun matroska(bytes: ByteArray): String? {
        var plain: String? = null
        for (name in listOf(WORD_LYRICS_FIELD, "LYRICS")) {
            val needle = name.toByteArray(StandardCharsets.US_ASCII)
            var from = 0
            while (true) {
                val at = bytes.indexOf(needle, from, bytes.size) ?: break
                from = at + needle.size
                // The name element's own header sits immediately in front of it: id(2) plus a
                // one-byte length for a name this short.
                if (at < 3 || bytes[at - 3] != ID_TAGNAME[0] || bytes[at - 2] != ID_TAGNAME[1]) continue
                if ((bytes[at - 1].toInt() and 0x7F) != needle.size) continue
                val string = bytes.indexOf(ID_TAGSTRING, from, bytes.size) ?: continue
                val size = readVint(bytes, string + 2) ?: continue
                val valueAt = string + 2 + size.width
                if (size.value <= 0 || valueAt + size.value > bytes.size) continue
                val value = String(bytes, valueAt, size.value.toInt(), StandardCharsets.UTF_8)
                if (value.isBlank()) continue
                if (name == WORD_LYRICS_FIELD) return value
                if (plain == null) plain = value
            }
        }
        return plain
    }

    private class Vint(val value: Long, val width: Int)

    private fun readVint(bytes: ByteArray, offset: Int): Vint? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first == 0) return null
        var width = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            width++
        }
        if (offset + width > bytes.size) return null
        var value = (first and mask.inv() and 0xFF).toLong()
        for (i in 1 until width) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return Vint(value, width)
    }

    // ── Bytes ────────────────────────────────────────────────────────────

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.isMp4(): Boolean =
        size > 12 && this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
            this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()

    private fun ByteArray.indexOf(needle: ByteArray, from: Int, until: Int): Int? {
        if (needle.isEmpty()) return null
        val last = minOf(until, size) - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return null
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (b[off + i].toLong() and 0xFF)
        return value
    }

    /** This app's own field, holding the word-timed form other players have no place for. */
    internal const val WORD_LYRICS_FIELD = "BITCHORD_LYRICS"

    private const val FLAC_VORBIS_COMMENT = 4
    private val FLAC_MAGIC = "fLaC".toByteArray(StandardCharsets.US_ASCII)
    private val MATROSKA_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val DATA_ATOM = "data".toByteArray(StandardCharsets.ISO_8859_1)
    private val LYR_ATOM = byteArrayOf(0xA9.toByte()) + "lyr".toByteArray(StandardCharsets.ISO_8859_1)
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
}
