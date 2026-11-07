package com.music.bitchord.data.lyrics

import android.system.Os
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor

/** Reads and writes standard ID3 lyrics frames stored in a RIFF/WAVE `id3 ` chunk. */
internal object WavId3Lyrics {
    private const val TAG = "WavId3Lyrics"
    private const val MAX_ID3_TAG_BYTES = 64 * 1024 * 1024
    private const val MAX_LYRICS_BYTES = 2 * 1024 * 1024
    private const val MAX_RIFF_SIZE = 0xFFFF_FFFFL

    private data class Chunk(val id: String, val headerOffset: Long, val size: Long)
    private data class Frame(val id: String, val flags: ByteArray, val data: ByteArray)
    private data class ParsedTag(val version: Int, val frames: List<Frame>)
    data class Lyrics(val standard: String?, val enhanced: String?)

    fun read(fd: FileDescriptor): String? = readFields(fd)?.let { it.enhanced ?: it.standard }

    fun readFields(fd: FileDescriptor): Lyrics? = runCatching {
        val chunks = chunks(fd) ?: return@runCatching null
        var standard: String? = null
        var enhanced: String? = null
        for (chunk in chunks) {
            if (chunk.id != "id3 ") continue
            if (chunk.size !in 10..MAX_ID3_TAG_BYTES.toLong()) continue
            val bytes = readAt(fd, chunk.headerOffset + 8, chunk.size.toInt()) ?: continue
            val tag = parseTag(bytes) ?: continue
            for (frame in tag.frames) {
                when (frame.id) {
                    "TXXX" -> {
                        val (description, value) = parseUserText(frame.data) ?: continue
                        if (description.equals(WORD_LYRICS_FIELD, ignoreCase = true) && value.isNotBlank() && enhanced == null) {
                            enhanced = value
                        }
                        if (description.equals("LYRICS", ignoreCase = true) && standard.isNullOrBlank()) {
                            standard = value.takeIf(String::isNotBlank)
                        }
                    }
                    "USLT" -> if (standard.isNullOrBlank()) {
                        standard = parseUnsynchronisedLyrics(frame.data)?.takeIf(String::isNotBlank)
                    }
                }
            }
        }
        if (standard == null && enhanced == null) null else Lyrics(standard, enhanced)
    }.onFailure { Log.w(TAG, "Could not read WAV ID3 lyrics", it) }.getOrNull()

    /**
     * Replaces only ID3 lyric frames. All other RIFF chunks and ID3 frames are
     * retained, and PCM/audio chunk bytes are never rewritten.
     */
    fun write(fd: FileDescriptor, standardLyrics: String?, enhancedLyrics: String?): Boolean = runCatching {
        val chunks = chunks(fd) ?: return false
        val id3Chunks = chunks.filter { it.id == "id3 " }
        val keptFrames = mutableListOf<Frame>()
        var outputVersion = 4

        for (chunk in id3Chunks) {
            if (chunk.size !in 10..MAX_ID3_TAG_BYTES.toLong()) return false
            val bytes = readAt(fd, chunk.headerOffset + 8, chunk.size.toInt()) ?: return false
            val tag = parseTag(bytes) ?: return false
            if (tag.version == 3) outputVersion = 3
            keptFrames += tag.frames.filterNot(::isLyricsFrame)
        }

        val standard = standardLyrics?.takeIf(String::isNotBlank)
        val enhanced = enhancedLyrics?.takeIf(String::isNotBlank)
        if (standard != null && standard.toByteArray(Charsets.UTF_8).size > MAX_LYRICS_BYTES) return false
        if (enhanced != null && enhanced.toByteArray(Charsets.UTF_8).size > MAX_LYRICS_BYTES) return false
        if (standard != null) keptFrames += Frame("USLT", byteArrayOf(0, 0), makeUnsynchronisedLyrics(standard))
        if (enhanced != null) keptFrames += Frame("TXXX", byteArrayOf(0, 0), makeUserText(WORD_LYRICS_FIELD, enhanced))

        val newTag = if (keptFrames.isEmpty()) null else buildTag(outputVersion, keptFrames) ?: return false
        if (newTag != null && newTag.size > MAX_ID3_TAG_BYTES) return false

        // Reuse the existing ID3 chunk when it has room. Padding is part of
        // the ID3 tag size, so this avoids growing the WAV on subsequent edits.
        if (newTag != null && id3Chunks.size == 1 && newTag.size <= id3Chunks.single().size) {
            val existing = id3Chunks.single()
            val paddedTag = buildTag(outputVersion, keptFrames, existing.size.toInt()) ?: return false
            writeAt(fd, existing.headerOffset + 8, paddedTag)
            runCatching { Os.fsync(fd) }
            return true
        }

        val riffEnd = Os.fstat(fd).st_size
        val riffSize = riffEnd - 8
        if (riffSize < 4 || riffSize > MAX_RIFF_SIZE) return false

        // Append the replacement first. Marking old ID3 chunks as JUNK after
        // the new chunk is committed makes an interrupted write recoverable.
        if (newTag != null) {
            val paddedTagSize = newTag.size.toLong()
            val chunkLength = 8L + paddedTagSize + (paddedTagSize and 1L)
            if (riffSize + chunkLength > MAX_RIFF_SIZE) return false
            val at = riffEnd
            writeAt(fd, at, byteArrayOf('i'.code.toByte(), 'd'.code.toByte(), '3'.code.toByte(), ' '.code.toByte()))
            writeAt(fd, at + 4, u32le(newTag.size.toLong()))
            writeAt(fd, at + 8, newTag)
            if (newTag.size and 1 != 0) writeAt(fd, at + 8 + newTag.size, byteArrayOf(0))
            writeAt(fd, 4, u32le(riffSize + chunkLength))
            runCatching { Os.fsync(fd) }
        }

        // Retire any previous ID3 chunks only after the replacement is valid.
        for (chunk in id3Chunks) {
            writeAt(fd, chunk.headerOffset, byteArrayOf('J'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte()))
        }
        if (newTag == null && id3Chunks.isNotEmpty()) runCatching { Os.fsync(fd) }
        true
    }.onFailure { Log.e(TAG, "Could not write WAV ID3 lyrics", it) }.getOrDefault(false)

