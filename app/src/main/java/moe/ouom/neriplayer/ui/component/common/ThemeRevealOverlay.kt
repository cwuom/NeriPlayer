package moe.ouom.neriplayer.ui.component.common

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
 * File: moe.ouom.neriplayer.ui.component/ThemeRevealOverlay
 * Updated: 2026/10/15
 */


import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import moe.ouom.neriplayer.ui.theme.NeriMotion

private const val ReverseFadeSpan = 0.12f
private const val MaxRevealFrameStepNanos = 40_000_000L

/**
 * Circular theme hand-off. Forward: the new theme grows out of [originInWindow] while the old
 * snapshot stays outside the circle. Reverse (Telegram-style): the old snapshot collapses back
 * into the origin, uncovering the new theme. The snapshot is drawn through a shader brush as
 * an anti-aliased ring or disc, so no offscreen layer or clear blend is needed per frame.
 */
@Composable
fun ThemeRevealOverlay(
    snapshot: ImageBitmap?,
    fallbackColor: Color,
    originInWindow: Offset,
    modifier: Modifier = Modifier,
    startRadiusPx: Float = 1f,
    reverse: Boolean = false,
    durationMillis: Int = 720,
    onFinished: () -> Unit
) {
    var containerOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
    var hasFinished by remember { mutableStateOf(false) }
    val currentOnFinished by rememberUpdatedState(onFinished)
    val progress = remember { mutableFloatStateOf(0f) }
    val finishReveal = {
        if (!hasFinished) {
            hasFinished = true
            currentOnFinished()
        }
    }

    DisposableEffect(Unit) {
        onDispose(finishReveal)
    }

    LaunchedEffect(Unit) {
        try {
            progress.floatValue = 0f
            val easing = if (reverse) NeriMotion.EmphasizedAccelerateSoft else NeriMotion.SmoothInOut
            val durationNanos = durationMillis * 1_000_000L
            var elapsed = 0L
            // 交接首帧开始计时，避免先停住等待图标和主题恢复平稳
            var last = withFrameNanos { it }
            while (elapsed < durationNanos) {
                val now = withFrameNanos { it }
                // a stalled frame pauses the circle instead of letting it leap ahead unseen
                elapsed += (now - last).coerceIn(0L, MaxRevealFrameStepNanos)
                last = now
                progress.floatValue = easing.transform((elapsed.toFloat() / durationNanos).coerceIn(0f, 1f))
            }
        } finally {
            finishReveal()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerOffsetInWindow = coordinates.positionInWindow()
            }
            .drawWithCache {
                val brush: Brush = snapshot?.let { image ->
                    val bitmap = image.asAndroidBitmap()
                    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    shader.setLocalMatrix(
                        Matrix().apply {
                            setScale(size.width / bitmap.width, size.height / bitmap.height)
                        }
                    )
                    ShaderBrush(shader)
                } ?: SolidColor(fallbackColor)
                val origin = originInWindow - containerOffsetInWindow
                val maxRadius = maxRevealRadius(origin, size).coerceAtLeast(1f)
                val initialRadius = startRadiusPx.coerceIn(1f, maxRadius)
                onDrawBehind {
                    val fraction = progress.floatValue
                    if (reverse) {
                        // collapse fully into the origin and fade the last sliver; stopping at the
                        // toggle's radius left a small disc that vanished in a single frame
                        val radius = maxRadius * (1f - fraction)
                        val alpha = ((1f - fraction) / ReverseFadeSpan).coerceIn(0f, 1f)
                        if (fraction < 1f && radius > 0.5f && alpha > 0f) {
                            drawCircle(brush = brush, radius = radius, center = origin, alpha = alpha)
                        }
                    } else {
                        val radius = initialRadius + (maxRadius - initialRadius) * fraction
                        val ringWidth = maxRadius - radius
                        if (ringWidth > 0.5f) {
                            drawCircle(
                                brush = brush,
                                radius = radius + ringWidth / 2f,
                                center = origin,
                                style = Stroke(width = ringWidth + 1f)
                            )
                        }
                    }
                }
            }
    )
}

private fun maxRevealRadius(origin: Offset, size: Size): Float {
    val corners = listOf(
        Offset.Zero,
        Offset(size.width, 0f),
        Offset(0f, size.height),
        Offset(size.width, size.height)
    )
    return corners.maxOf { corner -> (corner - origin).getDistance() }
}
