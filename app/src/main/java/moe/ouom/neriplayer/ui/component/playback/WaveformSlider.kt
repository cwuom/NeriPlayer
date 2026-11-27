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

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.lerp
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import moe.ouom.neriplayer.ui.animation.AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS
import moe.ouom.neriplayer.ui.animation.isNewAmbientFrameSlot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin

private const val WAVE_ANIMATION_DURATION_NS = 2_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val WAVE_SAMPLE_SPACING_PX = 6f
private const val MIN_WAVE_SEGMENTS = 48
private const val MAX_WAVE_SEGMENTS = 180
private const val WAITING_PULSE_ANIMATION_DURATION_NS = 1_400_000_000L
private const val WAITING_PULSE_RADIUS_SEGMENTS = 4f
private const val MIN_WAITING_PULSE_SEGMENTS = 1
private const val MAX_WAITING_PULSE_SEGMENTS = 72

// Material: M3 Expressive 波浪进度, 波浪只画已播放段, 未播放段是平直轨道并在末端留停止点
private val WaveAmplitude = 3.dp
private val WaveLength = 40.dp
private val WaveTrackThickness = 4.dp
private val WaveThumbWidth = 4.dp
private val WaveThumbHeight = 20.dp
private val WaveThumbDragHeight = 28.dp
private val WaveThumbGap = 4.dp
private val WaveStopIndicatorSize = 4.dp

// Clean: 无拇指的扁平胶囊, 按住时加粗给出可拖动反馈
private val FlatTrackThickness = 6.dp
private val FlatTrackDragThickness = 12.dp

