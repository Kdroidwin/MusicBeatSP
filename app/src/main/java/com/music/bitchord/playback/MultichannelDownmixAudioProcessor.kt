package com.music.bitchord.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.ParametricEqFilterType
import com.music.bitchord.playback.equalizer.BiquadUtils
import com.music.bitchord.playback.equalizer.BiquadUtils.BiquadCoefficients
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Downmixes decoded multichannel PCM (such as 7.1.4ch 12ch Dolby Atmos or 5.1/7.1)
 * to 2-channel stereo inside Media3's AudioProcessor pipeline:
 * - Per-channel stereo gain matrix (matching the 9.1.6 / 7.1.4 specification)
 * - Selectable LFE/main phase-alignment topology:
 *   1) 4th-order Butterworth LPF at 125 Hz + 172-sample (3.58333 ms @ 48 kHz) dry delay
 *   2) 4th-order Linkwitz-Riley (LR4) LPF at 125 Hz + matched 2nd-order All-Pass filter (zero pre-ringing)
 * - Clipping-prevention preamp + optional Auto-Headroom + clip sample counter
 * - Per-channel Mute/Solo + ~60 Hz real-time telemetry.
 */
@UnstableApi
class MultichannelDownmixAudioProcessor : BaseAudioProcessor() {

    private class BiquadState {
        var x1: Double = 0.0
        var x2: Double = 0.0
        var y1: Double = 0.0
        var y2: Double = 0.0

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        fun process(xn: Double, c: BiquadCoefficients): Double {
            var yn = c.b0 * xn + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1
            x1 = xn
            y2 = y1
            if (!yn.isFinite()) {
                reset()
                return 0.0
            }
            if (abs(yn) < 1e-30) yn = 0.0
            y1 = yn
            return yn
        }
    }

    @Volatile
    var config: DownmixConfig = DownmixConfig()

    @Volatile
    var bypassForBitPerfect: Boolean = false

    @Volatile
    var isPrimaryChain: Boolean = true

    @Volatile
    var onAutoHeadroomAdjusted: ((Float) -> Unit)? = null

    @Volatile
    var isActivelyDownmixing: Boolean = false
        private set

    @Volatile
    private var pendingChannelMask: Int = Format.NO_VALUE

    private var activeChannels: List<DownmixChannel> = emptyList()
    private var activeChannelMask: Int = Format.NO_VALUE

    // LFE 125 Hz filters: 4th-order Butterworth OR 4th-order Linkwitz-Riley (LR4)
    private var bwCoeffs1 = BiquadCoefficients()
    private var bwCoeffs2 = BiquadCoefficients()
    private var lr4Coeffs = BiquadCoefficients()
    private var ap2Coeffs = BiquadCoefficients()
    private val lfeState1 = BiquadState()
    private val lfeState2 = BiquadState()
    private val apStateL = BiquadState()
    private val apStateR = BiquadState()
    private var appliedLfeFilterMode = DownmixLfeFilterMode.BUTTERWORTH_4_DELAY

    // Non-LFE dry delay line (172 samples @ 48 kHz = 3.58333 ms)
    private var delaySamples = DEFAULT_DELAY_SAMPLES
    private var dryDelayL = DoubleArray(DEFAULT_DELAY_SAMPLES)
    private var dryDelayR = DoubleArray(DEFAULT_DELAY_SAMPLES)
    private var dryDelayIndex = 0

    // Preallocated per-frame work arrays (re-sized only in onConfigure)
    private var slotAudible = BooleanArray(0)
    private var slotGainL = DoubleArray(0)
    private var slotGainR = DoubleArray(0)
    private var slotIsLfe = BooleanArray(0)

    // Clip tracking (always runs even when UI is detached)
    private val localClipResetGen = AtomicLong(0L)
    private var seenLocalClipResetGen = 0L
    private var seenMonitorClipResetGen = 0L
    private var totalClippedSamples = 0L
    private var publishSeq = 0L

