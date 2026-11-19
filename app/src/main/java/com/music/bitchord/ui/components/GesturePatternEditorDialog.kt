package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.AppGestureAction
import com.music.bitchord.data.settings.AppGestureBinding
import com.music.bitchord.data.settings.AppGestureShape
import com.music.bitchord.data.settings.GesturePoint
import com.music.bitchord.R

@Composable
internal fun GesturePatternEditorDialog(
    shape: AppGestureShape,
    title: String,
    instruction: String,
    binding: AppGestureBinding,
    actionLabel: @Composable (AppGestureAction) -> String,
    onDismiss: () -> Unit,
    onSave: (AppGestureBinding) -> Unit,
) {
    val points = remember { mutableStateListOf<GesturePoint>().apply { addAll(binding.points) } }
    val action = remember { androidx.compose.runtime.mutableIntStateOf(AppGestureAction.entries.indexOf(binding.action).coerceAtLeast(0)) }
    val selectedAction = AppGestureAction.entries[action.intValue]
    val guideColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val strokeColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.tertiary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(instruction, style = MaterialTheme.typography.bodyMedium)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    points.clear()
                                    points += GesturePoint(
                                        (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        (offset.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (points.size < 256) points += GesturePoint(
                                        (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                    )
                                },
                            )
                        },
                ) {
                    val guide = shapeGuide(shape)
                    fun screen(point: GesturePoint) = Offset(
                        x = size.width * (0.12f + point.x * 0.76f),
                        y = size.height * (0.12f + point.y * 0.76f),
                    )
                    fun drawGesturePath(values: List<GesturePoint>, color: androidx.compose.ui.graphics.Color, width: Float) {
                        if (values.size < 2) return
                        val path = Path().apply {
                            moveTo(screen(values.first()).x, screen(values.first()).y)
                            values.drop(1).forEach { point ->
                                val position = screen(point)
                                lineTo(position.x, position.y)
                            }
                        }
                        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
                    }
                    drawGesturePath(guide, guideColor, 5.dp.toPx())
                    drawGesturePath(points, strokeColor, 7.dp.toPx())
                    points.firstOrNull()?.let { drawCircle(startColor, 6.dp.toPx(), screen(it)) }
                    points.lastOrNull()?.takeIf { points.size > 1 }?.let {
                        drawCircle(endColor, 6.dp.toPx(), screen(it))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { points.clear() }) { Text(stringResource(R.string.gesture_clear)) }
                    TextButton(onClick = {
                        action.intValue = (action.intValue + 1) % AppGestureAction.entries.size
                    }) {
                        Text(actionLabel(selectedAction))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = points.size >= 3 && selectedAction != AppGestureAction.NONE,
                onClick = { onSave(AppGestureBinding(selectedAction, GesturePathMatcher.normalized(points))) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun shapeGuide(shape: AppGestureShape): List<GesturePoint> = when (shape) {
    AppGestureShape.L -> listOf(GesturePoint(0.22f, 0.12f), GesturePoint(0.22f, 0.82f), GesturePoint(0.82f, 0.82f))
    AppGestureShape.MIRRORED_L -> listOf(GesturePoint(0.78f, 0.12f), GesturePoint(0.78f, 0.82f), GesturePoint(0.18f, 0.82f))
    AppGestureShape.U -> listOf(GesturePoint(0.18f, 0.12f), GesturePoint(0.18f, 0.82f), GesturePoint(0.82f, 0.82f), GesturePoint(0.82f, 0.12f))
    AppGestureShape.INVERTED_U -> listOf(GesturePoint(0.18f, 0.88f), GesturePoint(0.18f, 0.18f), GesturePoint(0.82f, 0.18f), GesturePoint(0.82f, 0.88f))
}
