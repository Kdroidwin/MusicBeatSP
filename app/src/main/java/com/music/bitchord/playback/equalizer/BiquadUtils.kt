package com.music.bitchord.playback.equalizer

import com.music.bitchord.data.settings.ParametricEqBand
import com.music.bitchord.data.settings.ParametricEqConfig
import com.music.bitchord.data.settings.ParametricEqFilterType
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-precision 64-bit floating-point Biquad filter coefficient and frequency-response
 * computation based on the Robert Bristow-Johnson (RBJ) Audio EQ Cookbook and
 * RootlessJamesDSP / EEL2 LiveProg implementation.
 */
object BiquadUtils {

    /**
     * Normalized biquad coefficients (divided by a0 so a0 = 1.0).
     * Transfer function:
     *   H(z) = (b0 + b1*z^-1 + b2*z^-2) / (1 + a1*z^-1 + a2*z^-2)
     * Difference equation:
     *   y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
     */
    data class BiquadCoefficients(
        val b0: Double = 1.0,
        val b1: Double = 0.0,
        val b2: Double = 0.0,
        val a1: Double = 0.0,
        val a2: Double = 0.0,
    )

    data class ChannelFrequencyResponse(
        val frequencies: DoubleArray,
        val globalGainsDb: DoubleArray,
        val leftGainsDb: DoubleArray,
        val rightGainsDb: DoubleArray,
    )

    fun computeCoefficients(
        band: ParametricEqBand,
        sampleRate: Double = 48_000.0,
    ): BiquadCoefficients {
        if (!band.enabled) return BiquadCoefficients()
        return computeCoefficients(
            frequency = band.frequencyHz.toDouble(),
            gainDb = band.gainDb.toDouble(),
            q = band.q.toDouble(),
            filterType = band.filterType,
            sampleRate = sampleRate,
        )
    }

    fun computeCoefficients(
        frequency: Double,
        gainDb: Double,
        q: Double,
        filterType: ParametricEqFilterType,
        sampleRate: Double = 48_000.0,
    ): BiquadCoefficients {
        if (sampleRate <= 0.0 || !frequency.isFinite() || !q.isFinite() || !gainDb.isFinite()) {
            return BiquadCoefficients()
        }
        val nyquistSafeMax = (sampleRate * 0.495).coerceAtLeast(ParametricEqBand.MIN_FREQUENCY_HZ.toDouble())
        val safeFreq = frequency.coerceIn(ParametricEqBand.MIN_FREQUENCY_HZ.toDouble(), nyquistSafeMax)
        val safeQ = q.coerceIn(ParametricEqBand.MIN_Q.toDouble(), ParametricEqBand.MAX_Q.toDouble())
        val safeGain = gainDb.coerceIn(ParametricEqBand.MIN_GAIN_DB.toDouble(), ParametricEqBand.MAX_GAIN_DB.toDouble())

        val w0 = 2.0 * PI * safeFreq / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * safeQ)
        val A = 10.0.pow(safeGain / 40.0)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double

