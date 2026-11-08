/** Adapted from UCRT (GPL-3.0-or-later): DSD file parsing and PCM decimation. */
package com.music.bitchord.playback.dsp.dsd

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** DSD ファイル(DSF / DSDIFF)の基本情報 */
class DsdInfo(
    val dff: Boolean,
    val channels: Int,
    /** 1 チャンネルあたりの DSD サンプリング周波数(例: 2822400 = DSD64) */
    val rate: Int,
    /** 音声データの開始位置(ファイル先頭から) */
    val dataOffset: Long,
    /** 音声データの長さ(全チャンネル分のバイト数。DSF はブロックの埋め草を含む) */
    val dataLength: Long,
    /** 1 チャンネルあたりの有効なバイト数 */
    val bytesPerChannel: Long,
    /** DSF のブロックサイズ(1 チャンネルあたり)。DFF は 1(バイト単位で交互) */
    val blockSize: Int,
    /** ビットが LSB から並んでいるか(DSF の bitsPerSample = 1) */
    val lsbFirst: Boolean,
    /** ID3v2 タグの位置(無ければ -1) */
    val id3Offset: Long,
    val id3Length: Long,
    /** DSDIFF の DIIN にある曲名・アーティスト */
    val diinTitle: String?,
    val diinArtist: String?,
    /** DST 圧縮(未対応) */
    val compressed: Boolean,
) {
    val durationMs: Long get() = if (rate > 0) bytesPerChannel * 8 * 1000 / rate else 0
    /** 64 / 128 / 256 / 512(44.1 kHz 系)。48 kHz 系は 0 */
    val multiple: Int get() = if (rate % 44100 == 0) rate / 44100 else 0

    fun label(): String {
        val mhz = "%.1f MHz".format(java.util.Locale.US, rate / 1_000_000.0)
        return when {
            rate % 44100 == 0 -> "DSD${rate / 44100} ($mhz)"
            rate % 48000 == 0 -> "DSD${rate / 48000} 48k ($mhz)"
            else -> "DSD $mhz"
        }
    }
}

/** ファイルを任意の位置から読むための小さな道具 */
// AutoCloseable は Android 4.4 からなので、古い Android でも使える Closeable にする
class RandomFile(private val pfd: ParcelFileDescriptor) : java.io.Closeable {
    private val ch: FileChannel = FileInputStream(pfd.fileDescriptor).channel
    val size: Long get() = runCatching { ch.size() }.getOrDefault(-1L)

    fun read(pos: Long, n: Int): ByteArray {
        val bb = ByteBuffer.allocate(n)
        var p = pos
        while (bb.hasRemaining()) {
            val r = ch.read(bb, p)
            if (r <= 0) break
            p += r
        }
        return bb.array().copyOf(bb.position())
    }

    /** dst[off] から n バイトに読む(配列を作らない版)。読めたバイト数を返す */
    fun readInto(pos: Long, dst: ByteArray, off: Int, n: Int): Int {
        val bb = ByteBuffer.wrap(dst, off, n)
        var p = pos
        while (bb.hasRemaining()) {
            val r = ch.read(bb, p)
            if (r <= 0) break
            p += r
        }
        return bb.position() - off
    }

    override fun close() {
        runCatching { ch.close() }
        runCatching { pfd.close() }
    }

    companion object {
        fun open(ctx: Context, uri: Uri): RandomFile? =
            runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()?.let { RandomFile(it) }
    }
}

object DsdFile {
    fun isDsd(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it == "dsf" || it == "dff" }