@Composable
fun WaveformSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onValueChangeStarted: (Float) -> Unit = {},
    onValueChangeCanceled: () -> Unit = {},
    enabled: Boolean = true,
    isPlaybackWaiting: Boolean = false,
    isProgressStalled: Boolean = false,
    isProgressPreviewing: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    durationMs: Long = 0L,
    playbackSpeed: Float = 1f,
    playbackSessionKey: String? = null
) {
    val clampedValue = normalizeWaveProgress(value)
    val flatStyle = NeriDesign.isClean
    val onSurface = MaterialTheme.colorScheme.onSurface
    val activeColor = when {
        !enabled -> onSurface.copy(alpha = .38f)
        // Clean 已播放段用中性墨色, 强调色只留给可交互元素
        flatStyle -> onSurface.copy(alpha = 0.92f)
        else -> activeTint
    }
    val inactiveColor = when {
        flatStyle -> onSurface.copy(alpha = if (enabled) 0.18f else 0.1f)
        else -> activeTint.copy(alpha = if (enabled) 0.26f else 0.12f)
    }
    val thumbColor = if (enabled) activeTint else onSurface.copy(alpha = .38f)

    var isDragging by remember { mutableStateOf(false) }
    val latestOnValueChangeCanceled by rememberUpdatedState(onValueChangeCanceled)
    LaunchedEffect(enabled, isDragging) {
        if (!enabled && isDragging) {
            isDragging = false
            latestOnValueChangeCanceled()
        }
    }

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (
            !flatStyle && enabled && isPlaying && !isPlaybackWaiting && !isDragging
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "amplitude_animation"
    )
    val dragEmphasis by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = NeriMotion.snappy(),
        label = "progress_drag_emphasis"
    )

    var phase by remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val isWaitingPulseAnimating = animationsEnabled && isPlaybackWaiting && !isDragging
    val isWaveAnimating =
        animationsEnabled && enabled && isPlaying && !isPlaybackWaiting && !isDragging
    val isProgressPredicting = shouldPredictWaveProgress(
        isWaveAnimating = isWaveAnimating,
        isProgressStalled = isProgressStalled,
        isProgressPreviewing = isProgressPreviewing
    )
    val waveProgress = remember(playbackSessionKey) { WaveProgressPredictor(clampedValue) }
    LaunchedEffect(
        playbackSessionKey,
        clampedValue,
        durationMs,
        playbackSpeed,
        isProgressPredicting
    ) {
        waveProgress.updateTarget(
            targetValue = clampedValue,
            durationMs = durationMs,
            playbackSpeed = playbackSpeed,
            animate = isProgressPredicting
        )
    }
    LaunchedEffect(
        lifecycleOwner,
        waveProgress,
        isWaitingPulseAnimating,
        isWaveAnimating,
        isProgressPredicting
    ) {
        if (!isWaitingPulseAnimating && !isWaveAnimating) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            waveProgress.resetFrameAnchor()
            val anchorPhase = phase
            var anchorFrameNs = 0L
            var lastRenderedFrameNs = 0L
            while (isActive) {
                // 状态必须在帧回调内写入，协程恢复后再写会落到下一帧，和其它动画错开一个 vsync
                withFrameNanos { frameNs ->
                    if (!isNewAmbientFrameSlot(lastRenderedFrameNs, frameNs, AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS)) {
                        return@withFrameNanos
                    }
                    lastRenderedFrameNs = frameNs
                    if (anchorFrameNs == 0L) {
                        anchorFrameNs = frameNs
                    }
                    val elapsedNs = frameNs - anchorFrameNs
                    if (isProgressPredicting) {
                        waveProgress.onFrame(frameNs)
                    }
                    phase = if (isWaitingPulseAnimating) {
                        resolveWaitingPulsePhase(anchorPhase, elapsedNs)
                    } else {
                        resolveWavePhase(anchorPhase, elapsedNs)
                    }
                }
            }
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
                    sliderFractionAt(offset.x, size.width.toFloat())?.let(onValueChangeStarted)
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
                    sliderFractionAt(change.position.x, size.width.toFloat())?.let(onValueChange)
                }
            )
        }.pointerInput(onValueChangeStarted, onValueChangeFinished) {
            detectTapGestures { offset ->
                val fraction = sliderFractionAt(offset.x, size.width.toFloat()) ?: return@detectTapGestures
                onValueChangeStarted(fraction)
                onValueChangeFinished()
            }
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
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clampedValue, 0f..1f)
                if (!enabled) disabled()
            }
    ) {
        val centerY = size.height / 2
        val progressValue = if (isProgressPredicting) {
            waveProgress.currentValue
        } else {
            clampedValue
        }
        val progressPx = progressValue * size.width
        val currentPhase = phase

        if (flatStyle) {
            drawFlatProgress(
                progressPx = progressPx,
                centerY = centerY,
                thickness = lerp(FlatTrackThickness, FlatTrackDragThickness, dragEmphasis).toPx(),
                activeColor = activeColor,
                inactiveColor = inactiveColor,
                waitingPhase = if (isPlaybackWaiting && !isDragging) currentPhase else null
            )
            return@Canvas
        }

        if (isPlaybackWaiting && !isDragging) {
            val preferredSpacingPx = 8.dp.toPx()
            val pulseSegmentCount = resolveWaitingPulseSegmentCount(
                widthPx = size.width,
                preferredSpacingPx = preferredSpacingPx
            )
            val pulseSegmentWidth = size.width / pulseSegmentCount
            val pulseStrokeWidth = (pulseSegmentWidth * 0.42f)
                .coerceIn(1.5.dp.toPx(), 3.dp.toPx())
            val minHalfHeight = 2.dp.toPx()
            val maxHalfHeight = 7.dp.toPx()

            repeat(pulseSegmentCount) { index ->
                val x = (index + 0.5f) * pulseSegmentWidth
                val strength = resolveWaitingPulseStrength(
                    segmentIndex = index,
                    segmentCount = pulseSegmentCount,
                    phase = currentPhase
                )
                val halfHeight = minHalfHeight + (maxHalfHeight - minHalfHeight) * strength
                val baseColor = if (x <= progressPx) activeColor else inactiveColor
                val pulseColor = baseColor.copy(
                    alpha = (baseColor.alpha * (0.55f + 0.45f * strength)).coerceIn(0f, 1f)
                )
                drawLine(
                    color = pulseColor,
                    start = Offset(x, centerY - halfHeight),
                    end = Offset(x, centerY + halfHeight),
                    strokeWidth = pulseStrokeWidth,
                    cap = StrokeCap.Round
                )
            }

            drawWaveThumb(progressPx, centerY, WaveThumbHeight.toPx(), thumbColor)
            return@Canvas
        }

        val strokePx = WaveTrackThickness.toPx()
        val halfThumbPx = WaveThumbWidth.toPx() / 2f
        val gapPx = WaveThumbGap.toPx()
        val amplitudePx = WaveAmplitude.toPx() * animatedAmplitude
        val waveNumber = TWO_PI / WaveLength.toPx()
        val activeEnd = progressPx - halfThumbPx - gapPx
        if (activeEnd > 0f) {
            val segmentCount = resolveWaveSegmentCount(activeEnd)
            val segmentWidth = activeEnd / segmentCount
            wavePath.rewind()
            wavePath.moveTo(0f, centerY + sin(currentPhase) * amplitudePx)
            for (index in 1..segmentCount) {
                val x = index * segmentWidth
                wavePath.lineTo(x, centerY + sin(x * waveNumber + currentPhase) * amplitudePx)
            }
            drawPath(
                path = wavePath,
                color = activeColor,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        val stopRadius = WaveStopIndicatorSize.toPx() / 2f
        val inactiveStart = progressPx + halfThumbPx + gapPx
        val inactiveEnd = size.width - stopRadius
        if (inactiveEnd > inactiveStart + strokePx) {
            drawLine(
                color = inactiveColor,
                start = Offset(inactiveStart + strokePx / 2f, centerY),
                end = Offset(inactiveEnd - strokePx / 2f, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = activeColor,
                radius = stopRadius,
                center = Offset(inactiveEnd, centerY)
            )
        }

        drawWaveThumb(
            progressPx = progressPx,
            centerY = centerY,
            heightPx = lerp(WaveThumbHeight, WaveThumbDragHeight, dragEmphasis).toPx(),
            color = thumbColor
        )
    }
}

private fun DrawScope.drawWaveThumb(progressPx: Float, centerY: Float, heightPx: Float, color: Color) {
    val widthPx = WaveThumbWidth.toPx()
    val x = progressPx.coerceIn(widthPx / 2f, size.width - widthPx / 2f)
    drawRoundRect(
        color = color,
        topLeft = Offset(x - widthPx / 2f, centerY - heightPx / 2f),
        size = Size(widthPx, heightPx),
        cornerRadius = CornerRadius(widthPx / 2f)
    )
}

private fun DrawScope.drawFlatProgress(
    progressPx: Float,
    centerY: Float,
    thickness: Float,
    activeColor: Color,
    inactiveColor: Color,
    waitingPhase: Float?
) {
    val half = thickness / 2f
    val start = Offset(half, centerY)
    val end = Offset(size.width - half, centerY)
    drawLine(inactiveColor, start, end, strokeWidth = thickness, cap = StrokeCap.Round)
    if (waitingPhase != null) {
        // 缓冲时一道柔光沿轨道扫过, 比跳动的竖条安静
        val band = size.width * 0.28f
        val center = (waitingPhase.floorMod(TWO_PI) / TWO_PI) * (size.width + band) - band / 2f
        clipRect(top = centerY - half, bottom = centerY + half) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, activeColor.copy(alpha = 0.32f), Color.Transparent),
                    startX = center - band / 2f,
                    endX = center + band / 2f
                ),
                start = start,
                end = end,
                strokeWidth = thickness,
                cap = StrokeCap.Round
            )
        }
    }
    if (progressPx > 0f) {
        // 矩形裁剪按整像素对齐, 慢速推进时边缘会跳格; 直接画到小数坐标让抗锯齿负责亚像素移动
        val activeEnd = (progressPx - half).coerceIn(half, size.width - half)
        drawLine(activeColor, start, Offset(activeEnd, centerY), strokeWidth = thickness, cap = StrokeCap.Round)
    }
}

