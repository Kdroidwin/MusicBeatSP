package com.music.bitchord.ui.screens.equalizer.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import java.util.Locale
import kotlin.math.abs

/**
 * Ported from BitChord's EqualizerScreen BalanceControl. Keep the same centred
 * detent, L/R trim presentation, and haptic feedback while using MusicBeatSP's
 * existing parametric-PCM processor as the output path.
 */
@Composable
fun BalanceControl(
    balance: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val trackColor = MaterialTheme.colorScheme.outline
    val fillColor = MaterialTheme.colorScheme.primary
    val knobColor = MaterialTheme.colorScheme.background
    // The gesture block can outlive the composition pass that started it.
    val latest by rememberUpdatedState(balance)
    val currentOnChange by rememberUpdatedState(onChange)

    Column(modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.equalizer_balance_left),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = String.format(Locale.getDefault(), "%.2f", balance),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.equalizer_balance_right),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(BALANCE_KNOB_RADIUS * 2 + 8.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = latest
                        fun report(offset: Offset) {
                            val inset = BALANCE_KNOB_RADIUS.toPx()
                            val usable = (size.width - inset * 2).coerceAtLeast(1f)
                            val fraction = ((offset.x - inset) / usable).coerceIn(0f, 1f)
                            val raw = fraction * 2f - 1f
                            val snapped = if (abs(raw) < BALANCE_DETENT) 0f else raw
                            if (abs(snapped - last) > 0.004f) {
                                if (snapped == 0f && last != 0f) haptics.play(Haptic.Tick)
                                last = snapped
                                currentOnChange(snapped)
                            }
                        }
                        report(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                report(change.position)
                            }
                        }
                    }
                },
        ) {
            val inset = BALANCE_KNOB_RADIUS.toPx()
            val usable = size.width - inset * 2
            val centreY = size.height / 2
            val centreX = inset + usable / 2
            val trackHeight = BALANCE_TRACK_WIDTH.toPx()
            val knobX = inset + usable * (balance + 1f) / 2f

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(inset, centreY - trackHeight / 2),
                size = Size(usable, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2),
            )
            if (abs(balance) > 0.004f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(minOf(centreX, knobX), centreY - trackHeight / 2),
                    size = Size(abs(centreX - knobX), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2),
                )
            }
            drawCircle(
                color = Color.Black.copy(alpha = 0.18f),
                radius = inset + 1.5f,
                center = Offset(knobX, centreY + 1.5f),
            )
            drawCircle(color = knobColor, radius = inset, center = Offset(knobX, centreY))
        }
    }
}

private val BALANCE_TRACK_WIDTH = 5.dp
private val BALANCE_KNOB_RADIUS = 10.dp
private const val BALANCE_DETENT = 0.04f
