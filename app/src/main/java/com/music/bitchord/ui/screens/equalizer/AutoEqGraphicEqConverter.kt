package com.music.bitchord.ui.screens.equalizer

import com.music.bitchord.data.settings.ParametricEqBand
import com.music.bitchord.data.settings.ParametricEqConfig
import com.music.bitchord.data.settings.ParametricEqFilterType
import com.music.bitchord.playback.equalizer.BiquadUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin

/** Fits a bounded PEQ bank to AutoEQ's GraphicEQ curve without changing the audio path. */
internal object AutoEqGraphicEqConverter {
    data class Point(val frequencyHz: Float, val gainDb: Float)

    private const val FIT_FILTER_COUNT = 32
    private const val FIT_SWEEPS = 5
    private const val FIT_SAMPLE_RATE_HZ = 96_000.0
    private const val FILTER_Q = 4.318f // approximately one-third octave
    private const val GAIN_STEP_DB = 0.5
    private const val GAIN_LIMIT_DB = 18.0
    private const val MAX_FIT_POINTS = 512

    fun convert(points: List<Point>, profileName: String): ParametricEqConfig {
        val usable = points.asSequence()
            .filter { it.frequencyHz.isFinite() && it.gainDb.isFinite() && it.frequencyHz in 10f..24_000f }
            .sortedBy(Point::frequencyHz)
            .distinctBy(Point::frequencyHz)
            .toList()
        require(usable.size >= 10) { "AutoEQ profile has too few valid frequency points" }

        val fitPointCount = minOf(MAX_FIT_POINTS, maxOf(usable.size, 256))
        val fitPoints = if (usable.size <= 10) usable else {
            val minimumLog = ln(usable.first().frequencyHz.toDouble())
            val maximumLog = ln(usable.last().frequencyHz.toDouble())
            List(fitPointCount) { index ->
                val fraction = index.toDouble() / (fitPointCount - 1)
                val frequency = kotlin.math.exp(minimumLog + (maximumLog - minimumLog) * fraction).toFloat()
                Point(frequency, interpolateAt(usable, frequency))
            }
        }
        val frequencies = DoubleArray(fitPoints.size) { fitPoints[it].frequencyHz.toDouble() }
        val target = DoubleArray(fitPoints.size) { fitPoints[it].gainDb.toDouble().coerceIn(-30.0, 30.0) }
        val weights = logarithmicFrequencyWeights(frequencies)
        val centers = DoubleArray(FIT_FILTER_COUNT) { index -> 20.0 * 2.0.pow(index / 3.0) }
            .filter { it <= 20_500.0 }
        val gainOptions = (-((GAIN_LIMIT_DB / GAIN_STEP_DB).toInt())..
            (GAIN_LIMIT_DB / GAIN_STEP_DB).toInt()).map { it * GAIN_STEP_DB }.toDoubleArray()

        // Cache the bounded filter response matrix. Conversion runs off the UI/audio thread.
        val response = Array(centers.size) { bandIndex ->
            Array(gainOptions.size) { optionIndex ->
                val band = ParametricEqBand(
                    frequencyHz = centers[bandIndex].toFloat(),
                    q = FILTER_Q,
                    gainDb = gainOptions[optionIndex].toFloat(),
                )
                val coefficients = BiquadUtils.computeCoefficients(band, FIT_SAMPLE_RATE_HZ)
                DoubleArray(frequencies.size) { pointIndex ->
                    magnitudeDb(coefficients, frequencies[pointIndex], FIT_SAMPLE_RATE_HZ)
                }
            }
        }

        val zeroGainIndex = gainOptions.size / 2
        val selected = IntArray(centers.size) { zeroGainIndex }
        val totalResponse = DoubleArray(frequencies.size)
        repeat(FIT_SWEEPS) {
            centers.indices.forEach { bandIndex ->
                val prior = response[bandIndex][selected[bandIndex]]
                for (pointIndex in totalResponse.indices) totalResponse[pointIndex] -= prior[pointIndex]

                var bestIndex = selected[bandIndex]
                var bestError = Double.POSITIVE_INFINITY
                response[bandIndex].indices.forEach { optionIndex ->
                    val candidate = response[bandIndex][optionIndex]
                    var error = 0.0
                    for (pointIndex in totalResponse.indices) {
                        val residual = target[pointIndex] - totalResponse[pointIndex] - candidate[pointIndex]
                        error += residual * residual * weights[pointIndex]
                    }
                    if (error < bestError) {
                        bestError = error
                        bestIndex = optionIndex
                    }
                }

                selected[bandIndex] = bestIndex
                val next = response[bandIndex][bestIndex]
                for (pointIndex in totalResponse.indices) totalResponse[pointIndex] += next[pointIndex]
            }
        }

        val fittedBands = centers.indices.mapNotNull { bandIndex ->
            val gain = gainOptions[selected[bandIndex]].toFloat()
            gain.takeIf { kotlin.math.abs(it) >= 0.25f }?.let {
                ParametricEqBand(
                    frequencyHz = centers[bandIndex].toFloat(),
                    q = FILTER_Q,
                    gainDb = it,
                    filterType = ParametricEqFilterType.PEAKING,
                )
            }
        }.take(ParametricEqBand.MAX_BANDS_PER_CHANNEL)

        val peakBoostDb = totalResponse.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val preampDb = if (peakBoostDb > 0.0) (-peakBoostDb - 1.0).toFloat().coerceAtLeast(-30f) else 0f
        require(fittedBands.isNotEmpty()) { "AutoEQ profile could not be converted to PEQ" }

        return ParametricEqConfig(
            enabled = true,
            globalPreampDb = preampDb,
            globalBands = fittedBands,
            activePresetId = null,
            activePresetName = profileName,
            sourceFileName = "AutoEQ GraphicEQ",
            notes = "Approximated from the AutoEQ GraphicEQ response using 1/3-octave PEQ filters.",
        ).sanitized()
    }

