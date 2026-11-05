package com.music.bitchord.ui.components

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.GridKind
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tanh

/** True inside a grid of 4+ columns: cards drop to a smaller title and no subtitle. */
val LocalGridCompact = compositionLocalOf { false }

/**
 * A grid's spacing for its column count: the gaps between cards, the card's
 * own inset and its corners. Fixed 2-column spacing left a 5-column grid with
 * gaps nearly half the width of its art, so the denser the grid, the tighter.
 */
@androidx.compose.runtime.Immutable
data class GridSpacing(
    val horizontal: Dp,
    val vertical: Dp,
    val cardPadding: Dp,
    val corner: Dp,
)

fun gridSpacingFor(columns: Int): GridSpacing = when {
    columns <= 2 -> GridSpacing(12.dp, 16.dp, 4.dp, 16.dp)
    columns == 3 -> GridSpacing(10.dp, 12.dp, 3.dp, 14.dp)
    columns == 4 -> GridSpacing(8.dp, 10.dp, 2.dp, 12.dp)
    else -> GridSpacing(6.dp, 8.dp, 2.dp, 10.dp)
}

/**
 * [gridSpacingFor], with the rows as close as the columns when titles sit on
 * the artwork: the tiles are then plain squares, and even gaps make the grid.
 */
@Composable
fun rememberGridSpacing(columns: Int): GridSpacing {
    val overlay by AppSettings.gridTitleOverlay.collectAsStateWithLifecycle()
    val base = gridSpacingFor(columns)
    return if (overlay) base.copy(vertical = base.horizontal) else base
}

/** The spacing of the grid a card is in; set by the grid, read by its cards. */
val LocalGridSpacing = compositionLocalOf { gridSpacingFor(2) }

/** Narrower than this, a grid card is "compact" (about 4+ columns on a phone). */
private val COMPACT_CARD_WIDTH = 100.dp

/** Narrower than this (5 columns on a phone), titles on the artwork are left out. */
val OVERLAY_TITLE_MIN_WIDTH = 76.dp

/** Whether a grid card is narrow enough to go compact, its width, and the modifier that measures it. */
class CardCompact(val compact: Boolean, val width: Dp, val measure: Modifier)

/**
 * Lets a grid card shrink its text when the grid gets dense: a smaller title
 * and no subtitle once the card is under [COMPACT_CARD_WIDTH] wide. Put
 * [CardCompact.measure] on the card's root.
 */
@Composable
fun rememberCardCompact(): CardCompact {
    val density = LocalDensity.current
    var width by remember { mutableStateOf(200.dp) }
    val measure = remember(density) {
        Modifier.onSizeChanged { width = with(density) { it.width.toDp() } }
    }
    return CardCompact(width < COMPACT_CARD_WIDTH, width, measure)
}

/** The user's column count for [kind] (pinch or Settings), or its default. */
@Composable
fun rememberGridColumns(kind: GridKind): Int {
    val all by AppSettings.gridColumns.collectAsStateWithLifecycle()
    return all[kind.key] ?: kind.defaultColumns
}

/**
 * [columns] across a phone-width grid, and proportionally more on a wider
 * one (tablet, landscape), so an item keeps about the same size everywhere.
 */
class ScaledGridCells(private val columns: Int) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val reference = PHONE_WIDTH.toPx()
        val count = max(columns, (columns * availableSize / reference).roundToInt()).coerceAtLeast(1)
        val usable = (availableSize - spacing * (count - 1)).coerceAtLeast(0)
        val base = usable / count
        val remainder = usable % count
        return List(count) { base + if (it < remainder) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is ScaledGridCells && other.columns == columns
    override fun hashCode(): Int = columns

    private companion object {
        val PHONE_WIDTH = 400.dp
    }
}

/** Pinch ratio past which the grid steps one column. */
private const val STEP_IN = 1.22f
private const val STEP_OUT = 0.82f

/** M3 Expressive "fast spatial" spring: the pill, the tiles' ripple. */
private const val FAST_DAMPING = 0.6f
private const val FAST_STIFFNESS = 800f

/** A little softer, for the whole grid following the fingers. */
private const val GRID_DAMPING = 0.55f
private const val GRID_STIFFNESS = 500f

/** Whether the system lets apps animate (off under "Remove animations"). */
internal fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

/**
 * One ripple across the grid's tiles: every tile carrying [gridPulse] dips to
 * [amplitude] and springs back, later the further it is from [pivot] (root
 * coordinates), [cellPx] being about one tile's width. Only one grid is pinched
 * at a time, so a single app-wide signal is enough.
 */
data class GridPulseEvent(val id: Int, val pivot: Offset, val amplitude: Float, val cellPx: Float, val at: Long = 0L)

