/** Adapted from UCRT (GPL-3.0-or-later): multi-stage Kaiser-windowed FIR PCM upsampler. */
package com.music.bitchord.playback.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 整数倍のアップサンプラー(多相 FIR、カイザー窓)。入力 1 フレームにつき、ちょうど factor フレームを出力する。
 *
 * 2 のべき乗倍(×2・×4・×8・×16)は 2 倍ずつ段に分けて上げる。
 * 音質を決めるのは最初の 2 倍の段(元の周波数の 0.46 倍までを通し、約 90 dB 減衰させる、以前と同じ 128 タップのフィルター)で、
 * 2 段目からは、すでに帯域が狭くなった音の折り返しを取るだけなので短いフィルターで済む。
 * 1 段で 8 倍にするより計算がずっと少なく、性能の低い端末でも途切れにくい。
 * それ以外の倍率(×3 など)は、以前と同じ 1 段の多相 FIR で上げる。
 */
class Upsampler(private val factor: Int, private val channels: Int) {
    private val stages: List<FirStage> = plan(factor, channels)
    private val bufs: Array<FloatArray> = Array(stages.size - 1) { FloatArray(0) }

    companion object {
        /** 倍率 factor にするための段(FIR)の並び。channels チャンネル分の状態を持つ */
        internal fun plan(factor: Int, channels: Int): List<FirStage> {
            val pow2 = factor >= 2 && (factor and (factor - 1)) == 0
            return if (!pow2) {
                listOf(FirStage(factor, 64, 0.5 / factor * 0.92, channels))
            } else {
                val list = ArrayList<FirStage>()
                var inRate = 1.0 // 元の周波数を 1 としたときの、各段の入力の周波数
                var f = factor
                while (f > 1) {
                    if (list.isEmpty()) {
                        // 1 段目: 音質を決める段。以前の 1 段のフィルター(64 タップ / 位相、β 9)より少し急峻で減衰も深い
                        list.add(FirStage(2, 72, 0.5 / 2 * 0.92, channels, 9.6))
                    } else {
                        // 2 段目から: 通す帯域(元の 0.46 倍まで)と、消す折り返し(入力の周波数 - 元の 0.505 倍から)の中間で切る
                        val pass = 0.46
                        val stop = inRate - 0.505
                        val fc = (pass + stop) / 2 / (2 * inRate)
                        // 遷移の幅が広いほど短くて済む(段が進むほど短く)
                        val taps = when (list.size) { 1 -> 20; 2 -> 14; else -> 12 }
                        list.add(FirStage(2, taps, fc, channels))
                    }
                    inRate *= 2
                    f /= 2
                }
                list
            }
        }
    }

    private fun run(st: FirStage, src: FloatArray, n: Int, dst: FloatArray) = st.process(src, n, dst)

    /** in: チャンネル交互の float(frames フレーム)。out に frames*factor フレームを書く */
    fun process(input: FloatArray, frames: Int, out: FloatArray) {
        if (stages.size == 1) {
            run(stages[0], input, frames, out)
            return
        }
        var src = input
        var n = frames
        for (i in stages.indices) {
            val st = stages[i]
            val dst = if (i == stages.lastIndex) out else {
                val need = n * st.factor * channels
                if (bufs[i].size < need) bufs[i] = FloatArray(need)
                bufs[i]
            }
            run(st, src, n, dst)
            src = dst
            n *= st.factor
        }
    }

}

/**
 * 1 段の多相 FIR。fc は出力の周波数に対する遮断周波数、taps は 1 位相あたりのタップ数。
 * ステレオは左右を同じループで計算する(係数の読み込みを半分にする)
 */
internal class FirStage(val factor: Int, private val taps: Int, fc: Double, private val channels: Int, private val beta: Double = 9.0) {
    /** cr[phase][j]: 古い順に並べた履歴に掛ける係数 */
    private val cr: Array<FloatArray>
    private val hist = Array(channels) { FloatArray(taps * 2) }
    /** 履歴の書き込み位置(チャンネルごと。別々のスレッドで計算できるように) */
    private val posOf = IntArray(channels)

