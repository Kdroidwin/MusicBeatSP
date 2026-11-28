package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.DownmixChannel
import com.music.bitchord.playback.DownmixConfig
import com.music.bitchord.playback.DownmixLfeFilterMode
import com.music.bitchord.playback.MultichannelDownmixMonitor
import com.music.bitchord.ui.components.TopBarContentHeight
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

private const val METER_MIN_DB = -60f
private const val METER_MAX_DB = 6f
private const val METER_FLOOR_DB = -90f
private const val METER_STALE_TIMEOUT_MS = 600f
private const val METER_PEAK_FALL_DB_PER_SEC = 28f
private const val METER_HOLD_DURATION_MS = 1500f
private const val METER_HOLD_FALL_DB_PER_SEC = 18f
private const val TEXT_REFRESH_INTERVAL_NS = 66_000_000L

@Composable
internal fun DownmixSettingsRow(onOpen: () -> Unit) {
    val enabled by AppSettings.multichannelDownmixEnabled.collectAsStateWithLifecycle()
    val preampDb by AppSettings.multichannelDownmixPreampDb.collectAsStateWithLifecycle()
    val valueText = if (enabled) formatSignedDb(preampDb) else stringResource(R.string.off)
    SettingsRow(
        icon = Icons.Rounded.SurroundSound,
        title = stringResource(R.string.downmix_settings_title),
        subtitle = stringResource(R.string.downmix_settings_subtitle),
        value = valueText,
        onClick = onOpen,
    )
}

@Composable
fun DownmixSettingsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val close: () -> Unit = { visibleState.targetState = false }
    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle && !visibleState.currentState && !visibleState.targetState) onDismiss()
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInVertically(tween(320)) { it / 6 } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(240)) { it / 6 } + fadeOut(tween(200)),
        ) {
            Surface(
                modifier = modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                BackHandler(onBack = close)
                DownmixSettingsPage(onClose = close)
            }
        }
    }
}

@Stable
private class SmoothedMeterLevel {
    var peakDb: Float = METER_FLOOR_DB
    var rmsDb: Float = METER_FLOOR_DB
    var holdDb: Float = METER_FLOOR_DB
    var holdAgeMs: Float = 0f
    var contribPeakDb: Float = METER_FLOOR_DB

    fun step(targetPeakRaw: Float, targetRmsRaw: Float, targetContribRaw: Float, dtSec: Float, dtMs: Float): Boolean {
        val targetPeak = normalizeDb(targetPeakRaw)
        val targetRms = normalizeDb(targetRmsRaw)
        val targetContrib = normalizeDb(targetContribRaw)

        val nextPeak = max(targetPeak, peakDb - METER_PEAK_FALL_DB_PER_SEC * dtSec)
            .coerceAtLeast(METER_FLOOR_DB)
        val nextContrib = max(targetContrib, contribPeakDb - METER_PEAK_FALL_DB_PER_SEC * dtSec)
            .coerceAtLeast(METER_FLOOR_DB)
        val rmsTau = if (targetRms >= rmsDb) 0.035f else 0.140f
        val rmsAlpha = 1f - exp(-dtSec / rmsTau)
        val rawRms = rmsDb + (targetRms - rmsDb) * rmsAlpha
        val nextRms = if (rawRms < METER_FLOOR_DB + 0.05f && targetRms <= METER_FLOOR_DB) {
            METER_FLOOR_DB
        } else {
            rawRms.coerceAtLeast(METER_FLOOR_DB)
        }

        if (targetPeak >= holdDb || holdAgeMs >= METER_HOLD_DURATION_MS) {
            if (targetPeak >= holdDb) {
                holdDb = targetPeak
                holdAgeMs = 0f
            } else {
                holdDb = max(nextPeak, holdDb - METER_HOLD_FALL_DB_PER_SEC * dtSec)
                    .coerceAtLeast(METER_FLOOR_DB)
            }
        } else {
            holdAgeMs += dtMs
            holdDb = max(holdDb, nextPeak)
        }

        val moved = nextPeak != peakDb || nextRms != rmsDb || holdDb != nextPeak || nextContrib != contribPeakDb
        peakDb = nextPeak
        rmsDb = nextRms
        contribPeakDb = nextContrib
        return moved || nextPeak > METER_FLOOR_DB || nextRms > METER_FLOOR_DB || holdDb > METER_FLOOR_DB || nextContrib > METER_FLOOR_DB
    }