object GridPulse {
    var event by mutableStateOf(GridPulseEvent(0, Offset.Zero, 1f, 1f, -10_000L))
        private set
    private var next = 1

    fun fire(pivot: Offset, amplitude: Float, cellPx: Float) {
        if (!animationsEnabled()) return
        event = GridPulseEvent(next++, pivot, amplitude, cellPx.coerceAtLeast(1f), android.os.SystemClock.uptimeMillis())
    }
}

/**
 * What a pinch (or a list/grid switch) is doing to the page right now, read by
 * the tiles and the page's header rather than applied to the grid as a whole:
 * so the album art, the title and the button row are never scaled, and no
 * dark film goes over them.
 *
 * - [scale] around [pivot] (root coordinates): tiles only.
 * - [blur], 0..1: tiles at full strength, the header ([gridPinchHeader]) at
 *   [HEADER_BLUR_SHARE] of it while pinching ([headerShare] is 0 while a
 *   list/grid switch blurs the songs alone).
 */
object GridPinch {
    val scale = Animatable(1f)
    val blur = Animatable(0f)
    var pivot by mutableStateOf(Offset.Zero)
    var headerShare by mutableStateOf(HEADER_BLUR_SHARE)
}

/** How strongly the page header blurs during a pinch, against the tiles. */
private const val HEADER_BLUR_SHARE = 0.5f

/** Strongest blur, at [GridPinch.blur] = 1. */
private val MAX_BLUR = 6.dp

private val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Delay per tile of distance from the pinch, for the ripple. */
private const val PULSE_STAGGER_MS = 35L

/** How old a ripple may be and still be joined by a tile that just appeared. */
private const val PULSE_FRESH_MS = 250L

/**
 * Makes a grid tile take part in a pinch: it moves and scales as if the grid
 * were zoomed about the fingers ([GridPinch]), blurs and fades a little with
 * it, and dips and springs back in each ripple ([GridPulse]). The ripple is
 * read off composition, so it costs no recomposition.
 */