private val TWO_PI = 2f * Math.PI.toFloat()

internal fun resolveWaveSegmentCount(widthPx: Float): Int {
    return ceil(widthPx.coerceAtLeast(0f) / WAVE_SAMPLE_SPACING_PX)
        .toInt()
        .coerceIn(MIN_WAVE_SEGMENTS, MAX_WAVE_SEGMENTS)
}

internal fun resolveWavePhase(anchorPhase: Float, elapsedNs: Long): Float {
    return resolveAnimationPhase(anchorPhase, elapsedNs, WAVE_ANIMATION_DURATION_NS)
}

internal fun resolveWaveProgress(
    anchorValue: Float,
    durationMs: Long,
    playbackSpeed: Float,
    elapsedNs: Long
): Float {
    val anchor = normalizeWaveProgress(anchorValue)
    if (durationMs <= 0L) return anchor
    val speed = normalizeWavePlaybackSpeed(playbackSpeed)
    if (speed <= 0f) return anchor
    val elapsedMs = elapsedNs.coerceAtLeast(0L).toDouble() / NANOS_PER_MILLISECOND
    val progressAdvance = elapsedMs / durationMs.toDouble() * speed
    return (anchor + progressAdvance.toFloat()).coerceIn(0f, 1f)
}

internal fun shouldPredictWaveProgress(
    isWaveAnimating: Boolean,
    isProgressStalled: Boolean,
    isProgressPreviewing: Boolean
): Boolean {
    return isWaveAnimating && !isProgressStalled && !isProgressPreviewing
}

