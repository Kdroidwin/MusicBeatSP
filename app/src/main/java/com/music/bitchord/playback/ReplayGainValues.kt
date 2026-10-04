package com.music.bitchord.playback

import kotlin.math.log10

/** Pure ReplayGain tag selection and gain calculation, kept separate from file I/O. */
object ReplayGainValues {
    fun calculateDb(
        properties: Map<String, Array<String>>,
        useAlbumGain: Boolean,
        preampDb: Float,
        preventClipping: Boolean,
    ): Float {
        fun value(suffix: String): String? = properties.entries
            .firstOrNull { (key, _) -> key.equals(suffix, ignoreCase = true) || key.endsWith(suffix, ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()

        fun parseGain(raw: String?): Float? = raw
            ?.replace(Regex("(?i)\\s*dB\\s*$"), "")
            ?.trim()
            ?.toFloatOrNull()

        val rawGain = if (useAlbumGain) {
            value("REPLAYGAIN_ALBUM_GAIN") ?: value("REPLAYGAIN_TRACK_GAIN")
        } else {
            value("REPLAYGAIN_TRACK_GAIN") ?: value("REPLAYGAIN_ALBUM_GAIN")
        }
        val taggedGain = parseGain(rawGain) ?: return 0f
        val rawPeak = if (useAlbumGain) {
            value("REPLAYGAIN_ALBUM_PEAK") ?: value("REPLAYGAIN_TRACK_PEAK")
        } else {
            value("REPLAYGAIN_TRACK_PEAK") ?: value("REPLAYGAIN_ALBUM_PEAK")
        }
        val peak = rawPeak?.toFloatOrNull()
        var result = (taggedGain + preampDb).coerceIn(-24f, 12f)
        if (preventClipping && peak != null && peak > 0f && result > 0f) {
            result = minOf(result, (-20f * log10(peak)).coerceAtLeast(-24f))
        }
        return result.coerceIn(-24f, 12f)
    }
}