    private fun chunks(fd: FileDescriptor): List<Chunk>? {
        val fileSize = Os.fstat(fd).st_size
        if (fileSize < 12) return null
        val riff = readAt(fd, 0, 12) ?: return null
        if (!riff.matchesAscii(0, "RIFF") || !riff.matchesAscii(8, "WAVE")) return null
        val declaredSize = readU32le(riff, 4) ?: return null
        if (declaredSize == 0xFFFF_FFFFL || declaredSize + 8L != fileSize) return null
        val end = declaredSize + 8L
        val result = mutableListOf<Chunk>()
        var offset = 12L
        while (offset < end) {
            if (end - offset < 8) return null
            val header = readAt(fd, offset, 8) ?: return null
            val size = readU32le(header, 4) ?: return null
            val paddedSize = size + (size and 1L)
            if (paddedSize > end - offset - 8L) return null
            result += Chunk(String(header, 0, 4, Charsets.ISO_8859_1), offset, size)
            offset += 8L + paddedSize
        }
        return result.takeIf { offset == end }
    }

    private fun parseTag(bytes: ByteArray): ParsedTag? {
        if (bytes.size < 10 || !bytes.matchesAscii(0, "ID3")) return null
        val version = bytes[3].toInt() and 0xFF
        if (version !in 3..4 || bytes[4].toInt() != 0) return null
        // Avoid rewriting a tag whose global transforms or extended header
        // would require decoding its existing frames first.
        if (bytes[5].toInt() != 0) return null
        val declared = syncSafe(bytes, 6) ?: return null
        if (declared > bytes.size - 10) return null
        val end = 10 + declared
        val frames = mutableListOf<Frame>()
        var offset = 10
        while (offset < end) {
            if (bytes[offset] == 0.toByte()) break
            if (end - offset < 10) return null
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) return null
            val size = if (version == 3) readU32be(bytes, offset + 4) else syncSafe(bytes, offset + 4)
                ?: return null
            if (size < 0 || size > end - offset - 10) return null
            frames += Frame(
                id = id,
                flags = bytes.copyOfRange(offset + 8, offset + 10),
                data = bytes.copyOfRange(offset + 10, offset + 10 + size),
            )
            offset += 10 + size
        }
        return ParsedTag(version, frames)
    }

    private fun isLyricsFrame(frame: Frame): Boolean = when (frame.id) {
        "USLT" -> true
        "TXXX" -> parseUserText(frame.data)?.first?.let {
            it.equals(WORD_LYRICS_FIELD, true) || it.equals("LYRICS", true)
        } ?: false
        else -> false
    }

    private fun makeUnsynchronisedLyrics(value: String): ByteArray = ByteArrayOutputStream().apply {
        write(1) // UTF-16 with BOM
        write(byteArrayOf('e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()))
        write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // empty content descriptor BOM
        write(byteArrayOf(0, 0))
        write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // lyrics text BOM
        write(value.toByteArray(Charsets.UTF_16LE))
    }.toByteArray()

    private fun makeUserText(description: String, value: String): ByteArray = ByteArrayOutputStream().apply {
        write(1) // UTF-16 with BOM
        write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        write(description.toByteArray(Charsets.UTF_16LE))
        write(byteArrayOf(0, 0))
        write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // user text BOM
        write(value.toByteArray(Charsets.UTF_16LE))
    }.toByteArray()

    private fun parseUnsynchronisedLyrics(data: ByteArray): String? {
        if (data.size < 6) return null
        val encoding = data[0].toInt() and 0xFF
        return decodeSeparatedText(data, 4, encoding)?.second?.takeIf(String::isNotBlank)
    }

    private fun parseUserText(data: ByteArray): Pair<String, String>? {
        if (data.size < 2) return null
        val encoding = data[0].toInt() and 0xFF
        val (description, value) = decodeSeparatedText(data, 1, encoding) ?: return null
        return description to value
    }

    private fun decodeSeparatedText(data: ByteArray, start: Int, encoding: Int): Pair<String, String>? {
        val terminator = if (encoding == 1 || encoding == 2) {
            var index = start
            while (index + 1 < data.size) {
                if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) break
                index += 2
            }
            if (index + 1 >= data.size) return null
            index to (index + 2)
        } else {
            val index = data.indexOf(0, start)
            if (index < 0) return null
            index to (index + 1)
        }
        fun decode(from: Int, to: Int): String {
            val charset = when (encoding) {
                0 -> Charsets.ISO_8859_1
                1 -> if (to - from >= 2 && data[from] == 0xFE.toByte() && data[from + 1] == 0xFF.toByte()) {
                    Charsets.UTF_16
                } else if (to - from >= 2 && data[from] == 0xFF.toByte() && data[from + 1] == 0xFE.toByte()) {
                    Charsets.UTF_16
                } else {
                    Charsets.UTF_16LE
                }
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
            return String(data, from, to - from, charset).trim('\uFEFF', '\u0000')
        }
        if (encoding !in 0..3) return null
        val first = decode(start, terminator.first)
        val second = decode(terminator.second, data.size)
        return first to second
    }

    private fun buildTag(version: Int, frames: List<Frame>, totalPayloadSize: Int? = null): ByteArray? {
        val body = ByteArrayOutputStream()
        for (frame in frames) {
            if (frame.id.length != 4 || frame.flags.size != 2) return null
            body.write(frame.id.toByteArray(Charsets.US_ASCII))
            body.write(if (version == 3) u32be(frame.data.size) else syncSafeBytes(frame.data.size) ?: return null)
            body.write(frame.flags)
            body.write(frame.data)
        }
        val frameBody = body.toByteArray()
        val payloadSize = totalPayloadSize ?: (10 + frameBody.size)
        if (payloadSize < 10 + frameBody.size) return null
        val bodySize = payloadSize - 10
        val size = syncSafeBytes(bodySize) ?: return null
        val padding = ByteArray(bodySize - frameBody.size)
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0) +
            size + frameBody + padding
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val a = bytes[offset].toInt() and 0xFF
        val b = bytes[offset + 1].toInt() and 0xFF
        val c = bytes[offset + 2].toInt() and 0xFF
        val d = bytes[offset + 3].toInt() and 0xFF
        if (((a or b or c or d) and 0x80) != 0) return null
        return (a shl 21) or (b shl 14) or (c shl 7) or d
    }

    private fun syncSafeBytes(value: Int): ByteArray? {
        if (value < 0 || value > 0x0FFF_FFFF) return null
        return byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    private fun readU32le(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readU32be(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun u32le(value: Long): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )

    private fun u32be(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun readAt(fd: FileDescriptor, position: Long, size: Int): ByteArray? {
        if (size < 0 || size > MAX_ID3_TAG_BYTES) return null
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = Os.pread(fd, bytes, read, size - read, position + read)
            if (count <= 0) return null
            read += count
        }
        return bytes
    }

    private fun writeAt(fd: FileDescriptor, position: Long, bytes: ByteArray) {
        var written = 0
        while (written < bytes.size) {
            val count = Os.pwrite(fd, bytes, written, bytes.size - written, position + written)
            if (count <= 0) error("WAV metadata write stopped early")
            written += count
        }
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
        offset >= 0 && offset + value.length <= size && value.indices.all { this[offset + it] == value[it].code.toByte() }

    private fun ByteArray.indexOf(value: Int, from: Int): Int {
        for (index in from.coerceAtLeast(0) until size) if (this[index].toInt() and 0xFF == value) return index
        return -1
    }
}
