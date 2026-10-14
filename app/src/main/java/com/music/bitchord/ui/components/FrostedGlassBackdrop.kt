package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun Modifier.frostedGlassEffect(hazeState: HazeState, tint: Color): Modifier =
    hazeEffect(state = hazeState, style = HazeMaterials.ultraThin(tint)) {
        inputScale = HazeInputScale.Fixed(0.5f)
        noiseFactor = 0f
    }

/** A translucent, low-resolution backdrop blur which lets the sampled page colour show through. */
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun FrostedGlassBackdrop(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surface,
    tintAlpha: Float = 0.22f,
) {
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .hazeEffect(state = hazeState, style = HazeMaterials.ultraThin(tint)) {
                    inputScale = HazeInputScale.Fixed(0.5f)
                    noiseFactor = 0f
                },
        )
        Box(Modifier.matchParentSize().background(tint.copy(alpha = tintAlpha)))
    }
}
