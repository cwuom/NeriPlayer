package moe.ouom.neriplayer.ui.component.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.abs

private const val InterpolatedPlaybackResyncThresholdMs = 220L
private const val InterpolatedPlaybackBackwardToleranceMs = 24L
private const val InterpolatedPlaybackFrameIntervalNanos = 33_000_000L

@Stable
internal class InterpolatedPlaybackPositionState(initialPositionMs: Long) {
    var renderedPositionMs by mutableLongStateOf(initialPositionMs)
    var anchorPositionMs by mutableLongStateOf(initialPositionMs)
    var anchorRealtimeNanos by mutableLongStateOf(System.nanoTime())
}

@Composable
internal fun rememberInterpolatedPlaybackPositionState(
    currentTimeMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float
): InterpolatedPlaybackPositionState {
    val state = remember { InterpolatedPlaybackPositionState(currentTimeMs) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(currentTimeMs, isPlaying) {
        state.anchorPositionMs = currentTimeMs
        state.anchorRealtimeNanos = System.nanoTime()
        state.renderedPositionMs = resolveAnchoredInterpolatedPlaybackPosition(
            externalPositionMs = currentTimeMs,
            renderedPositionMs = state.renderedPositionMs,
            isPlaying = isPlaying
        )
    }

    LaunchedEffect(isPlaying, playbackSpeed, lifecycleOwner) {
        if (!isPlaying) {
            state.renderedPositionMs = currentTimeMs
            return@LaunchedEffect
        }

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastRenderedFrameNanos = 0L
            while (isActive) {
                val frameNanos = withFrameNanos { it }
                if (
                    lastRenderedFrameNanos != 0L &&
                    frameNanos - lastRenderedFrameNanos < InterpolatedPlaybackFrameIntervalNanos
                ) {
                    continue
                }
                lastRenderedFrameNanos = frameNanos
                val predictedPositionMs = resolveInterpolatedPlaybackPosition(
                    anchorPositionMs = state.anchorPositionMs,
                    anchorRealtimeNanos = state.anchorRealtimeNanos,
                    frameRealtimeNanos = frameNanos,
                    playbackSpeed = playbackSpeed,
                    previousRenderedPositionMs = state.renderedPositionMs
                )
                if (predictedPositionMs != state.renderedPositionMs) {
                    state.renderedPositionMs = predictedPositionMs
                }
            }
        }
    }

    return state
}

@Composable
internal fun rememberInterpolatedPlaybackPositionMs(
    currentTimeMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float
): Long {
    return rememberInterpolatedPlaybackPositionState(
        currentTimeMs = currentTimeMs,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed
    ).renderedPositionMs
}

@Composable
internal fun rememberInterpolatedPlaybackPositionProvider(
    currentTimeMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float
): () -> Int {
    val state = rememberInterpolatedPlaybackPositionState(
        currentTimeMs = currentTimeMs,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed
    )
    return remember(state) {
        {
            state.renderedPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}

internal fun resolveAnchoredInterpolatedPlaybackPosition(
    externalPositionMs: Long,
    renderedPositionMs: Long,
    isPlaying: Boolean
): Long {
    if (shouldSnapInterpolatedPlaybackPosition(externalPositionMs, renderedPositionMs, isPlaying)) {
        return externalPositionMs
    }
    return if (externalPositionMs > renderedPositionMs) {
        externalPositionMs
    } else {
        renderedPositionMs
    }
}

internal fun shouldSnapInterpolatedPlaybackPosition(
    externalPositionMs: Long,
    renderedPositionMs: Long,
    isPlaying: Boolean,
    snapThresholdMs: Long = InterpolatedPlaybackResyncThresholdMs
): Boolean {
    if (!isPlaying) {
        return true
    }
    return abs(externalPositionMs - renderedPositionMs) >= snapThresholdMs
}

internal fun resolveInterpolatedPlaybackPosition(
    anchorPositionMs: Long,
    anchorRealtimeNanos: Long,
    frameRealtimeNanos: Long,
    playbackSpeed: Float,
    previousRenderedPositionMs: Long,
    backwardToleranceMs: Long = InterpolatedPlaybackBackwardToleranceMs
): Long {
    val elapsedNanos = (frameRealtimeNanos - anchorRealtimeNanos).coerceAtLeast(0L)
    val predictedDeltaMs = (
        (elapsedNanos / 1_000_000.0) * playbackSpeed.coerceAtLeast(0f)
        ).toLong()
    val predictedPositionMs = anchorPositionMs + predictedDeltaMs
    val backwardDeltaMs = previousRenderedPositionMs - predictedPositionMs
    return if (backwardDeltaMs in 1..backwardToleranceMs) {
        previousRenderedPositionMs
    } else {
        predictedPositionMs
    }
}
