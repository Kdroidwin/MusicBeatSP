package com.music.bitchord.playback.equalizer

/**
 * Standard equalizer presets with 5-band gain offsets in millibels (100 mB = 1 dB).
 *
 * The standard center frequencies used across most Android devices for a 5-band EQ
 * are roughly: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, and 14 kHz.
 */
data class EqualizerPreset(
    val id: Int,
    val name: String,
    val bandLevels: List<Short>, // in millibels
) {
    companion object {
        val PRESETS = listOf(
            EqualizerPreset(
                id = 0,
                name = "Flat",
                bandLevels = listOf(0, 0, 0, 0, 0),
            ),
            EqualizerPreset(
                id = 1,
                name = "Bass Boost",
                bandLevels = listOf(600, 400, 100, 0, 0),
            ),
            EqualizerPreset(
                id = 2,
                name = "Rock",
                bandLevels = listOf(450, 250, -100, 250, 400),
            ),
            EqualizerPreset(
                id = 3,
                name = "Pop",
                bandLevels = listOf(-150, 150, 400, 200, -100),
            ),
            EqualizerPreset(
                id = 4,
                name = "Jazz",
                bandLevels = listOf(350, 200, -150, 200, 350),
            ),
            EqualizerPreset(
                id = 5,
                name = "Classical",
                bandLevels = listOf(400, 250, -150, 250, 350),
            ),
            EqualizerPreset(
                id = 6,
                name = "Electronic",
                bandLevels = listOf(500, 350, 0, 250, 450),
            ),
            EqualizerPreset(
                id = 7,
                name = "Hip Hop",
                bandLevels = listOf(500, 300, 0, 150, 300),
            ),
            EqualizerPreset(
                id = 8,
                name = "Vocal Boost",
                bandLevels = listOf(-200, 100, 450, 300, 100),
            ),
        )

        /**
         * Tone curves adapted from Echo-Music's Dolby/Dirac preset groups.
         * These are ordinary five-band EQ profiles, not Dolby Atmos decoding
         * or Dirac's licensed device DSP. Values are logarithmically interpolated
         * from Echo's 10-band curves to MusicBeat's five standard EQ bands.
         */
        val DOLBY_ATMOS_PRESETS = listOf(
            EqualizerPreset(100, "Atmos Open", listOf(179, 185, 203, 275, 99).map { it.toShort() }),
            EqualizerPreset(101, "Atmos Rich", listOf(157, 218, 263, 206, 69).map { it.toShort() }),
            EqualizerPreset(102, "Atmos Focused", listOf(-62, 174, 134, 106, -252).map { it.toShort() }),
        )

        val DIRAC_AUDIO_PRESETS = listOf(
            EqualizerPreset(200, "Dirac Music", listOf(143, 10, 73, 191, 337).map { it.toShort() }),
            EqualizerPreset(201, "Dirac Movie", listOf(252, 18, 113, 239, 385).map { it.toShort() }),
            EqualizerPreset(202, "Dirac Game", listOf(245, 24, 140, 427, 303).map { it.toShort() }),
        )

        val ALL_PRESETS = PRESETS + DOLBY_ATMOS_PRESETS + DIRAC_AUDIO_PRESETS

        const val CUSTOM_PRESET_ID = -1

        fun getPresetById(id: Int): EqualizerPreset? = ALL_PRESETS.firstOrNull { it.id == id }

        fun diracPresetByIndex(index: Int): EqualizerPreset =
            DIRAC_AUDIO_PRESETS[index.coerceIn(DIRAC_AUDIO_PRESETS.indices)]
    }
}