    private fun le(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (k in n - 1 downTo 0) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    private fun be(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    fun parse(f: RandomFile): DsdInfo {
        val head = f.read(0, 16)
        if (head.size < 16) throw IOException("too short")
        val id = String(head, 0, 4, Charsets.ISO_8859_1)
        return when (id) {
            "DSD " -> parseDsf(f)
            "FRM8" -> parseDff(f)
            else -> throw IOException("not a DSD file")
        }
    }

    private fun parseDsf(f: RandomFile): DsdInfo {
        val b = f.read(0, 92)
        if (b.size < 92) throw IOException("bad DSF header")
        val meta = le(b, 20, 8)
        if (String(b, 28, 4, Charsets.ISO_8859_1) != "fmt ") throw IOException("bad DSF fmt")
        val fmtSize = le(b, 32, 8)
        val formatId = le(b, 44, 4)
        val channels = le(b, 52, 4).toInt()
        val rate = le(b, 56, 4).toInt()
        val bps = le(b, 60, 4).toInt()
        val samples = le(b, 64, 8)
        // ブロックサイズは本来 4096。おかしな値で巨大な配列を作らないよう、範囲外なら 4096 とみなす
        val block = le(b, 72, 4).toInt().takeIf { it in 1..(1 shl 20) } ?: 4096
        if (formatId != 0L) throw IOException("unsupported DSF format")
        if (channels !in 1..64 || rate <= 0) throw IOException("bad DSF fmt")
        val dataPos = 28 + fmtSize
        val d = f.read(dataPos, 12)
        if (d.size < 12 || String(d, 0, 4, Charsets.ISO_8859_1) != "data") throw IOException("bad DSF data")
        val dataLen = le(d, 4, 8) - 12
        var id3Len = 0L
        val total = f.size
        if (meta > 0) {
            id3Len = if (total > meta) total - meta else 0
        }
        // ファイルの大きさが分からないときは、data チャンクの長さを信じる
        val avail = if (total >= 0) total - (dataPos + 12) else dataLen
        return DsdInfo(
            dff = false, channels = channels, rate = rate, dataOffset = dataPos + 12, dataLength = dataLen,
            // 途中で切れたファイルでも、実際にあるデータの範囲だけを使う
            bytesPerChannel = minOf((samples + 7) / 8, maxOf(0L, minOf(dataLen, avail)) / channels.coerceAtLeast(1)),
            blockSize = block, lsbFirst = bps == 1,
            id3Offset = if (meta > 0 && id3Len > 10) meta else -1, id3Length = id3Len,
            diinTitle = null, diinArtist = null, compressed = false,
        )
    }

    private fun parseDff(f: RandomFile): DsdInfo {
        val total = f.size
        var pos = 16L // FRM8 + size + "DSD "
        var channels = 2; var rate = 0; var compressed = false
        var dataOff = -1L; var dataLen = 0L
        var id3Off = -1L; var id3Len = 0L
        var title: String? = null; var artist: String? = null
        while (pos + 12 <= total || (total < 0 && pos < 1L shl 40)) {
            val h = f.read(pos, 12)
            if (h.size < 12) break
            val cid = String(h, 0, 4, Charsets.ISO_8859_1)
            val size = be(h, 4, 8)
            val body = pos + 12
            if (size < 0) break
            when (cid) {
                "PROP" -> {
                    // "SND " の後に小チャンクが並ぶ
                    var p = body + 4
                    val end = body + size
                    while (p + 12 <= end) {
                        val sh = f.read(p, 12)
                        if (sh.size < 12) break
                        val sid = String(sh, 0, 4, Charsets.ISO_8859_1)
                        val ss = be(sh, 4, 8)
                        val sb = p + 12
                        // 壊れた大きさ(負)だと位置が戻って抜けられなくなるので打ち切る
                        if (ss < 0) break
                        when (sid) {
                            "FS  " -> rate = be(f.read(sb, 4), 0, 4).toInt()
                            "CHNL" -> channels = be(f.read(sb, 2), 0, 2).toInt()
                            "CMPR" -> compressed = String(f.read(sb, 4), Charsets.ISO_8859_1) != "DSD "
                        }
                        p = sb + ss + (ss and 1L)
                    }
                }
                "DSD " -> { dataOff = body; dataLen = size }
                "DST " -> { compressed = true; dataOff = body; dataLen = size }
                "ID3 " -> { id3Off = body; id3Len = size }
                "DIIN" -> {
                    var p = body
                    val end = body + size
                    while (p + 12 <= end) {
                        val sh = f.read(p, 12)
                        if (sh.size < 12) break
                        val sid = String(sh, 0, 4, Charsets.ISO_8859_1)
                        val ss = be(sh, 4, 8)
                        val sb = p + 12
                        if (ss < 0) break
                        if (sid == "DITI" || sid == "DIAR") {
                            val t = f.read(sb, ss.toInt().coerceIn(0, 4096))
                            if (t.size >= 4) {
                                val n = be(t, 0, 4).toInt().coerceIn(0, t.size - 4)
                                val s = decodeText(t, 4, n).trim()
                                if (sid == "DITI") title = s else artist = s
                            }
                        }
                        p = sb + ss + (ss and 1L)
                    }
                }
            }
            pos = body + size + (size and 1L)
        }
        if (dataOff < 0 || rate <= 0 || channels !in 1..64) throw IOException("bad DFF")
        return DsdInfo(
            dff = true, channels = channels, rate = rate, dataOffset = dataOff, dataLength = dataLen,
            bytesPerChannel = dataLen / channels, blockSize = 1, lsbFirst = false,
            id3Offset = id3Off, id3Length = id3Len, diinTitle = title, diinArtist = artist, compressed = compressed,
        )
    }

    /** 文字コードの指定が無い文字列: UTF-8 → Shift_JIS → ISO-8859-1 の順に試す */
    private fun decodeText(b: ByteArray, off: Int, len: Int): String {
        fun strict(cs: java.nio.charset.Charset): String? = runCatching {
            cs.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, len)).toString()
        }.getOrNull()
        return strict(Charsets.UTF_8)
            ?: runCatching { java.nio.charset.Charset.forName("windows-31j") }.getOrNull()?.let { strict(it) }
            ?: String(b, off, len, Charsets.ISO_8859_1)
    }

