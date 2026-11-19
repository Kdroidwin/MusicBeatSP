package com.music.bitchord.ui.components

import com.music.bitchord.data.settings.AppGestureBinding
import com.music.bitchord.data.settings.AppGestureAction
import com.music.bitchord.data.settings.AppGestureShape
import com.music.bitchord.data.settings.GesturePoint
import kotlin.math.hypot

/** Compares drawn paths by shape and direction, independent of screen size or drawing speed. */
internal object GesturePathMatcher {
    private const val SAMPLE_COUNT = 48
    // A hand-drawn path rarely lands exactly on the thin guide line. A slightly
    // wider tolerance keeps real finger traces usable while still separating
    // the four intentionally different patterns.
    private const val MAX_MATCH_DISTANCE = 0.34f

    fun match(
        input: List<GesturePoint>,
        bindings: Map<AppGestureShape, AppGestureBinding>,
    ): AppGestureShape? {
        val normalizedInput = normalize(input) ?: return null
        if (!hasCorner(normalizedInput)) return null
        val ranked = bindings.asSequence()
            .filter { (_, binding) -> binding.action != AppGestureAction.NONE && binding.points.size >= 3 }
            .mapNotNull { (shape, binding) ->
                val normalizedTemplate = normalize(binding.points) ?: return@mapNotNull null
                // Direction is part of the saved pattern. Treating the reverse
                // stroke as equivalent made L / mirrored-L and U / inverted-U
                // indistinguishable in normal use.
                shape to distance(normalizedInput, normalizedTemplate)
            }
            .sortedBy { it.second }
            .toList()
        val best = ranked.firstOrNull() ?: return null
        val secondDistance = ranked.getOrNull(1)?.second
        if (best.second > MAX_MATCH_DISTANCE) return null
        if (secondDistance != null && secondDistance - best.second < 0.008f) return null
        return best.first
    }

    fun normalized(points: List<GesturePoint>): List<GesturePoint> = normalize(points).orEmpty()

    private fun normalize(points: List<GesturePoint>): List<GesturePoint>? {
        if (points.size < 3) return null
        val finite = points.filter { it.x.isFinite() && it.y.isFinite() }
        if (finite.size < 3) return null
        val minX = finite.minOf { it.x }
        val maxX = finite.maxOf { it.x }
        val minY = finite.minOf { it.y }
        val maxY = finite.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        if (width < 0.08f || height < 0.08f) return null
        val normalized = finite.map { point ->
            GesturePoint(
                x = ((point.x - minX) / width).coerceIn(0f, 1f),
                y = ((point.y - minY) / height).coerceIn(0f, 1f),
            )
        }
        val length = normalized.zipWithNext().sumOf { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
        if (length < 1.25) return null
        return resample(normalized)
    }

    private fun resample(points: List<GesturePoint>): List<GesturePoint> {
        val lengths = points.zipWithNext().map { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat() }
        val total = lengths.sum()
        if (total <= 0f) return emptyList()
        val result = ArrayList<GesturePoint>(SAMPLE_COUNT)
        var segmentIndex = 0
        var segmentStartDistance = 0f
        for (sample in 0 until SAMPLE_COUNT) {
            val target = total * sample / (SAMPLE_COUNT - 1)
            while (segmentIndex < lengths.lastIndex && segmentStartDistance + lengths[segmentIndex] < target) {
                segmentStartDistance += lengths[segmentIndex]
                segmentIndex++
            }
            val segmentLength = lengths[segmentIndex].coerceAtLeast(0.0001f)
            val progress = ((target - segmentStartDistance) / segmentLength).coerceIn(0f, 1f)
            val start = points[segmentIndex]
            val end = points[segmentIndex + 1]
            result += GesturePoint(
                start.x + (end.x - start.x) * progress,
                start.y + (end.y - start.y) * progress,
            )
        }
        return result
    }

    private fun distance(first: List<GesturePoint>, second: List<GesturePoint>): Float {
        if (first.size != second.size || first.isEmpty()) return Float.MAX_VALUE
        val meanSquared = first.indices.sumOf { index ->
            val dx = first[index].x - second[index].x
            val dy = first[index].y - second[index].y
            (dx * dx + dy * dy).toDouble()
        } / first.size
        return kotlin.math.sqrt(meanSquared).toFloat()
    }

    private fun hasCorner(points: List<GesturePoint>): Boolean = (2 until points.lastIndex - 1).any { index ->
        // Compare across a short window. Finger traces round corners, so an
        // adjacent-sample angle can look almost straight even when the overall
        // stroke clearly turns through a right angle.
        val before = points[index - 2]
        val middle = points[index]
        val after = points[index + 2]
        val ax = middle.x - before.x
        val ay = middle.y - before.y
        val bx = after.x - middle.x
        val by = after.y - middle.y
        val aLength = hypot(ax.toDouble(), ay.toDouble()).toFloat().coerceAtLeast(0.0001f)
        val bLength = hypot(bx.toDouble(), by.toDouble()).toFloat().coerceAtLeast(0.0001f)
        val dot = (ax * bx + ay * by) / (aLength * bLength)
        dot < 0.72f
    }
}