    // Metering accumulators (written on audio thread; snapshot built only when UI observes)
    private var meterInPeak = DoubleArray(0)
    private var meterInSumSq = DoubleArray(0)
    private var meterContribPeakL = DoubleArray(0)
    private var meterContribPeakR = DoubleArray(0)
    private var meterPreSumPeakL = 0.0
    private var meterPreSumPeakR = 0.0
    private var meterPreSumSumSqL = 0.0
    private var meterPreSumSumSqR = 0.0
    private var meterOutPeakL = 0.0
    private var meterOutPeakR = 0.0
    private var meterOutSumSqL = 0.0
    private var meterOutSumSqR = 0.0
    private var meterClippingL = false
    private var meterClippingR = false
    private var meterFrames = 0L
    private var lastPublishNs = 0L

    fun setPendingChannelMask(channelMask: Int) {
        pendingChannelMask = channelMask
    }

    fun resetClipCounter() {
        localClipResetGen.incrementAndGet()
        if (isPrimaryChain) {
            val currentSnap = MultichannelDownmixMonitor.snapshot.value
            if (currentSnap.clippedSamples != 0L || currentSnap.clippingLeft || currentSnap.clippingRight) {
                MultichannelDownmixMonitor.publish(
                    currentSnap.copy(
                        clippedSamples = 0L,
                        clippingLeft = false,
                        clippingRight = false,
                    ),
                )
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supportedEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supportedEncoding || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            isActivelyDownmixing = false
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val current = config
        val bypassed = bypassForBitPerfect
        val effectiveEnabled = current.enabled && !bypassed
        val chCount = inputAudioFormat.channelCount
        val sr = inputAudioFormat.sampleRate
        val mask = pendingChannelMask
        activeChannelMask = mask

        if (!effectiveEnabled || chCount <= 2) {
            isActivelyDownmixing = false
            activeChannels = emptyList()
            totalClippedSamples = 0L
            seenLocalClipResetGen = localClipResetGen.get()
            seenMonitorClipResetGen = MultichannelDownmixMonitor.clipResetGeneration()
            if (isPrimaryChain) {
                val layout = if (chCount > 2) {
                    DownmixChannel.resolveChannelLayout(chCount, mask)
                } else {
                    emptyList()
                }
                publishSeq++
                MultichannelDownmixMonitor.publish(
                    MultichannelDownmixMonitor.Snapshot(
                        isActive = true,
                        isDownmixing = false,
                        bypassedByBitPerfect = bypassed,
                        inputChannelCount = chCount,
                        inputChannelMask = if (mask == Format.NO_VALUE) 0 else mask,
                        sampleRateHz = sr,
                        dryDelaySamples = computeDelaySamples(sr),
                        lfeFilterMode = current.lfeFilterMode,
                        layoutName = DownmixChannel.layoutLabel(chCount),
                        channels = buildMeterList(layout, current, active = false),
                        clippedSamples = 0L,
                        preampDb = current.preampDb,
                        publishSequence = publishSeq,
                    ),
                )
            }
            return AudioProcessor.AudioFormat.NOT_SET
        }

        val resolved = DownmixChannel.resolveChannelLayout(chCount, mask)
        activeChannels = resolved
        isActivelyDownmixing = true
        appliedLfeFilterMode = current.lfeFilterMode

        // 4th-order Butterworth LPF at 125 Hz (two cascaded 2nd-order sections)
        bwCoeffs1 = BiquadUtils.computeCoefficients(
            frequency = LFE_CUTOFF_HZ,
            gainDb = 0.0,
            q = BUTTERWORTH_4_Q1,
            filterType = ParametricEqFilterType.LOW_PASS,
            sampleRate = sr.toDouble(),
        )
        bwCoeffs2 = BiquadUtils.computeCoefficients(
            frequency = LFE_CUTOFF_HZ,
            gainDb = 0.0,
            q = BUTTERWORTH_4_Q2,
            filterType = ParametricEqFilterType.LOW_PASS,
            sampleRate = sr.toDouble(),
        )
        // 4th-order Linkwitz-Riley (LR4) LPF + matched 2nd-order All-Pass at 125 Hz
        lr4Coeffs = BiquadUtils.computeCoefficients(
            frequency = LFE_CUTOFF_HZ,
            gainDb = 0.0,
            q = LR4_Q,
            filterType = ParametricEqFilterType.LOW_PASS,
            sampleRate = sr.toDouble(),
        )
        ap2Coeffs = BiquadUtils.computeCoefficients(
            frequency = LFE_CUTOFF_HZ,
            gainDb = 0.0,
            q = LR4_Q,
            filterType = ParametricEqFilterType.ALL_PASS,
            sampleRate = sr.toDouble(),
        )
        resetFilterStates(sr)

        slotAudible = BooleanArray(chCount)
        slotGainL = DoubleArray(chCount) { idx -> resolved[idx].leftGain }
        slotGainR = DoubleArray(chCount) { idx -> resolved[idx].rightGain }
        slotIsLfe = BooleanArray(chCount) { idx -> resolved[idx].isLfe }

        meterInPeak = DoubleArray(chCount)
        meterInSumSq = DoubleArray(chCount)
        meterContribPeakL = DoubleArray(chCount)
        meterContribPeakR = DoubleArray(chCount)
        totalClippedSamples = 0L
        lastPublishNs = 0L
        seenLocalClipResetGen = localClipResetGen.get()
        seenMonitorClipResetGen = MultichannelDownmixMonitor.clipResetGeneration()
        resetMeterAccumulators()

        val maskHex = if (mask == Format.NO_VALUE) "unset" else "0x${Integer.toHexString(mask)}"
        Log.i(
            TAG,
            "configured: ${sr}Hz ${chCount}ch (mask=$maskHex) [${resolved.joinToString(",") { it.id }}] " +
                "-> 2ch stereo, mode=${appliedLfeFilterMode.key}, fc=${LFE_CUTOFF_HZ}Hz, dryDelay=${delaySamples}spl",
        )

        if (isPrimaryChain) {
            publishSeq++
            MultichannelDownmixMonitor.publish(
                MultichannelDownmixMonitor.Snapshot(
                    isActive = true,
                    isDownmixing = true,
                    bypassedByBitPerfect = false,
                    inputChannelCount = chCount,
                    inputChannelMask = if (mask == Format.NO_VALUE) 0 else mask,
                    sampleRateHz = sr,
                    dryDelaySamples = delaySamples,
                    lfeFilterMode = appliedLfeFilterMode,
                    layoutName = DownmixChannel.layoutLabel(chCount),
                    channels = buildMeterList(resolved, current, active = true),
                    clippedSamples = 0L,
                    preampDb = current.preampDb,
                    publishSequence = publishSeq,
                ),
            )
        }

        return AudioProcessor.AudioFormat(sr, 2, inputAudioFormat.encoding)
    }

    override fun onFlush() {
        resetFilterStates(inputAudioFormat.sampleRate)
        totalClippedSamples = 0L
        lastPublishNs = 0L
        seenLocalClipResetGen = localClipResetGen.get()
        seenMonitorClipResetGen = MultichannelDownmixMonitor.clipResetGeneration()
        resetMeterAccumulators()
    }

    override fun onReset() {
        isActivelyDownmixing = false
        activeChannels = emptyList()
        lfeState1.reset()
        lfeState2.reset()
        apStateL.reset()
        apStateR.reset()
        dryDelayL = DoubleArray(DEFAULT_DELAY_SAMPLES)
        dryDelayR = DoubleArray(DEFAULT_DELAY_SAMPLES)
        dryDelayIndex = 0
        totalClippedSamples = 0L
        lastPublishNs = 0L
        seenLocalClipResetGen = localClipResetGen.get()
        seenMonitorClipResetGen = MultichannelDownmixMonitor.clipResetGeneration()
        resetMeterAccumulators()
        if (isPrimaryChain) {
            publishSeq++
            MultichannelDownmixMonitor.publish(
                MultichannelDownmixMonitor.Snapshot(
                    isActive = false,
                    isDownmixing = false,
                    lfeFilterMode = config.lfeFilterMode,
                    channels = MultichannelDownmixMonitor.defaultMeters(config),
                    clippedSamples = 0L,
                    preampDb = config.preampDb,
                    publishSequence = publishSeq,
                ),
            )
        }
    }

    private fun resetFilterStates(sampleRateHz: Int) {
        lfeState1.reset()
        lfeState2.reset()
        apStateL.reset()
        apStateR.reset()
        val targetDelay = computeDelaySamples(sampleRateHz)
        if (dryDelayL.size != targetDelay) {
            dryDelayL = DoubleArray(targetDelay)
            dryDelayR = DoubleArray(targetDelay)
        } else {
            dryDelayL.fill(0.0)
            dryDelayR.fill(0.0)
        }
        delaySamples = targetDelay
        dryDelayIndex = 0
        lastPublishNs = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val chCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding
        if (chCount <= 2 || !isActivelyDownmixing) return

        val bytesPerSample = bytesPerSample(encoding)
        if (bytesPerSample <= 0) return

        val inFrameBytes = bytesPerSample * chCount
        val remaining = inputBuffer.remaining()
        val frameCount = remaining / inFrameBytes
        if (frameCount == 0) return

        val outFrameBytes = bytesPerSample * 2
        val output = replaceOutputBuffer(frameCount * outFrameBytes)
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        val curLocalGen = localClipResetGen.get()
        val curMonitorGen = if (isPrimaryChain) MultichannelDownmixMonitor.clipResetGeneration() else seenMonitorClipResetGen
        if (curLocalGen != seenLocalClipResetGen || curMonitorGen != seenMonitorClipResetGen) {
            totalClippedSamples = 0L
            meterClippingL = false
            meterClippingR = false
            seenLocalClipResetGen = curLocalGen
            seenMonitorClipResetGen = curMonitorGen
        }

        val current = config
        if (current.lfeFilterMode != appliedLfeFilterMode) {
            resetFilterStates(inputAudioFormat.sampleRate)
            appliedLfeFilterMode = current.lfeFilterMode
        }

        val useLr4AllPass = appliedLfeFilterMode == DownmixLfeFilterMode.LR4_ALLPASS
        var activePreampDb = current.preampDb.toDouble()
        var preampLinear = 10.0.pow(activePreampDb / 20.0)
        var autoAdjustedPreampDb: Float? = null

        val channels = activeChannels
        for (ch in 0 until chCount) {
            val chDef = channels[ch]
            slotAudible[ch] = current.isChannelAudible(chDef.id)
        }

        val measureMeters = isPrimaryChain && MultichannelDownmixMonitor.hasObservers()
        val delayLen = delaySamples
        var dIdx = dryDelayIndex
        val dBufL = dryDelayL
        val dBufR = dryDelayR
        val lfeSec1 = if (useLr4AllPass) lr4Coeffs else bwCoeffs1
        val lfeSec2 = if (useLr4AllPass) lr4Coeffs else bwCoeffs2
        val apCoeffs = ap2Coeffs

        for (frame in 0 until frameCount) {
            var drySumL = 0.0
            var drySumR = 0.0
            var lfeOutL = 0.0
            var lfeOutR = 0.0

            for (ch in 0 until chCount) {
                val sample = readSampleNormalized(inputBuffer, encoding)
                val audible = slotAudible[ch]
                val gL = slotGainL[ch]
                val gR = slotGainR[ch]

                if (slotIsLfe[ch]) {
                    // Always advance LFE filter state so unmuting/mode operation stays click-free.
                    val mid = lfeState1.process(sample, lfeSec1)
                    val filtered = lfeState2.process(mid, lfeSec2)
                    val contribL = if (audible) filtered * gL else 0.0
                    val contribR = if (audible) filtered * gR else 0.0
                    lfeOutL += contribL
                    lfeOutR += contribR
                    if (measureMeters) {
                        val absIn = abs(sample)
                        if (absIn > meterInPeak[ch]) meterInPeak[ch] = absIn
                        meterInSumSq[ch] += sample * sample
                        val absCL = abs(contribL)
                        val absCR = abs(contribR)
                        if (absCL > meterContribPeakL[ch]) meterContribPeakL[ch] = absCL
                        if (absCR > meterContribPeakR[ch]) meterContribPeakR[ch] = absCR
                    }
                } else {
                    val contribL = if (audible) sample * gL else 0.0
                    val contribR = if (audible) sample * gR else 0.0
                    drySumL += contribL
                    drySumR += contribR
                    if (measureMeters) {
                        val absIn = abs(sample)
                        if (absIn > meterInPeak[ch]) meterInPeak[ch] = absIn
                        meterInSumSq[ch] += sample * sample
                        val absCL = abs(contribL)
                        val absCR = abs(contribR)
                        if (absCL > meterContribPeakL[ch]) meterContribPeakL[ch] = absCL
                        if (absCR > meterContribPeakR[ch]) meterContribPeakR[ch] = absCR
                    }
                }
            }

            val alignedDryL: Double
            val alignedDryR: Double
            if (useLr4AllPass) {
                // Phase-match non-LFE channels with 2nd-order All-Pass (zero pre-ringing, 0 dB flat amplitude).
                alignedDryL = apStateL.process(drySumL, apCoeffs)
                alignedDryR = apStateR.process(drySumR, apCoeffs)
            } else {
                // Delay non-LFE dry bus by 172 samples (3.58333 ms @ 48 kHz) to align with 125 Hz Butterworth LPF.
                alignedDryL = dBufL[dIdx]
                alignedDryR = dBufR[dIdx]
                dBufL[dIdx] = drySumL
                dBufR[dIdx] = drySumR
                dIdx = if (dIdx + 1 >= delayLen) 0 else dIdx + 1
            }

            val summedL = alignedDryL + lfeOutL
            val summedR = alignedDryR + lfeOutR

            if (current.autoHeadroom) {
                val peakPre = maxOf(abs(summedL), abs(summedR))
                if (peakPre * preampLinear > AUTO_HEADROOM_CEILING && activePreampDb > DownmixConfig.MIN_PREAMP_DB) {
                    val rawTargetDb = -20.0 * log10(peakPre) - AUTO_HEADROOM_SAFETY_MARGIN_DB
                    val snappedDownDb = floor(rawTargetDb * 2.0) / 2.0
                    val maxAllowedDb = maxOf(DownmixConfig.MIN_PREAMP_DB.toDouble(), activePreampDb - 0.5)
                    val targetDb = snappedDownDb.coerceIn(DownmixConfig.MIN_PREAMP_DB.toDouble(), maxAllowedDb)
                    activePreampDb = targetDb
                    preampLinear = 10.0.pow(activePreampDb / 20.0)
                    autoAdjustedPreampDb = activePreampDb.toFloat()
                }
            }

            val finalL = summedL * preampLinear
            val finalR = summedR * preampLinear

            if (abs(finalL) >= CLIP_THRESHOLD) {
                totalClippedSamples++
                meterClippingL = true
            }
            if (abs(finalR) >= CLIP_THRESHOLD) {
                totalClippedSamples++
                meterClippingR = true
            }

            if (measureMeters) {
                val absPreL = abs(summedL)
                val absPreR = abs(summedR)
                if (absPreL > meterPreSumPeakL) meterPreSumPeakL = absPreL
                if (absPreR > meterPreSumPeakR) meterPreSumPeakR = absPreR
                meterPreSumSumSqL += summedL * summedL
                meterPreSumSumSqR += summedR * summedR

                val absOutL = abs(finalL)
                val absOutR = abs(finalR)
                if (absOutL > meterOutPeakL) meterOutPeakL = absOutL
                if (absOutR > meterOutPeakR) meterOutPeakR = absOutR
                meterOutSumSqL += finalL * finalL
                meterOutSumSqR += finalR * finalR
                meterFrames++
            }

            writeSampleNormalized(output, encoding, finalL)
            writeSampleNormalized(output, encoding, finalR)
        }

        dryDelayIndex = dIdx
        output.flip()

        if (autoAdjustedPreampDb != null) {
            config = current.copy(preampDb = autoAdjustedPreampDb)
            onAutoHeadroomAdjusted?.invoke(autoAdjustedPreampDb)
        }

        if (measureMeters && meterFrames > 0L) {
            val nowNs = System.nanoTime()
            if (nowNs - lastPublishNs >= METER_PUBLISH_INTERVAL_NS) {
                lastPublishNs = nowNs
                publishLiveMeters(current, activePreampDb.toFloat())
                resetMeterAccumulators()
            }
        }
    }

    private fun publishLiveMeters(current: DownmixConfig, effectivePreampDb: Float) {
        val chCount = inputAudioFormat.channelCount
        val resolved = activeChannels
        val invFrames = 1.0 / meterFrames.coerceAtLeast(1L).toDouble()

        val activeMeters = ArrayList<MultichannelDownmixMonitor.ChannelMeter>(
            maxOf(chCount, DownmixChannel.LAYOUT_7_1_4.size),
        )
        val seenIds = HashSet<String>(chCount * 2)
        for (ch in 0 until chCount) {
            val def = resolved[ch]
            seenIds.add(def.id)
            activeMeters.add(
                MultichannelDownmixMonitor.ChannelMeter(
                    channel = def,
                    inputPeakDb = linearToDb(meterInPeak[ch]),
                    inputRmsDb = linearToDb(sqrt(meterInSumSq[ch] * invFrames)),
                    contributedLeftPeakDb = linearToDb(meterContribPeakL[ch]),
                    contributedRightPeakDb = linearToDb(meterContribPeakR[ch]),
                    audible = current.isChannelAudible(def.id),
                    activeInStream = true,
                ),
            )
        }
        // Keep remaining 7.1.4 slots visible (dimmed) when playing e.g. 5.1 or 7.1 streams.
        for (def in DownmixChannel.LAYOUT_7_1_4) {
            if (def.id !in seenIds) {
                activeMeters.add(
                    MultichannelDownmixMonitor.ChannelMeter(
                        channel = def,
                        audible = current.isChannelAudible(def.id),
                        activeInStream = false,
                    ),
                )
            }
        }

        publishSeq++
        MultichannelDownmixMonitor.publish(
            MultichannelDownmixMonitor.Snapshot(
                isActive = true,
                isDownmixing = true,
                bypassedByBitPerfect = false,
                inputChannelCount = chCount,
                inputChannelMask = if (activeChannelMask == Format.NO_VALUE) 0 else activeChannelMask,
                sampleRateHz = inputAudioFormat.sampleRate,
                dryDelaySamples = delaySamples,
                lfeFilterMode = appliedLfeFilterMode,
                layoutName = DownmixChannel.layoutLabel(chCount),
                channels = activeMeters,
                summedLeftPrePreampDb = linearToDb(meterPreSumPeakL),
                summedRightPrePreampDb = linearToDb(meterPreSumPeakR),
                summedLeftPrePreampRmsDb = linearToDb(sqrt(meterPreSumSumSqL * invFrames)),
                summedRightPrePreampRmsDb = linearToDb(sqrt(meterPreSumSumSqR * invFrames)),
                outputLeftPeakDb = linearToDb(meterOutPeakL),
                outputRightPeakDb = linearToDb(meterOutPeakR),
                outputLeftRmsDb = linearToDb(sqrt(meterOutSumSqL * invFrames)),
                outputRightRmsDb = linearToDb(sqrt(meterOutSumSqR * invFrames)),
                clippingLeft = meterClippingL,
                clippingRight = meterClippingR,
                clippedSamples = totalClippedSamples,
                preampDb = effectivePreampDb,
                publishSequence = publishSeq,
            ),
        )
    }

    private fun buildMeterList(
        resolved: List<DownmixChannel>,
        current: DownmixConfig,
        active: Boolean,
    ): List<MultichannelDownmixMonitor.ChannelMeter> {
        val list = ArrayList<MultichannelDownmixMonitor.ChannelMeter>(
            maxOf(resolved.size, DownmixChannel.LAYOUT_7_1_4.size),
        )
        val seen = HashSet<String>()
        if (active) {
            for (def in resolved) {
                seen.add(def.id)
                list.add(
                    MultichannelDownmixMonitor.ChannelMeter(
                        channel = def,
                        audible = current.isChannelAudible(def.id),
                        activeInStream = true,
                    ),
                )
            }
        }
        for (def in DownmixChannel.LAYOUT_7_1_4) {
            if (def.id !in seen) {
                list.add(
                    MultichannelDownmixMonitor.ChannelMeter(
                        channel = def,
                        audible = current.isChannelAudible(def.id),
                        activeInStream = false,
                    ),
                )
            }
        }
        return list
    }

    private fun resetMeterAccumulators() {
        meterInPeak.fill(0.0)
        meterInSumSq.fill(0.0)
        meterContribPeakL.fill(0.0)
        meterContribPeakR.fill(0.0)
        meterPreSumPeakL = 0.0
        meterPreSumPeakR = 0.0
        meterPreSumSumSqL = 0.0
        meterPreSumSumSqR = 0.0
        meterOutPeakL = 0.0
        meterOutPeakR = 0.0
        meterOutSumSqL = 0.0
        meterOutSumSqR = 0.0
        meterClippingL = false
        meterClippingR = false
        meterFrames = 0L
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun readSampleNormalized(buf: ByteBuffer, encoding: Int): Double = when (encoding) {
        C.ENCODING_PCM_16BIT -> buf.short.toDouble() / 32768.0
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() // sign-extended
            ((b2 shl 16) or (b1 shl 8) or b0).toDouble() / 8388608.0
        }
        C.ENCODING_PCM_32BIT -> buf.int.toDouble() / 2147483648.0
        C.ENCODING_PCM_FLOAT -> {
            val f = buf.float
            if (f.isFinite()) f.toDouble() else 0.0
        }
        else -> 0.0
    }

    private fun writeSampleNormalized(buf: ByteBuffer, encoding: Int, sample: Double) {
        val safe = if (sample.isFinite()) sample else 0.0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val scaled = (safe * 32768.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buf.putShort(scaled.toShort())
            }
            C.ENCODING_PCM_24BIT -> {
                val scaled = (safe * 8388608.0).roundToInt().coerceIn(-8388608, 8388607)
                buf.put((scaled and 0xFF).toByte())
                buf.put(((scaled shr 8) and 0xFF).toByte())
                buf.put(((scaled shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> {
                val scaled = (safe * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                buf.putInt(scaled.toInt())
            }
            C.ENCODING_PCM_FLOAT -> {
                buf.putFloat(safe.coerceIn(-1.0, 1.0).toFloat())
            }
        }
    }

    companion object {
        private const val TAG = "MultichannelDownmix"
        const val LFE_CUTOFF_HZ = 125.0
        const val DRY_DELAY_MS = 3.5833333333333335
        const val DEFAULT_DELAY_SAMPLES = 172
        private const val CLIP_THRESHOLD = 0.9999
        private const val AUTO_HEADROOM_CEILING = 0.9885530946569389 // -0.1 dBFS
        private const val AUTO_HEADROOM_SAFETY_MARGIN_DB = 0.5
        private const val METER_PUBLISH_INTERVAL_NS = 16_000_000L

        // 4th-order Butterworth pole Q factors: 1 / (2 * cos((2k + 1) * pi / 8))
        private val BUTTERWORTH_4_Q1 = 1.0 / (2.0 * cos(PI / 8.0))
        private val BUTTERWORTH_4_Q2 = 1.0 / (2.0 * cos(3.0 * PI / 8.0))
        // 4th-order Linkwitz-Riley (LR4) & matching 2nd-order All-Pass Q factor
        private val LR4_Q = 1.0 / sqrt(2.0)

        fun computeDelaySamples(sampleRateHz: Int): Int = when {
            sampleRateHz <= 0 -> DEFAULT_DELAY_SAMPLES
            sampleRateHz == 48_000 -> DEFAULT_DELAY_SAMPLES
            else -> (sampleRateHz * DRY_DELAY_MS / 1000.0).roundToInt().coerceAtLeast(1)
        }

        private fun linearToDb(linear: Double): Float {
            if (!linear.isFinite() || linear <= 1e-6) return Float.NEGATIVE_INFINITY
            return (20.0 * log10(linear)).toFloat()
        }
    }
}