fun Modifier.gridPulse(): Modifier = composed {
    val pulse = remember { Animatable(1f) }
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val maxBlurPx = with(LocalDensity.current) { MAX_BLUR.toPx() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // A tile that has only just appeared (a list/grid switch builds them
        // all at once) still joins a ripple fired a moment ago; one scrolled
        // in later does not replay an old one.
        snapshotFlow { GridPulse.event }.collectLatest { event ->
            if (android.os.SystemClock.uptimeMillis() - event.at > PULSE_FRESH_MS) return@collectLatest
            if (holder[0]?.isAttached != true) androidx.compose.runtime.withFrameNanos { }
            val coordinates = holder[0]?.takeIf { it.isAttached } ?: return@collectLatest
            val center = coordinates.boundsInRoot().center
            val distance = (center - event.pivot).getDistance() / event.cellPx
            if (distance > 8f) return@collectLatest
            delay((distance * PULSE_STAGGER_MS).toLong())
            pulse.snapTo(event.amplitude)
            pulse.animateTo(1f, spring(FAST_DAMPING, FAST_STIFFNESS))
        }
    }
    this
        .onPlaced { holder[0] = it }
        .graphicsLayer {
            val zoom = GridPinch.scale.value
            if (zoom != 1f) {
                val center = holder[0]?.takeIf { it.isAttached }?.boundsInRoot()?.center
                if (center != null) {
                    val shift = (center - GridPinch.pivot) * (zoom - 1f)
                    translationX = shift.x
                    translationY = shift.y
                }
            }
            scaleX = pulse.value * zoom
            scaleY = pulse.value * zoom
            val strength = GridPinch.blur.value
            alpha = 1f - strength * 0.18f
            val radius = strength * maxBlurPx
            renderEffect = if (blurSupported && radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
        }
}

/**
 * For a page's header (album art, title, buttons, a tab's heading): blurs a
 * little during a pinch but never moves or scales, and stays drawn above the
 * tiles, which pass under it as they spread.
 */
fun Modifier.gridPinchHeader(): Modifier = composed {
    val maxBlurPx = with(LocalDensity.current) { MAX_BLUR.toPx() }
    this
        .zIndex(1f)
        .graphicsLayer {
            val radius = GridPinch.blur.value * GridPinch.headerShare * maxBlurPx
            renderEffect = if (blurSupported && radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
        }
}

/**
 * For the page art behind a collection (outside the list): blurs with the
 * header during a pinch, but is not lifted above the list the way
 * [gridPinchHeader] lifts the header rows — the art would cover the title.
 */
fun Modifier.gridPinchBackdrop(): Modifier = composed {
    val maxBlurPx = with(LocalDensity.current) { MAX_BLUR.toPx() }
    this.graphicsLayer {
        val radius = GridPinch.blur.value * GridPinch.headerShare * maxBlurPx
        renderEffect = if (blurSupported && radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
    }
}

/** For list rows: blurs with the songs while the page switches between list and grid. */
fun Modifier.gridSwitchBlur(): Modifier = composed {
    val maxBlurPx = with(LocalDensity.current) { MAX_BLUR.toPx() }
    this.graphicsLayer {
        val radius = if (GridPinch.headerShare == 0f) GridPinch.blur.value * maxBlurPx else 0f
        renderEffect = if (blurSupported && radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
    }
}

/**
 * The list/grid switch: the songs blur, change layout behind the blur via
 * [swap], and clear while the tiles ripple — the same look as a pinch that
 * changes the column count. The header stays sharp throughout.
 */
suspend fun animateGridSwitch(rippleFrom: Offset, cellPx: Float, swap: () -> Unit) {
    if (!animationsEnabled()) {
        swap()
        return
    }
    GridPinch.headerShare = 0f
    GridPinch.blur.animateTo(1f, tween(90))
    swap()
    // No waiting for the new layout: the blur starts clearing at once, and
    // the new tiles join the ripple as they appear (see [PULSE_FRESH_MS]).
    GridPulse.fire(rippleFrom, 0.9f, cellPx)
    GridPinch.blur.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
    GridPinch.headerShare = HEADER_BLUR_SHARE
}

/**
 * Two-finger pinch on a grid to change its column count, like Google Photos,
 * with the M3 Expressive feedback:
 *
 * - While pinching, the tiles follow the fingers on a spring as if the grid
 *   were zoomed, and blur (from Android 12) and fade a little; the page's
 *   header blurs half as much but holds still. A pill in the middle says how
 *   many columns it is at.
 * - Spreading past [STEP_IN] takes a column away (bigger items), pinching past
 *   [STEP_OUT] adds one. The tiles are relaid behind the blur at once rather
 *   than gliding; the pill pops and its number slides; the tiles ripple out
 *   from the fingers; the zoom springs back; a tick.
 * - At the fewest or most columns the tiles only give a little, like stiff
 *   rubber, and the pill shakes once.
 * - Letting go clears the blur, springs everything home and ripples the tiles
 *   once more as they settle.
 *
 * One-finger scrolling is untouched: only events with two pointers down are
 * consumed, on the Initial pass so the list does not scroll mid-pinch.
 */
@Composable
fun Modifier.pinchToResizeGrid(
    kind: GridKind,
    columns: Int,
): Modifier {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val scale = GridPinch.scale
    val blur = GridPinch.blur
    val pill = remember { Animatable(0f) }
    val numberShift = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    var pillColumns by remember { mutableIntStateOf(columns) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentColumns by rememberUpdatedState(columns)

    val textMeasurer = rememberTextMeasurer()
    val gridIcon = rememberVectorPainter(Icons.Rounded.GridView)
    val pillColor = MaterialTheme.colorScheme.primaryContainer
    val pillInk = MaterialTheme.colorScheme.onPrimaryContainer
    val labels = remember(context) {
        (GridKind.MIN_COLUMNS..GridKind.MAX_COLUMNS).associateWith {
            context.getString(R.string.grid_columns_value, it)
        }
    }
    val labelStyle = remember(pillInk) { TextStyle(color = pillInk, fontSize = 16.sp, fontWeight = FontWeight.Medium) }

    fun cellPx(cols: Int): Float = size.width / cols.coerceAtLeast(1).toFloat()
    fun toRoot(local: Offset): Offset =
        coordinates[0]?.takeIf { it.isAttached }?.let { it.localToRoot(local) } ?: local

    return this
        .onSizeChanged { size = it }
        .onPlaced { coordinates[0] = it }
        // The pill, over everything and never blurred.
        .drawWithPill {
            val p = pill.value
            if (p <= 0.01f) return@drawWithPill
            val label = labels[pillColumns] ?: return@drawWithPill
            val text = textMeasurer.measure(label, labelStyle)
            val height = 44.dp.toPx()
            val padding = 20.dp.toPx()
            val iconSize = 18.dp.toPx()
            val gap = 6.dp.toPx()
            val width = padding * 2 + iconSize + gap + text.size.width
            val center = Offset(this.size.width / 2f, this.size.height * 0.42f)
            val topLeft = Offset(center.x - width / 2f, center.y - height / 2f)
            rotate(shake.value, center) {
                scale(p.coerceAtLeast(0f), center) {
                    drawRoundRect(
                        color = pillColor.copy(alpha = (p * 1.6f).coerceIn(0f, 1f)),
                        topLeft = topLeft,
                        size = Size(width, height),
                        cornerRadius = CornerRadius(height / 2f),
                    )
                    translate(topLeft.x + padding, center.y - iconSize / 2f) {
                        with(gridIcon) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(pillInk)) }
                    }
                    clipRect(topLeft.x, topLeft.y, topLeft.x + width, topLeft.y + height) {
                        drawText(
                            text,
                            topLeft = Offset(
                                topLeft.x + padding + iconSize + gap,
                                center.y - text.size.height / 2f + numberShift.value,
                            ),
                        )
                    }
                }
            }
        }
        .pointerInput(kind) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var baseline = 0f
                var start = 0f
                var pinching = false
                var limitShaken = false
                var cols = currentColumns
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size < 2) continue
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val distance = (a - b).getDistance()
                    if (!pinching) {
                        pinching = true
                        baseline = distance.coerceAtLeast(1f)
                        start = baseline
                        GridPinch.pivot = toRoot((a + b) / 2f)
                        GridPinch.headerShare = HEADER_BLUR_SHARE
                        pillColumns = cols
                        limitShaken = false
                        scope.launch { pill.animateTo(1f, spring(FAST_DAMPING, FAST_STIFFNESS)) }
                    } else {
                        val ratio = distance / baseline
                        val step = when {
                            ratio > STEP_IN && cols > GridKind.MIN_COLUMNS -> -1
                            ratio < STEP_OUT && cols < GridKind.MAX_COLUMNS -> 1
                            else -> 0
                        }
                        if (step != 0) {
                            cols += step
                            AppSettings.setGridColumns(kind, cols)
                            haptics.play(Haptic.Tick)
                            baseline = distance
                            pillColumns = cols
                            // The zoom springs back from where the fingers had it.
                            scope.launch { scale.animateTo(1f, spring(GRID_DAMPING, GRID_STIFFNESS)) }
                            if (animationsEnabled()) {
                                scope.launch {
                                    pill.animateTo(1f, spring(FAST_DAMPING, FAST_STIFFNESS), initialVelocity = 9f)
                                }
                                scope.launch {
                                    // Fewer columns: the old number leaves upward.
                                    numberShift.snapTo(with(density) { (if (step < 0) 14.dp else (-14).dp).toPx() })
                                    numberShift.animateTo(0f, spring(FAST_DAMPING, FAST_STIFFNESS))
                                }
                                GridPulse.fire(GridPinch.pivot, 0.82f, cellPx(cols))
                            }
                        } else {
                            // Within one step: follow the fingers, damped like
                            // stiff rubber where there is no step left to take.
                            val atEnd = (ratio > 1f && cols == GridKind.MIN_COLUMNS) ||
                                (ratio < 1f && cols == GridKind.MAX_COLUMNS)
                            val limit = if (atEnd) 0.06f else 0.14f
                            val target = 1f + limit * tanh((ratio - 1f) / limit)
                            scope.launch { scale.animateTo(target, spring(GRID_DAMPING, GRID_STIFFNESS)) }
                            if (atEnd && abs(ratio - 1f) > 0.12f && !limitShaken) {
                                limitShaken = true
                                haptics.play(Haptic.Tick)
                                if (animationsEnabled()) {
                                    scope.launch {
                                        shake.animateTo(
                                            0f,
                                            spring(0.35f, 900f),
                                            initialVelocity = if (ratio > 1f) 260f else -260f,
                                        )
                                    }
                                }
                            }
                        }
                        val strength = (0.35f + abs(ln(distance / start)) * 2.4f).coerceIn(0f, 1f)
                        scope.launch { blur.animateTo(strength, spring(1f, 1500f)) }
                    }
                    event.changes.forEach { it.consume() }
                }
                if (pinching) {
                    scope.launch { scale.animateTo(1f, spring(GRID_DAMPING, GRID_STIFFNESS)) }
                    scope.launch { blur.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
                    scope.launch { pill.animateTo(0f, spring(FAST_DAMPING, FAST_STIFFNESS)) }
                    GridPulse.fire(GridPinch.pivot, 0.94f, cellPx(cols))
                }
            }
        }
}

/** Draws [block] over the content. */
private fun Modifier.drawWithPill(
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
): Modifier = this.drawWithContent {
    drawContent()
    block()
}

/** [gridPinchHeader] as a value, for call sites outside a composable modifier chain. */
val GridPinchHeaderFeel: Modifier = Modifier.gridPinchHeader()

/** [gridSwitchBlur] as a value, for call sites outside a composable modifier chain. */
val GridSwitchBlurFeel: Modifier = Modifier.gridSwitchBlur()

/** [gridPinchBackdrop] as a value, for call sites outside a composable modifier chain. */
val GridPinchBackdropFeel: Modifier = Modifier.gridPinchBackdrop()