    private fun normalizeDb(db: Float): Float =
        if (!db.isFinite() || db <= METER_FLOOR_DB) METER_FLOOR_DB else db
}

@Stable
private class DownmixMeterEngine {
    var meterFrameTick by mutableLongStateOf(0L)
    var textTick by mutableLongStateOf(0L)

    var isActive by mutableStateOf(false)
    var isDownmixing by mutableStateOf(false)
    var sampleRateHz by mutableIntStateOf(0)
    var dryDelaySamples by mutableIntStateOf(172)
    var layoutName by mutableStateOf("—")
    var clippedSamples by mutableLongStateOf(0L)
    var channelSlots by mutableStateOf(
        MultichannelDownmixMonitor.defaultMeters().map { it.channel to it.activeInStream },
    )

    val preSumL = SmoothedMeterLevel()
    val preSumR = SmoothedMeterLevel()
    val outL = SmoothedMeterLevel()
    val outR = SmoothedMeterLevel()
    private val channels = HashMap<String, SmoothedMeterLevel>(16)

    fun levelFor(channelId: String): SmoothedMeterLevel =
        channels.getOrPut(channelId) { SmoothedMeterLevel() }

    suspend fun runLoop() {
        var prevNanos = 0L
        var lastSeq = -1L
        var staleMs = METER_STALE_TIMEOUT_MS
        var lastTextUpdateNs = 0L

        while (true) {
            withFrameNanos { nowNanos ->
                val dtSec = if (prevNanos == 0L) {
                    1f / 60f
                } else {
                    ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                }
                val dtMs = dtSec * 1000f
                prevNanos = nowNanos

                val snap = MultichannelDownmixMonitor.snapshot.value
                val newFrame = snap.publishSequence != lastSeq
                if (newFrame) {
                    lastSeq = snap.publishSequence
                    staleMs = 0f
                } else {
                    staleMs += dtMs
                }

                if (isActive != snap.isActive) isActive = snap.isActive
                if (isDownmixing != snap.isDownmixing) isDownmixing = snap.isDownmixing
                if (sampleRateHz != snap.sampleRateHz) sampleRateHz = snap.sampleRateHz
                if (dryDelaySamples != snap.dryDelaySamples) dryDelaySamples = snap.dryDelaySamples
                if (layoutName != snap.layoutName) layoutName = snap.layoutName
                if (clippedSamples != snap.clippedSamples) clippedSamples = snap.clippedSamples

                if (snap.channels.isNotEmpty() && slotsDiffer(snap.channels)) {
                    channelSlots = snap.channels.map { it.channel to it.activeInStream }
                }

                val feedLive = snap.isDownmixing && staleMs <= METER_STALE_TIMEOUT_MS
                var anyAnimating = newFrame
                if (preSumL.step(
                        if (feedLive) snap.summedLeftPrePreampDb else METER_FLOOR_DB,
                        if (feedLive) snap.summedLeftPrePreampRmsDb else METER_FLOOR_DB,
                        METER_FLOOR_DB,
                        dtSec,
                        dtMs,
                    )
                ) {
                    anyAnimating = true
                }
                if (preSumR.step(
                        if (feedLive) snap.summedRightPrePreampDb else METER_FLOOR_DB,
                        if (feedLive) snap.summedRightPrePreampRmsDb else METER_FLOOR_DB,
                        METER_FLOOR_DB,
                        dtSec,
                        dtMs,
                    )
                ) {
                    anyAnimating = true
                }
                if (outL.step(
                        if (feedLive) snap.outputLeftPeakDb else METER_FLOOR_DB,
                        if (feedLive) snap.outputLeftRmsDb else METER_FLOOR_DB,
                        METER_FLOOR_DB,
                        dtSec,
                        dtMs,
                    )
                ) {
                    anyAnimating = true
                }
                if (outR.step(
                        if (feedLive) snap.outputRightPeakDb else METER_FLOOR_DB,
                        if (feedLive) snap.outputRightRmsDb else METER_FLOOR_DB,
                        METER_FLOOR_DB,
                        dtSec,
                        dtMs,
                    )
                ) {
                    anyAnimating = true
                }

                for (meter in snap.channels) {
                    val slot = levelFor(meter.channel.id)
                    val targetInPeak = if (feedLive && meter.activeInStream) meter.inputPeakDb else METER_FLOOR_DB
                    val targetInRms = if (feedLive && meter.activeInStream) meter.inputRmsDb else METER_FLOOR_DB
                    val targetContrib = if (feedLive && meter.activeInStream) {
                        max(meter.contributedLeftPeakDb, meter.contributedRightPeakDb)
                    } else {
                        METER_FLOOR_DB
                    }
                    if (slot.step(targetInPeak, targetInRms, targetContrib, dtSec, dtMs)) {
                        anyAnimating = true
                    }
                }

                if (anyAnimating) {
                    meterFrameTick++
                    if (nowNanos - lastTextUpdateNs >= TEXT_REFRESH_INTERVAL_NS) {
                        lastTextUpdateNs = nowNanos
                        textTick++
                    }
                }
            }
        }
    }

