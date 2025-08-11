package moe.ouom.neriplayer.ui.component

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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.sin

private const val WAVE_AMPLITUDE = 6f   // 波浪的振幅
private const val WAVE_FREQUENCY = 0.08f // 波浪的频率
private const val WAVE_ANIMATION_DURATION = 2000 // 波浪滚动一周的时间

@Composable
fun WaveformSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val thumbColor = MaterialTheme.colorScheme.primary

    val infiniteTransition = rememberInfiniteTransition(label = "wave_phase")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_ANIMATION_DURATION, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase_animation"
    )

    var isDragging by remember { mutableStateOf(false) }

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying && !isDragging) WAVE_AMPLITUDE else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "amplitude_animation"
    )

    var canvasWidth by remember { mutableStateOf(0f) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(onValueChange, onValueChangeFinished) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished()
                    },
                    onDrag = { change, _ ->
                        if (canvasWidth > 0) {
                            val newValue = (change.position.x / canvasWidth).coerceIn(0f, 1f)
                            onValueChange(newValue)
                        }
                    }
                )
            }
    ) {
        canvasWidth = size.width
        val centerY = size.height / 2
        val progressPx = value * size.width

        val path = Path().apply {
            moveTo(0f, centerY)
            for (x in 0..size.width.toInt()) {
                val angle = x * WAVE_FREQUENCY + phase
                val y = centerY + sin(angle) * animatedAmplitude
                lineTo(x.toFloat(), y)
            }
        }

        drawPath(
            path = path,
            color = inactiveColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = StrokeCap.Round)
        )

        clipRect(right = progressPx) {
            drawPath(
                path = path,
                color = activeColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f, cap = StrokeCap.Round)
            )
        }

        val thumbX = progressPx
        val thumbY = centerY + sin(thumbX * WAVE_FREQUENCY + phase) * animatedAmplitude
        drawCircle(
            color = thumbColor,
            radius = 16f,
            center = Offset(thumbX, thumbY)
        )
    }
}