    /** ビットの並びを逆にする表(LSB 先頭 → MSB 先頭) */
    val REVERSE = ByteArray(256) { v ->
        var r = 0
        for (i in 0 until 8) if (v and (1 shl i) != 0) r = r or (1 shl (7 - i))
        r.toByte()
    }
}

/**
 * DSD の音声データを、チャンネルごとに「時間順・MSB 先頭」のバイト列として取り出す。
 */
class DsdReader(private val f: RandomFile, val info: DsdInfo) {
    private val ch = info.channels
    /** 次に読む位置(1 チャンネルあたりのバイト数) */
    var position = 0L
        private set
    private var block = ByteArray(0)
    private var blockIndex = -1L
    /** DFF の読み込み用(毎回配列を作らないように使い回す) */
    private var raw = ByteArray(0)

    fun seek(bytePerCh: Long) {
        position = bytePerCh.coerceIn(0, info.bytesPerChannel)
    }

    /**
     * 最大 max バイト(1 チャンネルあたり)を out[c] に読む。読めたバイト数を返す(終わりなら 0)。
     */
    fun read(out: Array<ByteArray>, max: Int): Int {
        val left = info.bytesPerChannel - position
        if (left <= 0) return 0
        val want = minOf(max.toLong(), left).toInt()
        if (info.dff) {
            if (raw.size < want * ch) raw = ByteArray(want * ch)
            val rb = raw
            val n = f.readInto(info.dataOffset + position * ch, rb, 0, want * ch) / ch
            for (i in 0 until n) for (c in 0 until ch) out[c][i] = rb[i * ch + c]
            position += n
            return n
        }
        // DSF: チャンネルごとのブロックが交互に並ぶ
        val bs = info.blockSize
        var got = 0
        while (got < want) {
            val bi = position / bs
            if (bi != blockIndex) {
                if (block.size != bs * ch) block = ByteArray(bs * ch)
                val got0 = f.readInto(info.dataOffset + bi * bs * ch, block, 0, bs * ch)
                blockIndex = bi
                // 足りない分は DSD の無音(0x69)で埋める(0x00 だと大きな雑音になる)
                if (got0 < bs * ch) java.util.Arrays.fill(block, got0, bs * ch, 0x69.toByte())
            }
            val inBlock = (position % bs).toInt()
            val k = minOf(want - got, bs - inBlock)
            for (c in 0 until ch) {
                System.arraycopy(block, c * bs + inBlock, out[c], got, k)
            }
            got += k
            position += k
        }
        if (info.lsbFirst) {
            val rev = DsdFile.REVERSE
            for (c in 0 until ch) {
                val o = out[c]
                for (i in 0 until got) o[i] = rev[o[i].toInt() and 0xFF]
            }
        }
        return got
    }
}
