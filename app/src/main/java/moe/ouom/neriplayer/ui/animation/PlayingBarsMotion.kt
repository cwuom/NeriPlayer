package moe.ouom.neriplayer.ui.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

private const val NanosPerMilli = 1_000_000L
internal const val PLAYING_BARS_SETTLE_DURATION_MILLIS = 180

/** 单条指示条的往返节奏, 对应 infiniteRepeatable(tween(durationMillis, delayMillis), RepeatMode.Reverse) */
internal data class PlayingBarSpec(
    val from: Float,
    val to: Float,
    val durationMillis: Int,
    val delayMillis: Int = 0
)

/** 与 Compose 往返 tween 同一条曲线, 只是由调用方决定在哪一帧取样 */
internal fun reverseRepeatingTweenValue(playTimeNanos: Long, spec: PlayingBarSpec): Float {
    if (playTimeNanos <= 0L || spec.durationMillis <= 0) return spec.from
    val iterationNanos = (spec.delayMillis + spec.durationMillis) * NanosPerMilli
    val repeats = playTimeNanos / iterationNanos
    val repetitionNanos = if (repeats % 2L == 0L) {
        playTimeNanos - repeats * iterationNanos
    } else {
        (repeats + 1L) * iterationNanos - playTimeNanos
    }
    val durationNanos = spec.durationMillis * NanosPerMilli
    val activeNanos = (repetitionNanos - spec.delayMillis * NanosPerMilli).coerceIn(0L, durationNanos)
    val fraction = FastOutSlowInEasing.transform(activeNanos / durationNanos.toFloat())
    return spec.from + (spec.to - spec.from) * fraction
}

internal fun settlePlayingBarValue(from: Float, flat: Float, elapsedNanos: Long): Float {
    val fraction = (elapsedNanos.toFloat() / (PLAYING_BARS_SETTLE_DURATION_MILLIS * NanosPerMilli))
        .coerceIn(0f, 1f)
    return from + (flat - from) * FastOutSlowInEasing.transform(fraction)
}

@Stable
internal class PlayingBarsState(initial: List<Float>) {
    private val heights = initial.map { mutableFloatStateOf(it) }

    val size: Int get() = heights.size

    /** 只应在绘制阶段读取, 这样每帧只失效绘制而不触发重组和重新布局 */
    operator fun get(index: Int): Float = heights[index].floatValue

    internal operator fun set(index: Int, value: Float) {
        heights[index].floatValue = value
    }

    internal fun snapshot(): FloatArray = FloatArray(heights.size) { heights[it].floatValue }
}

/**
 * 列表里的播放指示条: 播放时按环境帧格最多 60 fps 推进, 暂停后缓回平直再停止请求帧,
 * 取代每个 vsync 都重组并重新布局的 InfiniteTransition
 */
@Composable
internal fun rememberPlayingBarsState(
    specs: List<PlayingBarSpec>,
    flatValue: Float,
    animate: Boolean
): PlayingBarsState {
    val state = remember(specs) {
        PlayingBarsState(specs.map { if (animate) it.from else flatValue })
    }
    val currentFlatValue by rememberUpdatedState(flatValue)
    // 与 InfiniteTransition 一样从首帧起算相位, 暂停期间不清零, 恢复时接着原节奏
    val clock = remember(specs) { PlayingBarsClock() }
    LaunchedEffect(state, animate) {
        if (animate) {
            var lastRenderedNanos = 0L
            while (true) {
                withInfiniteAnimationFrameNanos { frameNanos ->
                    if (clock.startNanos == 0L) clock.startNanos = frameNanos
                    if (isNewAmbientFrameSlot(lastRenderedNanos, frameNanos, AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS)) {
                        lastRenderedNanos = frameNanos
                        val playTimeNanos = frameNanos - clock.startNanos
                        specs.forEachIndexed { index, spec ->
                            state[index] = reverseRepeatingTweenValue(playTimeNanos, spec)
                        }
                    }
                }
            }
        } else {
            val from = state.snapshot()
            if (from.all { it == currentFlatValue }) return@LaunchedEffect
            var settleStartNanos = 0L
            var settled = false
            while (!settled) {
                withFrameNanos { frameNanos ->
                    if (settleStartNanos == 0L) settleStartNanos = frameNanos
                    val elapsedNanos = frameNanos - settleStartNanos
                    from.forEachIndexed { index, value ->
                        state[index] = settlePlayingBarValue(value, currentFlatValue, elapsedNanos)
                    }
                    settled = elapsedNanos >= PLAYING_BARS_SETTLE_DURATION_MILLIS * NanosPerMilli
                }
            }
        }
    }
    return state
}

private class PlayingBarsClock {
    var startNanos = 0L
}