internal fun resolveWaitingPulsePhase(anchorPhase: Float, elapsedNs: Long): Float {
    return resolveAnimationPhase(
        anchorPhase = anchorPhase,
        elapsedNs = elapsedNs,
        durationNs = WAITING_PULSE_ANIMATION_DURATION_NS
    )
}

internal fun resolveWaitingPulseSegmentCount(
    widthPx: Float,
    preferredSpacingPx: Float
): Int {
    if (preferredSpacingPx <= 0f) return MIN_WAITING_PULSE_SEGMENTS
    return ceil(widthPx.coerceAtLeast(0f) / preferredSpacingPx)
        .toInt()
        .coerceIn(MIN_WAITING_PULSE_SEGMENTS, MAX_WAITING_PULSE_SEGMENTS)
}

internal fun resolveWaitingPulseStrength(
    segmentIndex: Int,
    segmentCount: Int,
    phase: Float
): Float {
    if (segmentCount <= 0 || segmentIndex !in 0 until segmentCount) return 0f
    val normalizedPhase = phase.floorMod(TWO_PI)
    val travelDistance = segmentCount - 1 + WAITING_PULSE_RADIUS_SEGMENTS * 2f
    val pulseHead = normalizedPhase / TWO_PI * travelDistance - WAITING_PULSE_RADIUS_SEGMENTS
    val distance = abs(segmentIndex - pulseHead)
    return (1f - distance / WAITING_PULSE_RADIUS_SEGMENTS)
        .coerceIn(0f, 1f)
        .pow(2)
}

private fun resolveAnimationPhase(
    anchorPhase: Float,
    elapsedNs: Long,
    durationNs: Long
): Float {
    val elapsedInCycle = elapsedNs.floorMod(durationNs)
    val cycleFraction = elapsedInCycle.toFloat() / durationNs.toFloat()
    return (anchorPhase + TWO_PI * cycleFraction).floorMod(TWO_PI)
}

private fun normalizeWaveProgress(value: Float): Float {
    return value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
}

private fun normalizeWavePlaybackSpeed(value: Float): Float {
    return value.takeIf { it.isFinite() && it > 0f } ?: 0f
}

internal class WaveProgressPredictor(initialValue: Float) {
    private var anchorValue = normalizeWaveProgress(initialValue)
    private var targetValue = anchorValue
    private var durationMs = 0L
    private var playbackSpeed = 0f
    private var anchorFrameNs = 0L
    private var hasPendingAnchor = true
    private var wasAnimating = false

    var currentValue = anchorValue
        private set

    fun updateTarget(
        targetValue: Float,
        durationMs: Long,
        playbackSpeed: Float,
        animate: Boolean
    ) {
        val normalizedTarget = normalizeWaveProgress(targetValue)
        val normalizedDurationMs = durationMs.coerceAtLeast(0L)
        val normalizedPlaybackSpeed = normalizeWavePlaybackSpeed(playbackSpeed)
        val inputChanged = normalizedTarget != this.targetValue ||
            normalizedDurationMs != this.durationMs ||
            normalizedPlaybackSpeed != this.playbackSpeed
        if (inputChanged || !animate || !wasAnimating) {
            anchorValue = normalizedTarget
            currentValue = normalizedTarget
            hasPendingAnchor = true
        }
        this.targetValue = normalizedTarget
        this.durationMs = normalizedDurationMs
        this.playbackSpeed = normalizedPlaybackSpeed
        wasAnimating = animate
    }

    fun onFrame(frameNs: Long) {
        if (frameNs <= 0L) return
        if (hasPendingAnchor || anchorFrameNs == 0L || frameNs < anchorFrameNs) {
            anchorFrameNs = frameNs
            currentValue = anchorValue
            hasPendingAnchor = false
            return
        }
        currentValue = resolveWaveProgress(
            anchorValue = anchorValue,
            durationMs = durationMs,
            playbackSpeed = playbackSpeed,
            elapsedNs = frameNs - anchorFrameNs
        )
    }

    fun resetFrameAnchor() {
        anchorValue = currentValue
        anchorFrameNs = 0L
        hasPendingAnchor = true
    }
}

private fun Long.floorMod(other: Long): Long {
    return ((this % other) + other) % other
}

private fun Float.floorMod(other: Float): Float {
    return ((this % other) + other) % other
}

/** 触点在进度条上的比例；宽度未测量时返回 null */
internal fun sliderFractionAt(x: Float, width: Float): Float? =
    if (width > 0f) (x / width).coerceIn(0f, 1f) else null
