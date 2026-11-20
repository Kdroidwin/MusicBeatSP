package com.music.bitchord.playback

import android.content.Context
import android.os.SystemClock
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.HeadphoneBurnInSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Random
import kotlinx.coroutines.flow.MutableStateFlow

/** Builds short, loopable PCM burn-in signals locally; no service or network is involved. */
object HeadphoneBurnInAudio {
    private const val SAMPLE_RATE = 44_100
    private const val CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2
    private const val LOOP_SECONDS = 60
    private const val PEAK_AMPLITUDE = 0.14
    private const val FADE_SECONDS = 0.08

    fun generatedSong(context: Context, source: HeadphoneBurnInSource): Song {
        require(source != HeadphoneBurnInSource.MUSIC)
        val file = writeSignal(context, source)
        val titleRes = when (source) {
            HeadphoneBurnInSource.WHITE_NOISE -> R.string.headphone_burn_in_white_noise
            HeadphoneBurnInSource.PINK_NOISE -> R.string.headphone_burn_in_pink_noise
            HeadphoneBurnInSource.FREQUENCY_SWEEP -> R.string.headphone_burn_in_frequency_sweep
            HeadphoneBurnInSource.MUSIC -> error("Music is supplied by the user")
        }
        val title = context.getString(titleRes)
        return Song(
            videoId = "bitchord-headphone-burn-in:${source.name.lowercase()}",
            title = title,
            artist = context.getString(R.string.headphone_burn_in_artist),
            thumbnailUrl = null,
            localUri = android.net.Uri.fromFile(file).toString(),
            localPath = file.absolutePath,
            durationText = "1:00",
            isExternalPreview = true,
            isHeadphoneBurnIn = true,
        )
    }

    private fun writeSignal(context: Context, source: HeadphoneBurnInSource): File {
        val directory = File(context.cacheDir, "headphone-burn-in").apply { mkdirs() }
        val destination = File(directory, "${source.name.lowercase()}-44100-16bit-stereo.wav")
        if (destination.isFile && destination.length() == HEADER_BYTES + PCM_DATA_BYTES) return destination

        val temporary = File(directory, destination.name + ".tmp")
        val frameCount = SAMPLE_RATE * LOOP_SECONDS
        val dataBytes = frameCount * CHANNELS * BYTES_PER_SAMPLE
        BufferedOutputStream(FileOutputStream(temporary), 64 * 1024).use { output ->
            writeWavHeader(output, dataBytes)
            val random = Random(0x4D425350L + source.ordinal)
            var p0 = 0.0
            var p1 = 0.0
            var p2 = 0.0
            var p3 = 0.0
            var p4 = 0.0
            var p5 = 0.0
            var p6 = 0.0
            var phase = 0.0
            val fadeFrames = (SAMPLE_RATE * FADE_SECONDS).roundToInt().coerceAtLeast(1)
            for (frame in 0 until frameCount) {
                val raw = when (source) {
                    HeadphoneBurnInSource.WHITE_NOISE -> random.nextDouble() * 2.0 - 1.0
                    HeadphoneBurnInSource.PINK_NOISE -> {
                        val white = random.nextDouble() * 2.0 - 1.0
                        p0 = 0.99886 * p0 + white * 0.0555179
                        p1 = 0.99332 * p1 + white * 0.0750759
                        p2 = 0.96900 * p2 + white * 0.1538520
                        p3 = 0.86650 * p3 + white * 0.3104856
                        p4 = 0.55000 * p4 + white * 0.5329522
                        p5 = -0.7616 * p5 - white * 0.0168980
                        val pink = p0 + p1 + p2 + p3 + p4 + p5 + p6 + white * 0.5362
                        p6 = white * 0.115926
                        pink * 0.55
                    }
                    HeadphoneBurnInSource.FREQUENCY_SWEEP -> {
                        val half = frame.toDouble() / (frameCount - 1).coerceAtLeast(1)
                        val sweepPosition = if (half <= 0.5) half * 2.0 else (1.0 - half) * 2.0
                        val frequency = 30.0 * (18_000.0 / 30.0).pow(sweepPosition)
                        val sample = sin(phase)
                        phase += 2.0 * PI * frequency / SAMPLE_RATE
                        if (phase >= 2.0 * PI) phase %= 2.0 * PI
                        sample
                    }
                    HeadphoneBurnInSource.MUSIC -> error("Music is supplied by the user")
                }
                val fade = min(
                    1.0,
                    min(frame.toDouble() / fadeFrames, (frameCount - 1 - frame).toDouble() / fadeFrames),
                ).coerceIn(0.0, 1.0)
                val pcm = (raw * PEAK_AMPLITUDE * fade * Short.MAX_VALUE)
                    .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val low = pcm and 0xff
                val high = (pcm ushr 8) and 0xff
                repeat(CHANNELS) {
                    output.write(low)
                    output.write(high)
                }
            }
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("Could not replace headphone burn-in signal")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("Could not finalize headphone burn-in signal")
        }
        return destination
    }

    private fun writeWavHeader(output: BufferedOutputStream, dataBytes: Int) {
        output.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        writeLe32(output, 36 + dataBytes)
        output.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        output.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        writeLe32(output, 16)
        writeLe16(output, 1)
        writeLe16(output, CHANNELS)
        writeLe32(output, SAMPLE_RATE)
        writeLe32(output, SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
        writeLe16(output, CHANNELS * BYTES_PER_SAMPLE)
        writeLe16(output, BYTES_PER_SAMPLE * 8)
        output.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        writeLe32(output, dataBytes)
    }

    private fun writeLe16(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeLe32(output: BufferedOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private const val HEADER_BYTES = 44L
    private const val PCM_DATA_BYTES = 44_100L * LOOP_SECONDS * CHANNELS * BYTES_PER_SAMPLE
}

/** In-process status for the settings surface; playback itself is owned by PlaybackService. */
object HeadphoneBurnInRuntime {
    val deadlineElapsedMs = MutableStateFlow<Long?>(null)
    val isRunning: Boolean get() = deadlineElapsedMs.value != null

    fun start(durationMinutes: Int) {
        deadlineElapsedMs.value = SystemClock.elapsedRealtime() + durationMinutes.coerceIn(5, 6_000) * 60_000L
    }

    fun stop() {
        deadlineElapsedMs.value = null
    }
}