        when (filterType) {
            ParametricEqFilterType.PEAKING -> {
                if (safeGain == 0.0) return BiquadCoefficients()
                b0 = 1.0 + alpha * A
                b1 = -2.0 * cosW0
                b2 = 1.0 - alpha * A
                a0 = 1.0 + alpha / A
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha / A
            }
            ParametricEqFilterType.LOW_SHELF -> {
                if (safeGain == 0.0) return BiquadCoefficients()
                val sqrtA = sqrt(A)
                val twoSqrtAAlpha = 2.0 * sqrtA * alpha
                b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0)
                a2 = (A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha
            }
            ParametricEqFilterType.HIGH_SHELF -> {
                if (safeGain == 0.0) return BiquadCoefficients()
                val sqrtA = sqrt(A)
                val twoSqrtAAlpha = 2.0 * sqrtA * alpha
                b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha)
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0)
                b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha)
                a0 = (A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0)
                a2 = (A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha
            }
            ParametricEqFilterType.LOW_PASS -> {
                b0 = (1.0 - cosW0) / 2.0
                b1 = 1.0 - cosW0
                b2 = (1.0 - cosW0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
            ParametricEqFilterType.HIGH_PASS -> {
                b0 = (1.0 + cosW0) / 2.0
                b1 = -(1.0 + cosW0)
                b2 = (1.0 + cosW0) / 2.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
            ParametricEqFilterType.BAND_PASS -> {
                b0 = alpha
                b1 = 0.0
                b2 = -alpha
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
            ParametricEqFilterType.NOTCH -> {
                b0 = 1.0
                b1 = -2.0 * cosW0
                b2 = 1.0
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
            ParametricEqFilterType.ALL_PASS -> {
                b0 = 1.0 - alpha
                b1 = -2.0 * cosW0
                b2 = 1.0 + alpha
                a0 = 1.0 + alpha
                a1 = -2.0 * cosW0
                a2 = 1.0 - alpha
            }
        }

        if (!a0.isFinite() || a0 == 0.0) return BiquadCoefficients()
        val invA0 = 1.0 / a0
        return BiquadCoefficients(
            b0 = b0 * invA0,
            b1 = b1 * invA0,
            b2 = b2 * invA0,
            a1 = a1 * invA0,
            a2 = a2 * invA0,
        )
    }

    /**
     * Evaluates |H(e^(j*w))| in dB at [frequency] Hz.
     */
    fun magnitudeResponse(
        coeffs: BiquadCoefficients,
        frequency: Double,
        sampleRate: Double = 48_000.0,
    ): Double {
        val omega = 2.0 * PI * frequency / sampleRate
        val cosW = cos(omega)
        val cos2W = cos(2.0 * omega)
        val sinW = sin(omega)
        val sin2W = sin(2.0 * omega)

        val numReal = coeffs.b0 + coeffs.b1 * cosW + coeffs.b2 * cos2W
        val numImag = -(coeffs.b1 * sinW + coeffs.b2 * sin2W)

        val denReal = 1.0 + coeffs.a1 * cosW + coeffs.a2 * cos2W
        val denImag = -(coeffs.a1 * sinW + coeffs.a2 * sin2W)

        val numMagSq = numReal * numReal + numImag * numImag
        val denMagSq = denReal * denReal + denImag * denImag

        return if (denMagSq > 0.0 && numMagSq > 0.0) {
            10.0 * log10(numMagSq / denMagSq)
        } else {
            0.0
        }
    }

    fun computeCombinedResponse(
        bands: List<ParametricEqBand>,
        preampDb: Double = 0.0,
        numPoints: Int = 256,
        minFreq: Double = 20.0,
        maxFreq: Double = 20_000.0,
        sampleRate: Double = 48_000.0,
    ): List<Pair<Double, Double>> {
        val logMin = ln(minFreq)
        val logMax = ln(maxFreq)
        val activeCoeffs = bands.filter { it.enabled }.map { computeCoefficients(it, sampleRate) }
        return List(numPoints) { i ->
            val t = i.toDouble() / (numPoints - 1).coerceAtLeast(1).toDouble()
            val freq = exp(logMin + t * (logMax - logMin))
            var gain = preampDb
            for (c in activeCoeffs) {
                gain += magnitudeResponse(c, freq, sampleRate)
            }
            freq to gain
        }
    }

    fun computeChannelResponses(
        config: ParametricEqConfig,
        numPoints: Int = 240,
        minFreq: Double = 20.0,
        maxFreq: Double = 20_000.0,
        sampleRate: Double = 48_000.0,
    ): ChannelFrequencyResponse {
        val logMin = ln(minFreq)
        val logMax = ln(maxFreq)
        val freqs = DoubleArray(numPoints)
        val globalGains = DoubleArray(numPoints)
        val leftGains = DoubleArray(numPoints)
        val rightGains = DoubleArray(numPoints)

        val globalCoeffs = config.globalBands.filter { it.enabled }.map { computeCoefficients(it, sampleRate) }
        val leftCoeffs = config.leftBands.filter { it.enabled }.map { computeCoefficients(it, sampleRate) }
        val rightCoeffs = config.rightBands.filter { it.enabled }.map { computeCoefficients(it, sampleRate) }

        val leftPreamp = config.effectiveLeftPreampDb.toDouble()
        val rightPreamp = config.effectiveRightPreampDb.toDouble()
        val basePreamp = config.totalPreampDb.toDouble()

        for (i in 0 until numPoints) {
            val t = i.toDouble() / (numPoints - 1).coerceAtLeast(1).toDouble()
            val freq = exp(logMin + t * (logMax - logMin))
            freqs[i] = freq

            var gDb = 0.0
            for (c in globalCoeffs) gDb += magnitudeResponse(c, freq, sampleRate)

            var lDb = gDb + leftPreamp
            for (c in leftCoeffs) lDb += magnitudeResponse(c, freq, sampleRate)

            var rDb = gDb + rightPreamp
            for (c in rightCoeffs) rDb += magnitudeResponse(c, freq, sampleRate)

            globalGains[i] = gDb + basePreamp
            leftGains[i] = lDb
            rightGains[i] = rDb
        }

        return ChannelFrequencyResponse(
            frequencies = freqs,
            globalGainsDb = globalGains,
            leftGainsDb = leftGains,
            rightGainsDb = rightGains,
        )
    }

    /**
     * Exports a [ParametricEqConfig] to a RootlessJamesDSP-compatible LiveProg (.eel) script.
     */
    fun toEelString(config: ParametricEqConfig, title: String = config.activePresetName ?: "MusicBeatSP Custom PEQ"): String {
        fun fmt(v: Float): String {
            val d = v.toDouble()
            return if (d == d.toLong().toDouble()) {
                String.format(Locale.US, "%.1f", d)
            } else {
                String.format(Locale.US, "%.3f", d).trimEnd('0').trimEnd('.')
            }
        }
        fun fmtFreq(v: Float): String {
            val d = v.toDouble()
            return if (kotlin.math.abs(d - kotlin.math.round(d)) < 0.005) {
                kotlin.math.round(d).toLong().toString()
            } else {
                String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
            }
        }

        val sb = StringBuilder()
        sb.appendLine("// Title: $title")
        sb.appendLine("// Converted for RootlessJamesDSP (LiveProg/EEL2)")
        sb.appendLine("// Global Preamp: ${String.format(Locale.US, "%.2f", config.globalPreampDb)} dB")
        sb.appendLine("// L/R Preamp: ${String.format(Locale.US, "%.2f", config.lrPreampDb)} dB")
        if (config.swapChannels) {
            sb.appendLine("// Note: Input channels are swapped (Left <-> Right) ${if (config.swapBeforeEq) "before" else "after"} processing.")
        }
        sb.appendLine()
        sb.appendLine("desc: $title")
        sb.appendLine()
        sb.appendLine("@init")
        sb.appendLine("PI = 3.14159265359;")
        sb.appendLine("srate = srate;")
        sb.appendLine()
        sb.appendLine("global_preamp_db = ${fmt(config.globalPreampDb)};")
        sb.appendLine("lr_preamp_db = ${fmt(config.lrPreampDb)};")
        sb.appendLine("total_gain = 10 ^ ((global_preamp_db + lr_preamp_db) / 20);")
        sb.appendLine()
        sb.appendLine("function calc_peak(freq, q, gain_db) (")
        sb.appendLine("w0 = 2 * PI * freq / srate;")
        sb.appendLine("alpha = sin(w0) / (2 * q);")
        sb.appendLine("A = 10 ^ (gain_db / 40);")
        sb.appendLine("b0 = 1 + alpha * A; b1 = -2 * cos(w0); b2 = 1 - alpha * A;")
        sb.appendLine("a0 = 1 + alpha / A; a1 = -2 * cos(w0); a2 = 1 - alpha / A;")
        sb.appendLine("inv_a0 = 1 / a0;")
        sb.appendLine("this.b0 = b0 * inv_a0; this.b1 = b1 * inv_a0; this.b2 = b2 * inv_a0;")
        sb.appendLine("this.a1 = a1 * inv_a0; this.a2 = a2 * inv_a0;")
        sb.appendLine(");")
        sb.appendLine()
        sb.appendLine("function calc_lshelf(freq, q, gain_db) (")
        sb.appendLine("w0 = 2 * PI * freq / srate; alpha = sin(w0) / (2 * q); A = 10 ^ (gain_db / 40);")
        sb.appendLine("cos_w0 = cos(w0); sqrt_A = sqrt(A);")
        sb.appendLine("b0 = A * ((A + 1) - (A - 1) * cos_w0 + 2 * sqrt_A * alpha);")
        sb.appendLine("b1 = 2 * A * ((A - 1) - (A + 1) * cos_w0);")
        sb.appendLine("b2 = A * ((A + 1) - (A - 1) * cos_w0 - 2 * sqrt_A * alpha);")
        sb.appendLine("a0 = (A + 1) + (A - 1) * cos_w0 + 2 * sqrt_A * alpha;")
        sb.appendLine("a1 = -2 * ((A - 1) + (A + 1) * cos_w0);")
        sb.appendLine("a2 = (A + 1) + (A - 1) * cos_w0 - 2 * sqrt_A * alpha;")
        sb.appendLine("inv_a0 = 1 / a0;")
        sb.appendLine("this.b0 = b0 * inv_a0; this.b1 = b1 * inv_a0; this.b2 = b2 * inv_a0;")
        sb.appendLine("this.a1 = a1 * inv_a0; this.a2 = a2 * inv_a0;")
        sb.appendLine(");")
        sb.appendLine()
        sb.appendLine("function calc_hshelf(freq, q, gain_db) (")
        sb.appendLine("w0 = 2 * PI * freq / srate; alpha = sin(w0) / (2 * q); A = 10 ^ (gain_db / 40);")
        sb.appendLine("cos_w0 = cos(w0); sqrt_A = sqrt(A);")
        sb.appendLine("b0 = A * ((A + 1) + (A - 1) * cos_w0 + 2 * sqrt_A * alpha);")
        sb.appendLine("b1 = -2 * A * ((A - 1) + (A + 1) * cos_w0);")
        sb.appendLine("b2 = A * ((A + 1) + (A - 1) * cos_w0 - 2 * sqrt_A * alpha);")
        sb.appendLine("a0 = (A + 1) - (A - 1) * cos_w0 + 2 * sqrt_A * alpha;")
        sb.appendLine("a1 = 2 * ((A - 1) - (A + 1) * cos_w0);")
        sb.appendLine("a2 = (A + 1) - (A - 1) * cos_w0 - 2 * sqrt_A * alpha;")
        sb.appendLine("inv_a0 = 1 / a0;")
        sb.appendLine("this.b0 = b0 * inv_a0; this.b1 = b1 * inv_a0; this.b2 = b2 * inv_a0;")
        sb.appendLine("this.a1 = a1 * inv_a0; this.a2 = a2 * inv_a0;")
        sb.appendLine(");")
        sb.appendLine()
        sb.appendLine("function process_biquad(xn) (")
        sb.appendLine("yn = this.b0 * xn + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2;")
        sb.appendLine("this.x2 = this.x1; this.x1 = xn; this.y2 = this.y1; this.y1 = yn; yn;")
        sb.appendLine(");")
        sb.appendLine()

        val activeGlobal = config.globalBands.filter { it.enabled }
        val activeLeft = config.leftBands.filter { it.enabled }
        val activeRight = config.rightBands.filter { it.enabled }

        if (activeGlobal.isNotEmpty()) {
            sb.appendLine("// 1. GLOBAL FILTERS (Applied to BOTH channels)")
            activeGlobal.forEachIndexed { idx, b ->
                val n = idx + 1
                sb.appendLine("// Filter $n: ON ${b.filterType.apoLabel} Fc ${fmtFreq(b.frequencyHz)} Hz Gain ${fmt(b.gainDb)} dB Q ${fmt(b.q)}")
                sb.appendLine("g_band${n}L.${b.filterType.eelFunction}(${fmtFreq(b.frequencyHz)}, ${fmt(b.q)}, ${fmt(b.gainDb)}); g_band${n}R.${b.filterType.eelFunction}(${fmtFreq(b.frequencyHz)}, ${fmt(b.q)}, ${fmt(b.gainDb)});")
            }
            sb.appendLine()
        }

        if (activeLeft.isNotEmpty() || activeRight.isNotEmpty()) {
            sb.appendLine("// 2. CHANNEL SPECIFIC FILTERS")
            if (activeLeft.isNotEmpty()) {
                sb.appendLine("// Channel L")
                activeLeft.forEachIndexed { idx, b ->
                    val n = idx + 1
                    sb.appendLine("// Filter $n: ON ${b.filterType.apoLabel} Fc ${fmtFreq(b.frequencyHz)} Hz Gain ${fmt(b.gainDb)} dB Q ${fmt(b.q)}")
                    sb.appendLine("l_band$n.${b.filterType.eelFunction}(${fmtFreq(b.frequencyHz)}, ${fmt(b.q)}, ${fmt(b.gainDb)});")
                }
                sb.appendLine()
            }
            if (activeRight.isNotEmpty()) {
                sb.appendLine("// Channel R")
                activeRight.forEachIndexed { idx, b ->
                    val n = idx + 1
                    sb.appendLine("// Filter $n: ON ${b.filterType.apoLabel} Fc ${fmtFreq(b.frequencyHz)} Hz Gain ${fmt(b.gainDb)} dB Q ${fmt(b.q)}")
                    sb.appendLine("r_band$n.${b.filterType.eelFunction}(${fmtFreq(b.frequencyHz)}, ${fmt(b.q)}, ${fmt(b.gainDb)});")
                }
                sb.appendLine()
            }
        }

        sb.appendLine("@sample")
        if (config.swapChannels && config.swapBeforeEq) {
            sb.appendLine("// 1. Swap Channels First (Left <-> Right)")
            sb.appendLine("swap_temp = spl0;")
            sb.appendLine("spl0 = spl1;")
            sb.appendLine("spl1 = swap_temp;")
            sb.appendLine()
        }
        sb.appendLine("// 2. Apply Total Preamp Gain (Global + L/R Preamp)")
        sb.appendLine("spl0 = spl0 * total_gain;")
        sb.appendLine("spl1 = spl1 * total_gain;")
        sb.appendLine()
        if (activeGlobal.isNotEmpty()) {
            sb.appendLine("// 3. Apply Global Filters (Same for both)")
            activeGlobal.indices.forEach { idx ->
                sb.appendLine("spl0 = g_band${idx + 1}L.process_biquad(spl0);")
            }
            activeGlobal.indices.forEach { idx ->
                sb.appendLine("spl1 = g_band${idx + 1}R.process_biquad(spl1);")
            }
            sb.appendLine()
        }
        if (activeLeft.isNotEmpty() || activeRight.isNotEmpty()) {
            sb.appendLine("// 4. Apply Channel Specific Filters (L filters to spl0, R filters to spl1)")
            activeLeft.indices.forEach { idx ->
                sb.appendLine("spl0 = l_band${idx + 1}.process_biquad(spl0);")
            }
            activeRight.indices.forEach { idx ->
                sb.appendLine("spl1 = r_band${idx + 1}.process_biquad(spl1);")
            }
            sb.appendLine()
        }
        if (config.swapChannels && !config.swapBeforeEq) {
            sb.appendLine("swap_temp = spl0;")
            sb.appendLine("spl0 = spl1;")
            sb.appendLine("spl1 = swap_temp;")
        }
        return sb.toString()
    }
}
