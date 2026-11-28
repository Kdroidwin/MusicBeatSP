package com.music.bitchord.playback

import android.media.AudioFormat
import androidx.media3.common.Format
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Downmix channels and exact per-channel stereo panning coefficients.
 *
 * The user's 9.1.6 reference matrix is defined here; on 7.1.4ch (12ch) streams,
 * the 12 active channels (`FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBL, TBR`)
 * map directly to these coefficients, while 5.1 / 7.1 / 9.1.6 streams also
 * resolve cleanly.
 */
enum class DownmixChannel(
    val id: String,
    val label: String,
    val leftGain: Double,
    val rightGain: Double,
    val isLfe: Boolean,
    val androidOutBit: Int,
) {
    FL("FL", "FL", 1.0, 0.0, false, AudioFormat.CHANNEL_OUT_FRONT_LEFT),
    FR("FR", "FR", 0.0, 1.0, false, AudioFormat.CHANNEL_OUT_FRONT_RIGHT),
    FC("FC", "FC", 0.70710678, 0.70710678, false, AudioFormat.CHANNEL_OUT_FRONT_CENTER),
    LFE("LFE", "LFE", 2.26464431, 2.26464431, true, AudioFormat.CHANNEL_OUT_LOW_FREQUENCY),
    BL("BL", "BL", 1.0, 0.0, false, AudioFormat.CHANNEL_OUT_BACK_LEFT),
    BR("BR", "BR", 0.0, 1.0, false, AudioFormat.CHANNEL_OUT_BACK_RIGHT),
    BLC("BLC", "BLC", 1.0, 0.0, false, AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER),
    BRC("BRC", "BRC", 0.0, 1.0, false, AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER),
    BC("BC", "BC", 0.70710678, 0.70710678, false, AudioFormat.CHANNEL_OUT_BACK_CENTER),
    SL("SL", "SL", 1.0, 0.0, false, AudioFormat.CHANNEL_OUT_SIDE_LEFT),
    SR("SR", "SR", 0.0, 1.0, false, AudioFormat.CHANNEL_OUT_SIDE_RIGHT),
    TFL("TFL", "TFL", 1.0, 0.0, false, 0x00004000), // AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
    TFR("TFR", "TFR", 0.0, 1.0, false, 0x00010000), // AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT
    TBL("TBL", "TBL", 1.0, 0.0, false, 0x00020000), // AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT
    TBR("TBR", "TBR", 0.0, 1.0, false, 0x00080000), // AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT
    TSL("TSL", "TSL", 1.0, 0.0, false, 0x00100000), // AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT
    TSR("TSR", "TSR", 0.0, 1.0, false, 0x00200000), // AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT
    FWL("FWL", "FWL", 1.0, 0.0, false, 0x04000000), // AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT
    FWR("FWR", "FWR", 0.0, 1.0, false, 0x08000000), // AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT
    ;

    companion object {
        /** Exact 12-channel 7.1.4 layout in Android PCM frame order (LSB -> MSB of 0xB58FC). */
        val LAYOUT_7_1_4: List<DownmixChannel> = listOf(
            FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBL, TBR,
        )

        val LAYOUT_5_1: List<DownmixChannel> = listOf(
            FL, FR, FC, LFE, BL, BR,
        )

        /** Standard 4.1 channel order: FL, FR, LFE, back-left, back-right. */
        val LAYOUT_4_1: List<DownmixChannel> = listOf(
            FL, FR, LFE, BL, BR,
        )

        val LAYOUT_7_1: List<DownmixChannel> = listOf(
            FL, FR, FC, LFE, BL, BR, SL, SR,
        )

        val LAYOUT_9_1_6: List<DownmixChannel> = listOf(
            FL, FR, FC, LFE, BL, BR, BLC, BRC, SL, SR, TFL, TFR, TBL, TBR, TSL, TSR,
        )

        private val BY_OUT_BIT: List<Pair<Int, DownmixChannel>> = listOf(
            0x00000004 to FL,
            0x00000008 to FR,
            0x00000010 to FC,
            0x00000020 to LFE,
            0x00000040 to BL,
            0x00000080 to BR,
            0x00000100 to BLC,
            0x00000200 to BRC,
            0x00000400 to BC,
            0x00000800 to SL,
            0x00001000 to SR,
            0x00002000 to FC, // TOP_CENTER
            0x00004000 to TFL,
            0x00008000 to FC, // TOP_FRONT_CENTER
            0x00010000 to TFR,
            0x00020000 to TBL,
            0x00040000 to FC, // TOP_BACK_CENTER
            0x00080000 to TBR,
            0x00100000 to TSL,
            0x00200000 to TSR,
            0x00400000 to FL, // BOTTOM_FRONT_LEFT
            0x00800000 to FC, // BOTTOM_FRONT_CENTER
            0x01000000 to FR, // BOTTOM_FRONT_RIGHT
            0x02000000 to LFE, // LOW_FREQUENCY_2
            0x04000000 to FWL,
            0x08000000 to FWR,
        )

        fun resolveChannelLayout(channelCount: Int, channelMask: Int): List<DownmixChannel> {
            if (channelCount <= 0) return emptyList()
            if (channelMask != 0 && channelMask != Format.NO_VALUE) {
                val javaMask = if ((channelMask and 0x3) != 0) channelMask shl 2 else channelMask
                val decoded = BY_OUT_BIT.mapNotNull { (bit, channel) ->
                    if ((javaMask and bit) != 0) channel else null
                }
                if (decoded.size == channelCount) {
                    return decoded
                }
            }
            return when (channelCount) {
                5 -> LAYOUT_4_1
                6 -> LAYOUT_5_1
                8 -> LAYOUT_7_1
                12 -> LAYOUT_7_1_4
                16 -> LAYOUT_9_1_6
                else -> List(channelCount) { idx ->
                    LAYOUT_7_1_4.getOrNull(idx) ?: if (idx % 2 == 0) FL else FR
                }
            }
        }

        fun layoutLabel(channelCount: Int): String = when (channelCount) {
            0 -> "—"
            1 -> "1.0 Mono"
            2 -> "2.0 Stereo"
            5 -> "4.1 (5ch)"
            6 -> "5.1 (6ch)"
            8 -> "7.1 (8ch)"
            12 -> "7.1.4 (12ch)"
            16 -> "9.1.6 (16ch)"
            else -> "${channelCount}ch"
        }
    }
}

