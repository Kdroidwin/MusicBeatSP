/** Adapted from UCRT (GPL-3.0-or-later): DSD file parsing and PCM decimation. */
package com.music.bitchord.playback.dsp.dsd

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSD を PCM(float)に変換する。DSD に対応していない出力先で再生するときに使う。
 * 1 段目: 128 タップの FIR を 1 バイト単位の表引きで計算し、1/16 に間引く(DSD64 → 176.4 kHz)
 * 2 段目: float の FIR で、出力の周波数(44.1 kHz 系は 176.4 kHz、48 kHz 系は 192 kHz)まで間引く
 */
class DsdToPcm(dsdRate: Int, private val channels: Int, hq: Boolean = false) {
    /** 1 段目のフィルターが見るバイト数(128 タップなら 16。高音質(ファイルの変換)では 512 タップで 64) */
    private val nb = if (hq) 64 else 16
    val outRate: Int
    private val m2: Int
    private val tab: Array<FloatArray> // [バイト位置 0..15][バイト値]
    private val hist1 = Array(channels) { ByteArray(nb * 2) }
    private var p1 = 0
    private var phase1 = 0
    private val taps2: Int
    private val h2: FloatArray
    private val hist2 = Array(channels) { FloatArray(0) }
    private var p2 = 0
    private var phase2 = 0

    init {
        val stage1Rate = dsdRate / 16
        val target = if (dsdRate % 48000 == 0) 192000 else 176400
        m2 = (stage1Rate / target).coerceAtLeast(1)
        outRate = stage1Rate / m2
        // 1 段目
        val n1 = nb * 8
        val fc1 = 70000.0 / dsdRate * (dsdRate / 2822400.0).coerceAtLeast(1.0) // 周波数が高いほど広く取り、2 段目で絞る
        // 高音質: タップを長く・窓を深くして、可聴帯域に折り返す DSD の高域の雑音を約 120 dB 落とす(再生用は約 80 dB)
        val h1 = kaiserLowpass(n1, fc1.coerceAtMost(0.5 / 16 * 0.9), if (hq) 12.0 else 8.0)
        tab = Array(nb) { j ->
            FloatArray(256) { b ->
                var s = 0.0
                for (i in 0 until 8) {
                    // 新しいバイトの LSB がいちばん新しいビット
                    val t = 8 * j + i
                    s += h1[t] * (if ((b shr i) and 1 != 0) 1.0 else -1.0)
                }
                s.toFloat()
            }
        }
        // 2 段目(約 45 kHz より上を落とす)
        taps2 = (if (m2 == 1) 64 else 32 * m2) * (if (hq) 2 else 1)
        h2 = kaiserLowpass(taps2, 45000.0 / stage1Rate, if (hq) 12.0 else 8.0).map { it.toFloat() }.toFloatArray()
        for (c in 0 until channels) hist2[c] = FloatArray(taps2 * 2)
    }

    private fun kaiserLowpass(n: Int, fc: Double, beta: Double): DoubleArray {
        val mid = (n - 1) / 2.0
        val h = DoubleArray(n) { i ->
            val x = i - mid
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val r = 2.0 * i / (n - 1) - 1.0
            sinc * bessel0(beta * sqrt((1 - r * r).coerceAtLeast(0.0))) / bessel0(beta)
        }
        val sum = h.sum()
        for (i in h.indices) h[i] /= sum
        return h
    }

    private fun bessel0(x: Double): Double {
        var s = 1.0; var t = 1.0; var k = 1
        while (k < 60) {
            t *= (x / (2 * k)) * (x / (2 * k))
            s += t
            if (t < 1e-12 * s) break
            k++
        }
        return s
    }

    /** 出力の最大フレーム数(入力 n バイト/チャンネルに対して) */
    fun maxOut(n: Int): Int = n / 2 / m2 + 2

    /**
     * in[c] の先頭 n バイト(MSB 先頭)を変換し、out にチャンネル交互の float を書く。書いたフレーム数を返す。
     */
    fun process(input: Array<ByteArray>, n: Int, out: FloatArray): Int {
        var frames = 0
        for (i in 0 until n) {
            for (c in 0 until channels) {
                val h = hist1[c]
                h[p1] = input[c][i]
                h[p1 + nb] = input[c][i]
            }
            p1 = (p1 + 1) and (nb - 1)
            phase1++
            if (phase1 < 2) continue
            phase1 = 0
            // 1 段目の出力(1 チャンネル分ずつ)
            for (c in 0 until channels) {
                val h = hist1[c]
                var acc = 0f
                // p1 は次に書く位置 = いちばん古いもの。新しい順に j = 0..nb-1
                var idx = p1 + nb - 1
                for (j in 0 until nb) {
                    acc += tab[j][h[idx].toInt() and 0xFF]
                    idx--
                }
                val h2c = hist2[c]
                h2c[p2] = acc
                h2c[p2 + taps2] = acc
            }
            p2++
            if (p2 == taps2) p2 = 0
            phase2++
            if (phase2 < m2) continue
            phase2 = 0
            for (c in 0 until channels) {
                val h2c = hist2[c]
                var acc = 0f
                var idx = (if (p2 == 0) taps2 else p2) - 1 + taps2
                for (k in 0 until taps2) {
                    acc += h2[k] * h2c[idx]
                    idx--
                }
                out[frames * channels + c] = acc
            }
            frames++
        }
        return frames
    }
}