    private fun slotsDiffer(incoming: List<MultichannelDownmixMonitor.ChannelMeter>): Boolean {
        val current = channelSlots
        if (current.size != incoming.size) return true
        for (i in incoming.indices) {
            if (current[i].first != incoming[i].channel || current[i].second != incoming[i].activeInStream) {
                return true
            }
        }
        return false
    }
}

@Composable
private fun DownmixSettingsPage(
    onClose: () -> Unit,
) {
    val engine = remember { DownmixMeterEngine() }
    DisposableEffect(Unit) {
        MultichannelDownmixMonitor.onUiAttached()
        onDispose { MultichannelDownmixMonitor.onUiDetached() }
    }
    LaunchedEffect(engine) {
        engine.runLoop()
    }

    val enabled by AppSettings.multichannelDownmixEnabled.collectAsStateWithLifecycle()
    val preampDb by AppSettings.multichannelDownmixPreampDb.collectAsStateWithLifecycle()
    val mutedChannels by AppSettings.multichannelDownmixMutedChannels.collectAsStateWithLifecycle()
    val soloChannels by AppSettings.multichannelDownmixSoloChannels.collectAsStateWithLifecycle()
    val lfeFilterMode by AppSettings.multichannelDownmixLfeFilterMode.collectAsStateWithLifecycle()
    val autoHeadroom by AppSettings.multichannelDownmixAutoHeadroom.collectAsStateWithLifecycle()
    val bitPerfect by AppSettings.bitPerfectPlayback.collectAsStateWithLifecycle()

    val pageColor = MaterialTheme.colorScheme.surface
    val scrollState = rememberScrollState()
    val titleAwayPx = with(LocalDensity.current) { 56.dp.toPx() }
    val titleAway by remember(titleAwayPx) {
        derivedStateOf { scrollState.value > titleAwayPx }
    }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val pageTitle = stringResource(R.string.downmix_settings_title)

    val statusText = when {
        bitPerfect -> stringResource(R.string.downmix_bit_perfect_bypass)
        engine.isDownmixing -> stringResource(
            R.string.downmix_status_active,
            engine.layoutName,
            engine.sampleRateHz / 1000.0,
        )
        engine.isActive && !engine.isDownmixing && engine.sampleRateHz > 0 -> stringResource(
            R.string.downmix_status_stereo,
            engine.layoutName,
        )
        else -> stringResource(R.string.downmix_status_inactive)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pageColor)
                .verticalScroll(scrollState)
                .padding(top = topInset + TopBarContentHeight),
        ) {
                Column(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (engine.isDownmixing && !bitPerfect) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // 1. Master switch & DSP summary
                EqGroup {
                    row {
                        EqSwitchRow(
                            title = stringResource(R.string.downmix_enable_title),
                            subtitle = stringResource(R.string.downmix_enable_subtitle),
                            checked = enabled,
                            onCheckedChange = AppSettings::setMultichannelDownmixEnabled,
                            icon = Icons.Rounded.SurroundSound,
                            enabled = !bitPerfect,
                        )
                    }
                    row {
                        EqRowContent {
                            Text(
                                text = when (lfeFilterMode) {
                                    DownmixLfeFilterMode.LR4_ALLPASS -> stringResource(R.string.downmix_dsp_spec_lr4)
                                    DownmixLfeFilterMode.BUTTERWORTH_4_DELAY -> stringResource(
                                        R.string.downmix_dsp_spec_bw,
                                        engine.dryDelaySamples,
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 2. LFE filter & phase alignment selector
                EqGroup(title = stringResource(R.string.downmix_lfe_filter_section)) {
                    row {
                        LfeFilterModeRow(
                            title = stringResource(R.string.downmix_lfe_mode_bw4_title),
                            subtitle = stringResource(R.string.downmix_lfe_mode_bw4_subtitle),
                            selected = lfeFilterMode == DownmixLfeFilterMode.BUTTERWORTH_4_DELAY,
                            onClick = { AppSettings.setMultichannelDownmixLfeFilterMode(DownmixLfeFilterMode.BUTTERWORTH_4_DELAY) },
                        )
                    }
                    row {
                        LfeFilterModeRow(
                            title = stringResource(R.string.downmix_lfe_mode_lr4_title),
                            subtitle = stringResource(R.string.downmix_lfe_mode_lr4_subtitle),
                            selected = lfeFilterMode == DownmixLfeFilterMode.LR4_ALLPASS,
                            onClick = { AppSettings.setMultichannelDownmixLfeFilterMode(DownmixLfeFilterMode.LR4_ALLPASS) },
                        )
                    }
                }

                // 3. Final summed gain, clip counter, preamp & auto-headroom
                EqGroup(title = stringResource(R.string.downmix_output_section)) {
                    row {
                        FinalOutputMeterBlock(
                            engine = engine,
                            onResetClips = MultichannelDownmixMonitor::requestClipReset,
                        )
                    }
                    row {
                        PreampSliderRow(
                            preampDb = preampDb,
                            onPreampChange = AppSettings::setMultichannelDownmixPreampDb,
                        )
                    }
                    row {
                        EqSwitchRow(
                            title = stringResource(R.string.downmix_auto_headroom_title),
                            subtitle = stringResource(R.string.downmix_auto_headroom_subtitle),
                            checked = autoHeadroom,
                            onCheckedChange = AppSettings::setMultichannelDownmixAutoHeadroom,
                            icon = Icons.Rounded.AutoFixHigh,
                            enabled = !bitPerfect && enabled,
                        )
                    }
                }

                // 4. Per-channel real-time input/contributed gain + Mute / Solo controls
                EqGroup(title = stringResource(R.string.downmix_channels_section)) {
                    if (mutedChannels.isNotEmpty() || soloChannels.isNotEmpty()) {
                        row {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = buildString {
                                        if (soloChannels.isNotEmpty()) {
                                            append("SOLO: ${soloChannels.joinToString(", ")}")
                                        }
                                        if (mutedChannels.isNotEmpty()) {
                                            if (isNotEmpty()) append("  ·  ")
                                            append("MUTE: ${mutedChannels.joinToString(", ")}")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = AppSettings::clearMultichannelDownmixMuteSolo) {
                                    Text(stringResource(R.string.downmix_reset_ms))
                                }
                            }
                        }
                    }
                    val activeConfig = DownmixConfig(
                        enabled = enabled,
                        preampDb = preampDb,
                        mutedChannels = mutedChannels,
                        soloChannels = soloChannels,
                        lfeFilterMode = lfeFilterMode,
                        autoHeadroom = autoHeadroom,
                    )
                    engine.channelSlots.forEach { (ch, activeInStream) ->
                        val isMuted = ch.id in mutedChannels
                        val isSolo = ch.id in soloChannels
                        val audible = activeConfig.isChannelAudible(ch.id)
                        row {
                            ChannelMeterRow(
                                channel = ch,
                                activeInStream = activeInStream,
                                level = engine.levelFor(ch.id),
                                frameTick = { engine.meterFrameTick },
                                textTick = { engine.textTick },
                                isMuted = isMuted,
                                isSolo = isSolo,
                                effectiveAudible = audible,
                                onToggleMute = { AppSettings.toggleMultichannelDownmixMute(ch.id) },
                                onToggleSolo = { AppSettings.toggleMultichannelDownmixSolo(ch.id) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topInset + 12.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(pageColor.copy(alpha = 0.85f), pageColor.copy(alpha = 0f)),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .height(TopBarContentHeight),
        ) {
            DownmixTopBarButton(
                onClick = onClose,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
            )
            AnimatedVisibility(
                visible = titleAway,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(160)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 72.dp),
            ) {
                DownmixTitleCapsule(pageTitle)
            }
            DownmixTopBarButton(
                onClick = {
                    AppSettings.setMultichannelDownmixPreampDb(DownmixConfig.DEFAULT_PREAMP_DB)
                    AppSettings.clearMultichannelDownmixMuteSolo()
                    MultichannelDownmixMonitor.requestClipReset()
                },
                icon = Icons.Rounded.RestartAlt,
                contentDescription = stringResource(R.string.downmix_reset_ms),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun LfeFilterModeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinalOutputMeterBlock(
    engine: DownmixMeterEngine,
    onResetClips: () -> Unit,
) {
    val clipped = engine.clippedSamples > 0L
    EqRowContent {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.downmix_pre_sum_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (clipped) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    )
                    .clickable(onClick = onResetClips)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (clipped) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = if (clipped) {
                            stringResource(R.string.downmix_clips_count, engine.clippedSamples)
                        } else {
                            stringResource(R.string.downmix_no_clips)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (clipped) FontWeight.Bold else FontWeight.Medium,
                        color = if (clipped) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        StereoLevelRow(
            label = "L",
            level = engine.preSumL,
            frameTick = { engine.meterFrameTick },
            textTick = { engine.textTick },
            warnOnOverZero = false,
            accentColor = MaterialTheme.colorScheme.primary,
        )
        StereoLevelRow(
            label = "R",
            level = engine.preSumR,
            frameTick = { engine.meterFrameTick },
            textTick = { engine.textTick },
            warnOnOverZero = false,
            accentColor = MaterialTheme.colorScheme.primary,
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )

        Text(
            text = stringResource(R.string.downmix_post_out_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        StereoLevelRow(
            label = "L",
            level = engine.outL,
            frameTick = { engine.meterFrameTick },
            textTick = { engine.textTick },
            warnOnOverZero = true,
            accentColor = MaterialTheme.colorScheme.primary,
        )
        StereoLevelRow(
            label = "R",
            level = engine.outR,
            frameTick = { engine.meterFrameTick },
            textTick = { engine.textTick },
            warnOnOverZero = true,
            accentColor = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun StereoLevelRow(
    label: String,
    level: SmoothedMeterLevel,
    frameTick: () -> Long,
    textTick: () -> Long,
    warnOnOverZero: Boolean,
    accentColor: Color,
) {
    // Subscribe to ~15 Hz textTick for readable numeric readout without 60 Hz text flicker.
    val tick = textTick()
    val shownPeakDb = remember(tick, level) { level.peakDb }
    val isOverZero = warnOnOverZero && shownPeakDb > 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )
        DownmixLevelMeter(
            level = level,
            frameTick = frameTick,
            modifier = Modifier.weight(1f),
            height = 12.dp,
            warnOnOverZero = warnOnOverZero,
            accentColor = accentColor,
        )
        Text(
            text = formatDbValue(shownPeakDb),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            color = if (isOverZero) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.width(62.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * High-precision 60/120fps Canvas level meter inspired by Galaxy Downmixer:
 * translucent bar = smoothed peak, solid bar = RMS, tick = peak hold, thin line = 0 dBFS.
 */
@Composable
private fun DownmixLevelMeter(
    level: SmoothedMeterLevel,
    frameTick: () -> Long,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    dimmed: Boolean = false,
    warnOnOverZero: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val scheme = MaterialTheme.colorScheme
    val trackColor = scheme.surfaceContainerHighest
    val zeroMarkerColor = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    val warnColor = Color(0xFFF59E0B)
    val errorColor = scheme.error
    val outlineColor = scheme.outline

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // Read VSync frameTick inside draw scope so only Canvas redraws at display refresh rate.
        frameTick()

        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        fun dbToX(db: Float): Float =
            ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f) * size.width

        val barColor = when {
            dimmed -> outlineColor
            warnOnOverZero && level.holdDb > 0f -> errorColor
            level.peakDb > -6f -> warnColor
            else -> accentColor
        }

        drawRoundRect(color = trackColor, cornerRadius = radius)

        val peakX = dbToX(level.peakDb)
        if (peakX > 0f) {
            drawRoundRect(
                color = barColor.copy(alpha = 0.36f),
                size = Size(peakX, size.height),
                cornerRadius = radius,
            )
        }

        val rmsX = dbToX(level.rmsDb)
        if (rmsX > 0f) {
            drawRoundRect(
                color = barColor,
                size = Size(rmsX, size.height),
                cornerRadius = radius,
            )
        }

        if (level.holdDb > METER_MIN_DB) {
            val tickWidth = 2.5.dp.toPx()
            val holdX = dbToX(level.holdDb).coerceIn(0f, size.width - tickWidth)
            drawRect(
                color = barColor,
                topLeft = Offset(holdX, 0f),
                size = Size(tickWidth, size.height),
            )
        }

        val zeroX = dbToX(0f)
        drawLine(
            color = zeroMarkerColor,
            start = Offset(zeroX, 0f),
            end = Offset(zeroX, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@Composable
private fun PreampSliderRow(
    preampDb: Float,
    onPreampChange: (Float) -> Unit,
) {
    EqRowContent {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.downmix_preamp_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.downmix_preamp_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatSignedDb(preampDb),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
        }
        Slider(
            value = preampDb,
            onValueChange = { raw ->
                val snapped = (raw * 2f).roundToInt() / 2f
                onPreampChange(snapped)
            },
            valueRange = DownmixConfig.MIN_PREAMP_DB..DownmixConfig.MAX_PREAMP_DB,
            steps = ((DownmixConfig.MAX_PREAMP_DB - DownmixConfig.MIN_PREAMP_DB) * 2f).roundToInt() - 1,
            colors = eqSliderColors(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(-12f, -6f, -3f, 0f).forEach { preset ->
                val selected = kotlin.math.abs(preampDb - preset) < 0.1f
                val label = if (preset == 0f) "0 dB" else String.format(Locale.US, "%+.0f dB", preset)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onPreampChange(preset) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelMeterRow(
    channel: DownmixChannel,
    activeInStream: Boolean,
    level: SmoothedMeterLevel,
    frameTick: () -> Long,
    textTick: () -> Long,
    isMuted: Boolean,
    isSolo: Boolean,
    effectiveAudible: Boolean,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
) {
    val matrixDesc = remember(channel) {
        when {
            channel.isLfe -> String.format(Locale.US, "L/R ×%.2f · 125 Hz LPF", channel.leftGain)
            channel.leftGain > 0.0 && channel.rightGain > 0.0 ->
                String.format(Locale.US, "L/R ×%.3f", channel.leftGain)
            channel.leftGain > 0.0 -> String.format(Locale.US, "L ×%.2f", channel.leftGain)
            else -> String.format(Locale.US, "R ×%.2f", channel.rightGain)
        }
    }
    val tick = textTick()
    val shownInputPeakDb = remember(tick, level) { level.peakDb }
    val shownContribPeakDb = remember(tick, level) { level.contribPeakDb }
    val rowAlpha = if (!activeInStream) 0.45f else if (!effectiveAudible) 0.55f else 1f
    val accent = if (channel.isLfe) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(rowAlpha),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = channel.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (channel.isLfe) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(38.dp),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = if (activeInStream) matrixDesc else stringResource(R.string.downmix_inactive_channel),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChannelToggleChip(
                    text = "M",
                    active = isMuted,
                    activeContainer = MaterialTheme.colorScheme.errorContainer,
                    activeContent = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onToggleMute,
                )
                ChannelToggleChip(
                    text = "S",
                    active = isSolo,
                    activeContainer = MaterialTheme.colorScheme.primary,
                    activeContent = MaterialTheme.colorScheme.onPrimary,
                    onClick = onToggleSolo,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownmixLevelMeter(
                level = level,
                frameTick = frameTick,
                modifier = Modifier.weight(1f),
                height = 10.dp,
                dimmed = !effectiveAudible || !activeInStream,
                warnOnOverZero = false,
                accentColor = accent,
            )
            Text(
                text = formatDbValue(shownInputPeakDb),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(62.dp),
                maxLines = 1,
                softWrap = false,
            )
        }

        if (activeInStream) {
            Text(
                text = stringResource(
                    R.string.downmix_channel_in_contrib,
                    formatDbValue(shownInputPeakDb),
                    formatDbValue(shownContribPeakDb),
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChannelToggleChip(
    text: String,
    active: Boolean,
    activeContainer: Color,
    activeContent: Color,
    onClick: () -> Unit,
) {
    val bg = if (active) activeContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (active) activeContent else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = 1.dp,
                color = if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
        )
    }
}

private class EqGroupScope {
    @Composable
    fun row(content: @Composable () -> Unit) {
        content()
    }
}

@Composable
private fun EqGroup(
    title: String? = null,
    content: @Composable EqGroupScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            EqGroupScope().content()
        }
    }
}

@Composable
private fun EqRowContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun EqSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun eqSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
)

@Composable
private fun DownmixTopBarButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp).clip(CircleShape).background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DownmixTitleCapsule(title: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private fun formatDbValue(db: Float): String {
    if (!db.isFinite() || db <= METER_FLOOR_DB) return "-∞ dB"
    return String.format(Locale.US, "%+.1f dB", db)
}

private fun formatSignedDb(db: Float): String =
    String.format(Locale.US, "%+.1f dB", db)