enum class DownmixLfeFilterMode(val key: String) {
    BUTTERWORTH_4_DELAY("butterworth4_delay"),
    LR4_ALLPASS("lr4_allpass"),
    ;

    companion object {
        fun fromKey(key: String?): DownmixLfeFilterMode =
            entries.firstOrNull { it.key == key } ?: BUTTERWORTH_4_DELAY
    }
}

data class DownmixConfig(
    val enabled: Boolean = false,
    val preampDb: Float = DEFAULT_PREAMP_DB,
    val mutedChannels: Set<String> = emptySet(),
    val soloChannels: Set<String> = emptySet(),
    val lfeFilterMode: DownmixLfeFilterMode = DownmixLfeFilterMode.BUTTERWORTH_4_DELAY,
    val autoHeadroom: Boolean = false,
) {
    fun isChannelAudible(channelId: String): Boolean =
        if (soloChannels.isNotEmpty()) channelId in soloChannels else channelId !in mutedChannels

    companion object {
        const val MIN_PREAMP_DB = -24.0f
        const val MAX_PREAMP_DB = 6.0f
        const val DEFAULT_PREAMP_DB = -6.0f
    }
}

/**
 * Lock-free-friendly telemetry bus feeding the Downmix Settings screen with
 * real-time per-channel input/contributed gain and final summed L/R output.
 * Audio-thread metering work runs only while the UI screen has an attached observer.
 */
object MultichannelDownmixMonitor {

    data class ChannelMeter(
        val channel: DownmixChannel,
        val inputPeakDb: Float = Float.NEGATIVE_INFINITY,
        val inputRmsDb: Float = Float.NEGATIVE_INFINITY,
        val contributedLeftPeakDb: Float = Float.NEGATIVE_INFINITY,
        val contributedRightPeakDb: Float = Float.NEGATIVE_INFINITY,
        val audible: Boolean = true,
        val activeInStream: Boolean = false,
    )

    data class Snapshot(
        val isActive: Boolean = false,
        val isDownmixing: Boolean = false,
        val bypassedByBitPerfect: Boolean = false,
        val inputChannelCount: Int = 0,
        val inputChannelMask: Int = 0,
        val sampleRateHz: Int = 0,
        val dryDelaySamples: Int = 172,
        val lfeFilterMode: DownmixLfeFilterMode = DownmixLfeFilterMode.BUTTERWORTH_4_DELAY,
        val layoutName: String = "—",
        val channels: List<ChannelMeter> = defaultMeters(),
        val summedLeftPrePreampDb: Float = Float.NEGATIVE_INFINITY,
        val summedRightPrePreampDb: Float = Float.NEGATIVE_INFINITY,
        val summedLeftPrePreampRmsDb: Float = Float.NEGATIVE_INFINITY,
        val summedRightPrePreampRmsDb: Float = Float.NEGATIVE_INFINITY,
        val outputLeftPeakDb: Float = Float.NEGATIVE_INFINITY,
        val outputRightPeakDb: Float = Float.NEGATIVE_INFINITY,
        val outputLeftRmsDb: Float = Float.NEGATIVE_INFINITY,
        val outputRightRmsDb: Float = Float.NEGATIVE_INFINITY,
        val clippingLeft: Boolean = false,
        val clippingRight: Boolean = false,
        val clippedSamples: Long = 0L,
        val preampDb: Float = DownmixConfig.DEFAULT_PREAMP_DB,
        val publishSequence: Long = 0L,
    )

    val snapshot = MutableStateFlow(Snapshot())
    private val observerCount = AtomicInteger(0)
    private val clipResetGeneration = AtomicLong(0L)

    fun onUiAttached() {
        observerCount.incrementAndGet()
    }

    fun onUiDetached() {
        observerCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun hasObservers(): Boolean = observerCount.get() > 0

    fun clipResetGeneration(): Long = clipResetGeneration.get()

    fun requestClipReset() {
        clipResetGeneration.incrementAndGet()
        val current = snapshot.value
        if (current.clippedSamples != 0L || current.clippingLeft || current.clippingRight) {
            snapshot.value = current.copy(
                clippedSamples = 0L,
                clippingLeft = false,
                clippingRight = false,
            )
        }
    }

    fun publish(value: Snapshot) {
        snapshot.value = value
    }

    fun defaultMeters(config: DownmixConfig = DownmixConfig()): List<ChannelMeter> =
        DownmixChannel.LAYOUT_7_1_4.map { ch ->
            ChannelMeter(
                channel = ch,
                audible = config.isChannelAudible(ch.id),
                activeInStream = false,
            )
        }
}
