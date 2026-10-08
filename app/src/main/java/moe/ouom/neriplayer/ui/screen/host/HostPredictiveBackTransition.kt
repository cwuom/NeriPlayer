package moe.ouom.neriplayer.ui.screen.host

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class HostBackGesture<S>(val targetState: S, val progress: Float)

/**
 * 宿主页面的导航转场：普通导航沿用原有动画，预测性返回手势按进度拖动同一个转场，
 * 松手后从当前进度完成返回，取消时回弹到原页面
 *
 * [onBack] 的参数表示转场是否已经被手势拖向 [backTargetState]，此时调用方必须让
 * [targetState] 立即变为 [backTargetState]，否则转场会回弹
 */
@Composable
internal fun <S> rememberHostPredictiveBackTransition(
    targetState: S,
    backEnabled: Boolean,
    backTargetState: S,
    onBack: (seekedToBackTarget: Boolean) -> Unit,
    label: String,
    seekEnabled: Boolean = true
): Transition<S> {
    val transitionState = remember { SeekableTransitionState(targetState) }
    val transition = rememberTransition(transitionState, label = label)
    var gesture by remember { mutableStateOf<HostBackGesture<S>?>(null) }
    val currentBackTarget by rememberUpdatedState(backTargetState)
    val currentSeekEnabled by rememberUpdatedState(seekEnabled)
    val currentOnBack by rememberUpdatedState(onBack)

    PredictiveBackHandler(enabled = backEnabled) { events ->
        val backTarget = currentBackTarget
        val seek = currentSeekEnabled
        var seeked = false
        try {
            events.collect { event ->
                if (seek) {
                    gesture = HostBackGesture(backTarget, event.progress.coerceIn(0f, 1f))
                    seeked = true
                }
            }
            gesture = null
            currentOnBack(seeked)
        } catch (cancelled: CancellationException) {
            gesture = null
            throw cancelled
        }
    }

    val activeGesture = gesture
    if (activeGesture != null) {
        LaunchedEffect(activeGesture) {
            transitionState.seekTo(activeGesture.progress, activeGesture.targetState)
        }
    } else {
        LaunchedEffect(targetState) {
            if (transitionState.currentState != targetState) {
                transitionState.animateTo(targetState)
            } else if (transitionState.targetState != targetState) {
                transitionState.settleCancelledBack(targetState, transition.totalDurationNanos)
            }
        }
    }
    return transition
}

internal fun hostBackCancelDurationMillis(fraction: Float, totalDurationNanos: Long): Int =
    (fraction * totalDurationNanos / 1_000_000L).roundToInt()

private suspend fun <S> SeekableTransitionState<S>.settleCancelledBack(
    targetState: S,
    totalDurationNanos: Long
) {
    val startFraction = fraction
    coroutineScope {
        animate(
            initialValue = startFraction,
            targetValue = 0f,
            animationSpec = tween(hostBackCancelDurationMillis(startFraction, totalDurationNanos))
        ) { value, _ ->
            launch { seekTo(value) }
        }
    }
    snapTo(targetState)
}
