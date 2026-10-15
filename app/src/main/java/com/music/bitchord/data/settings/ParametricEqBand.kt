package com.music.bitchord.data.settings

/** One peaking filter in the optional software parametric equalizer. */
data class ParametricEqBand(
    val frequencyHz: Float,
    val q: Float,
    val gainDb: Float,
) {
    fun sanitized(fallback: ParametricEqBand): ParametricEqBand = ParametricEqBand(
        frequencyHz = frequencyHz.takeIf(Float::isFinite)?.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
            ?: fallback.frequencyHz,
        q = q.takeIf(Float::isFinite)?.coerceIn(MIN_Q, MAX_Q) ?: fallback.q,
        gainDb = gainDb.takeIf(Float::isFinite)?.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) ?: fallback.gainDb,
    )

    companion object {
        const val MIN_FREQUENCY_HZ = 20f
        const val MAX_FREQUENCY_HZ = 20_000f
        const val MIN_Q = 0.3f
        const val MAX_Q = 5f
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f

        val DEFAULTS = listOf(
            ParametricEqBand(frequencyHz = 100f, q = 0.707f, gainDb = 0f),
            ParametricEqBand(frequencyHz = 1_000f, q = 0.707f, gainDb = 0f),
            ParametricEqBand(frequencyHz = 8_000f, q = 0.707f, gainDb = 0f),
        )

        fun encode(bands: List<ParametricEqBand>): String = DEFAULTS.mapIndexed { index, fallback ->
            val safe = bands.getOrNull(index)?.sanitized(fallback) ?: fallback
            "${safe.frequencyHz},${safe.q},${safe.gainDb}"
        }.joinToString(";")

        fun decode(value: String?): List<ParametricEqBand> {
            val values = value?.split(';').orEmpty()
            return DEFAULTS.mapIndexed { index, fallback ->
                val fields = values.getOrNull(index)?.split(',').orEmpty()
                val parsed = if (fields.size == 3) {
                    ParametricEqBand(
                        frequencyHz = fields[0].toFloatOrNull() ?: fallback.frequencyHz,
                        q = fields[1].toFloatOrNull() ?: fallback.q,
                        gainDb = fields[2].toFloatOrNull() ?: fallback.gainDb,
                    )
                } else fallback
                parsed.sanitized(fallback)
            }
        }
    }
}