    private fun logarithmicFrequencyWeights(frequencies: DoubleArray): DoubleArray {
        if (frequencies.size == 1) return doubleArrayOf(1.0)
        val logs = DoubleArray(frequencies.size) { log2(frequencies[it]) }
        return DoubleArray(frequencies.size) { index ->
            when (index) {
                0 -> (logs[1] - logs[0]).coerceAtLeast(1e-6)
                frequencies.lastIndex -> (logs[index] - logs[index - 1]).coerceAtLeast(1e-6)
                else -> ((logs[index + 1] - logs[index - 1]) / 2.0).coerceAtLeast(1e-6)
            }
        }
    }

    private fun interpolateAt(points: List<Point>, frequencyHz: Float): Float {
        val lowerIndex = points.indexOfLast { it.frequencyHz <= frequencyHz }.coerceAtLeast(0)
        val lower = points[lowerIndex]
        val upper = points.getOrNull(lowerIndex + 1) ?: return lower.gainDb
        if (upper.frequencyHz <= lower.frequencyHz) return lower.gainDb
        val span = ln(upper.frequencyHz / lower.frequencyHz)
        if (!span.isFinite() || span <= 0f) return lower.gainDb
        val fraction = (ln(frequencyHz / lower.frequencyHz) / span).coerceIn(0f, 1f)
        return lower.gainDb + (upper.gainDb - lower.gainDb) * fraction
    }

    private fun magnitudeDb(
        coefficients: BiquadUtils.BiquadCoefficients,
        frequencyHz: Double,
        sampleRateHz: Double,
    ): Double {
        val omega = 2.0 * PI * frequencyHz / sampleRateHz
        val cos1 = cos(omega)
        val sin1 = sin(omega)
        val cos2 = cos(2.0 * omega)
        val sin2 = sin(2.0 * omega)
        val numeratorReal = coefficients.b0 + coefficients.b1 * cos1 + coefficients.b2 * cos2
        val numeratorImaginary = -coefficients.b1 * sin1 - coefficients.b2 * sin2
        val denominatorReal = 1.0 + coefficients.a1 * cos1 + coefficients.a2 * cos2
        val denominatorImaginary = -coefficients.a1 * sin1 - coefficients.a2 * sin2
        val numeratorMagnitude = kotlin.math.hypot(numeratorReal, numeratorImaginary)
        val denominatorMagnitude = kotlin.math.hypot(denominatorReal, denominatorImaginary).coerceAtLeast(1e-15)
        return 20.0 * kotlin.math.log10((numeratorMagnitude / denominatorMagnitude).coerceAtLeast(1e-12))
    }

    private fun Double.pow(power: Double): Double = kotlin.math.exp(ln(this) * power)
}
