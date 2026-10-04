package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainValuesTest {
    @Test
    fun readsTrackGainAndAppliesPreamp() {
        val gain = ReplayGainValues.calculateDb(
            properties = mapOf("REPLAYGAIN_TRACK_GAIN" to arrayOf("-6.00 dB")),
            useAlbumGain = false,
            preampDb = 1f,
            preventClipping = true,
        )

        assertEquals(-5f, gain, 0.001f)
    }

    @Test
    fun albumModeUsesAlbumGainAndAvoidsClipping() {
        val gain = ReplayGainValues.calculateDb(
            properties = mapOf(
                "REPLAYGAIN_TRACK_GAIN" to arrayOf("-8.00 dB"),
                "REPLAYGAIN_ALBUM_GAIN" to arrayOf("4.00 dB"),
                "REPLAYGAIN_ALBUM_PEAK" to arrayOf("1.5"),
            ),
            useAlbumGain = true,
            preampDb = 0f,
            preventClipping = true,
        )

        assertEquals(-20f * kotlin.math.log10(1.5f), gain, 0.001f)
    }

    @Test
    fun missingOrInvalidTagsLeaveAudioAtUnityGain() {
        assertEquals(
            0f,
            ReplayGainValues.calculateDb(emptyMap(), false, 5f, true),
            0f,
        )
        assertEquals(
            0f,
            ReplayGainValues.calculateDb(mapOf("REPLAYGAIN_TRACK_GAIN" to arrayOf("unknown")), false, 5f, true),
            0f,
        )
    }
}
