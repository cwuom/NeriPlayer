package moe.ouom.neriplayer.ui.component.playback

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.component/WaveformSlider
 * Created: 2025/8/11
 */

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.sin

private const val WAVE_AMPLITUDE = 6f   // 波浪的振幅
private const val WAVE_FREQUENCY = 0.08f // 波浪的频率
private const val WAVE_ANIMATION_DURATION_NS = 2_000_000_000L
private const val WAVE_PHASE_FRAME_INTERVAL_NS = 1_000_000_000L / 45L
private const val WAVE_SAMPLE_SPACING_PX = 6f
private const val MIN_WAVE_SEGMENTS = 48
private const val MAX_WAVE_SEGMENTS = 180
private val WaveInactiveStroke = androidx.compose.ui.graphics.drawscope.Stroke(
    width = 4f,
    cap = StrokeCap.Round
)
private val WaveActiveStroke = androidx.compose.ui.graphics.drawscope.Stroke(
    width = 6f,
    cap = StrokeCap.Round
)

@Composable
fun WaveformSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    onValueChangeStarted: (Float) -> Unit = {},
    onValueChangeCanceled: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.55f)
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.3f else 0.18f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.55f)

    var isDragging by remember { mutableStateOf(false) }
    val latestOnValueChangeCanceled by rememberUpdatedState(onValueChangeCanceled)
    LaunchedEffect(enabled, isDragging) {
        if (!enabled && isDragging) {
            isDragging = false
            latestOnValueChangeCanceled()
        }
    }

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (enabled && isPlaying && !isDragging) WAVE_AMPLITUDE else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "amplitude_animation"
    )

    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, isPlaying, isDragging) {
        if (!enabled || !isPlaying || isDragging) return@LaunchedEffect
        val anchorPhase = phase
        var anchorFrameNs = 0L
        var nextFrameNs = Long.MIN_VALUE
        while (isActive) {
            val frameNs = withFrameNanos { it }
            if (anchorFrameNs == 0L) {
                anchorFrameNs = frameNs
            }
            if (nextFrameNs != Long.MIN_VALUE && frameNs < nextFrameNs) {
                continue
            }
            nextFrameNs = nextWavePhaseFrameNs(
                frameNs = frameNs,
                currentNextNs = nextFrameNs
            )
            phase = resolveWavePhase(
                anchorPhase = anchorPhase,
                elapsedNs = frameNs - anchorFrameNs
            )
        }
    }

    val dragModifier = if (enabled) {
        Modifier.pointerInput(
            onValueChange,
            onValueChangeFinished,
            onValueChangeStarted,
            onValueChangeCanceled
        ) {
            detectDragGestures(
                onDragStart = { offset ->
                    isDragging = true
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        val startValue = (offset.x / width).coerceIn(0f, 1f)
                        onValueChangeStarted(startValue)
                    }
                },
                onDragEnd = {
                    isDragging = false
                    onValueChangeFinished()
                },
                onDragCancel = {
                    isDragging = false
                    onValueChangeCanceled()
                },
                onDrag = { change, _ ->
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        val newValue = (change.position.x / width).coerceIn(0f, 1f)
                        onValueChange(newValue)
                    }
                }
            )
        }
    } else {
        Modifier
    }
    val wavePath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(dragModifier)
    ) {
        val centerY = size.height / 2
        val progressPx = value * size.width
        val currentPhase = phase
        val segmentCount = ceil(size.width / WAVE_SAMPLE_SPACING_PX)
            .toInt()
            .coerceIn(MIN_WAVE_SEGMENTS, MAX_WAVE_SEGMENTS)
        val segmentWidth = size.width / segmentCount

        wavePath.rewind()
        wavePath.moveTo(0f, centerY + sin(currentPhase) * animatedAmplitude)
        for (index in 1..segmentCount) {
            val x = index * segmentWidth
            val angle = x * WAVE_FREQUENCY + currentPhase
            val y = centerY + sin(angle) * animatedAmplitude
            wavePath.lineTo(x, y)
        }

        drawPath(
            path = wavePath,
            color = inactiveColor,
            style = WaveInactiveStroke
        )

        clipRect(right = progressPx) {
            drawPath(
                path = wavePath,
                color = activeColor,
                style = WaveActiveStroke
            )
        }

        val thumbY = centerY + sin(progressPx * WAVE_FREQUENCY + currentPhase) * animatedAmplitude
        drawCircle(
            color = thumbColor,
            radius = 16f,
            center = Offset(progressPx, thumbY)
        )
    }
}

private val TWO_PI = 2f * Math.PI.toFloat()

private fun resolveWavePhase(anchorPhase: Float, elapsedNs: Long): Float {
    val elapsedInCycle = elapsedNs.floorMod(WAVE_ANIMATION_DURATION_NS)
    val cycleFraction = elapsedInCycle.toFloat() / WAVE_ANIMATION_DURATION_NS.toFloat()
    return (anchorPhase + TWO_PI * cycleFraction) % TWO_PI
}

private fun nextWavePhaseFrameNs(frameNs: Long, currentNextNs: Long): Long {
    if (
        currentNextNs == Long.MIN_VALUE ||
        frameNs - currentNextNs > WAVE_PHASE_FRAME_INTERVAL_NS * 2L
    ) {
        return frameNs + WAVE_PHASE_FRAME_INTERVAL_NS
    }

    var nextNs = currentNextNs
    while (nextNs <= frameNs) {
        nextNs += WAVE_PHASE_FRAME_INTERVAL_NS
    }
    return nextNs
}

private fun Long.floorMod(other: Long): Long {
    return ((this % other) + other) % other
}