    init {
        val n = factor * taps
        val mid = (n - 1) / 2.0
        val h = DoubleArray(n) { i ->
            val x = i - mid
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val r = 2.0 * i / (n - 1) - 1.0
            sinc * bessel0(beta * sqrt((1 - r * r).coerceAtLeast(0.0))) / bessel0(beta)
        }
        // 各位相の合計が 1 になるように(ゼロを挟んだ分の音量を戻す)
        val sum = h.sum()
        cr = Array(factor) { p -> FloatArray(taps) { j -> (h[p + (taps - 1 - j) * factor] * factor / sum).toFloat() } }
    }

    private fun bessel0(x: Double): Double {
        var s = 1.0; var t = 1.0; var k = 1
        while (k < 50) {
            t *= (x / (2 * k)) * (x / (2 * k))
            s += t
            if (t < 1e-12 * s) break
            k++
        }
        return s
    }

    fun process(input: FloatArray, frames: Int, out: FloatArray) {
        if (channels == 2) { processStereo(input, frames, out); return }
        for (c in 0 until channels) processOne(input, frames, out, c)
    }

    /** 1 チャンネル分だけ計算する(入出力はチャンネル交互のまま) */
    fun processOne(input: FloatArray, frames: Int, out: FloatArray, c: Int) {
        val ch = channels
        val hh = hist[c]
        val t = taps
        var pos = posOf[c]
        var i = c
        var o = c
        for (f in 0 until frames) {
            // 履歴は 2 重に持ち、折り返しなしで連続して読めるようにする。最新は pos + taps
            val v = input[i]
            i += ch
            hh[pos] = v
            hh[pos + t] = v
            val base = pos + 1
            for (p in 0 until factor) {
                val cf = cr[p]
                var acc = 0f
                var j = 0
                var k = base
                while (j + 3 < t) {
                    acc += cf[j] * hh[k] + cf[j + 1] * hh[k + 1] + cf[j + 2] * hh[k + 2] + cf[j + 3] * hh[k + 3]
                    j += 4
                    k += 4
                }
                while (j < t) {
                    acc += cf[j] * hh[k]
                    j++
                    k++
                }
                out[o] = acc
                o += ch
            }
            pos++
            if (pos == t) pos = 0
        }
        posOf[c] = pos
    }

    private fun processStereo(input: FloatArray, frames: Int, out: FloatArray) {
        val hl = hist[0]
        val hr = hist[1]
        val t = taps
        var pos = posOf[0]
        var o = 0
        var i = 0
        for (f in 0 until frames) {
            val l = input[i]
            val r = input[i + 1]
            i += 2
            hl[pos] = l; hl[pos + t] = l
            hr[pos] = r; hr[pos + t] = r
            val base = pos + 1
            for (p in 0 until factor) {
                val cf = cr[p]
                var al = 0f
                var ar = 0f
                var j = 0
                var k = base
                // 4 つずつまとめて計算する(ループの負担を減らす)
                while (j + 3 < t) {
                    val c0 = cf[j]; val c1 = cf[j + 1]; val c2 = cf[j + 2]; val c3 = cf[j + 3]
                    al += c0 * hl[k] + c1 * hl[k + 1] + c2 * hl[k + 2] + c3 * hl[k + 3]
                    ar += c0 * hr[k] + c1 * hr[k + 1] + c2 * hr[k + 2] + c3 * hr[k + 3]
                    j += 4
                    k += 4
                }
                while (j < t) {
                    val c = cf[j]
                    al += c * hl[k]
                    ar += c * hr[k]
                    j++
                    k++
                }
                out[o] = al
                out[o + 1] = ar
                o += 2
            }
            pos++
            if (pos == t) pos = 0
        }
        posOf[0] = pos
        posOf[1] = pos
    }
}
