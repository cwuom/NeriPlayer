package moe.ouom.neriplayer.ui.screen.host

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class HostBackGesture<S>(
    val targetState: S,
    val progress: Float,
    val segment: Any,
    val primeAtZero: Boolean
)
private data class HostBackCancellation<S>(val restoreState: S)

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
    seekEnabled: Boolean = true,
    previewEasing: Easing = LinearEasing,
    cancelMinimumDurationMillis: Int = 0,
    onPreviewAnimationActiveChanged: (Boolean) -> Unit = {},
    primePreviewAtZero: Boolean = false,
    previewSettleEasing: Easing? = null
): Transition<S> {
    val transitionState = remember { SeekableTransitionState(targetState) }
    val transition = rememberTransition(transitionState, label = label)
    var gesture by remember { mutableStateOf<HostBackGesture<S>?>(null) }
    var cancelledGesture by remember { mutableStateOf<HostBackCancellation<S>?>(null) }
    var previewAnimationActive by remember { mutableStateOf(false) }
    var previewAnimationSegment by remember { mutableStateOf<Any?>(null) }
    val currentTarget by rememberUpdatedState(targetState)
    val currentBackTarget by rememberUpdatedState(backTargetState)
    val currentSeekEnabled by rememberUpdatedState(
        seekEnabled && LocalPredictiveBackPreviewEnabled.current
    )
    val currentOnBack by rememberUpdatedState(onBack)
    val currentPreviewEasing by rememberUpdatedState(previewEasing)
    val currentOnPreviewAnimationActiveChanged by rememberUpdatedState(onPreviewAnimationActiveChanged)

    PredictiveBackHandler(enabled = backEnabled) { events ->
        val backTarget = currentBackTarget
        val restoreTarget = currentTarget
        val seek = currentSeekEnabled
        val segment = Any()
        var primeAtZero: Boolean? = null
        var seeked = false
        cancelledGesture = null
        try {
            events.collect { event ->
                if (seek) {
                    val progress = currentPreviewEasing.transform(event.progress.coerceIn(0f, 1f))
                    if (primeAtZero == null) primeAtZero = primePreviewAtZero && !previewAnimationActive
                    previewAnimationSegment = segment
                    if (!previewAnimationActive) {
                        previewAnimationActive = true
                        currentOnPreviewAnimationActiveChanged(true)
                    }
                    gesture = HostBackGesture(
                        backTarget, progress.coerceIn(0f, 1f), segment, primeAtZero == true
                    )
                    seeked = true
                }
            }
            gesture = null
            currentOnBack(seeked)
        } catch (cancelled: CancellationException) {
            cancelledGesture = if (seeked) HostBackCancellation(restoreTarget) else null
            gesture = null
            throw cancelled
        }
    }

    val activeGesture = gesture
    if (activeGesture != null) {
        if (primePreviewAtZero) {
            LaunchedEffect(activeGesture.segment) {
                coroutineScope {
                    if (activeGesture.primeAtZero) {
                        // 先让布局动画在零进度注册，收尾中重入则继续现有片段
                        val prime = launch(start = CoroutineStart.UNDISPATCHED) {
                            transitionState.seekTo(0f, activeGesture.targetState)
                        }
                        try {
                            repeat(2) { withFrameNanos {} }
                        } finally {
                            prime.cancelAndJoin()
                        }
                    }
                    snapshotFlow {
                        gesture?.takeIf { it.segment === activeGesture.segment }?.progress
                    }.filterNotNull().collectLatest { progress ->
                        transitionState.seekTo(progress, activeGesture.targetState)
                    }
                }
            }
        } else {
            LaunchedEffect(activeGesture) {
                transitionState.seekTo(activeGesture.progress, activeGesture.targetState)
            }
        }
    } else {
        val cancellation = cancelledGesture
        val settlingSegment = previewAnimationSegment
        LaunchedEffect(targetState, cancellation, settlingSegment) {
            val restoreCancelledBack = cancellation != null && cancellation.restoreState == targetState
            if (!restoreCancelledBack && cancelledGesture === cancellation) cancelledGesture = null
            val settlingCancellation = cancelledGesture
            settleHostTransition(
                transitionState,
                transition,
                targetState,
                restoreCancelledBack = restoreCancelledBack,
                cancelMinimumDurationMillis = cancelMinimumDurationMillis,
                previewRetargetEasing = currentPreviewEasing,
                previewSettleEasing = previewSettleEasing.takeIf {
                    previewAnimationActive && targetState == currentBackTarget
                }
            )
            if (gesture == null && previewAnimationSegment === settlingSegment &&
                cancelledGesture === settlingCancellation
            ) {
                cancelledGesture = null
                // 收尾仍沿用预览的空间规格，新手势接管后旧收尾不能关闭它
                if (previewAnimationActive) {
                    previewAnimationActive = false
                    currentOnPreviewAnimationActiveChanged(false)
                }
            }
        }
    }
    return transition
}

private suspend fun <S> settleHostTransition(
    transitionState: SeekableTransitionState<S>,
    transition: Transition<S>,
    targetState: S,
    restoreCancelledBack: Boolean,
    cancelMinimumDurationMillis: Int,
    previewRetargetEasing: Easing,
    previewSettleEasing: Easing?
) {
    // 普通反向要更新共享元素的目标，只有取消预测返回才拖回原来的片段
    if (restoreCancelledBack && transitionState.currentState == targetState &&
        transitionState.targetState != targetState
    ) {
        transitionState.settleCancelledBack(
            targetState, transition.totalDurationNanos, cancelMinimumDurationMillis
        )
    } else if (transitionState.currentState != targetState || transitionState.targetState != targetState) {
        // 预览收尾中再次返回也要减速，重定向会从零开始新的时间线
        val settleSpec = if (previewSettleEasing != null) {
            val continuingPreview = transitionState.targetState == targetState
            val remainingFraction = if (continuingPreview) {
                1f - transitionState.fraction
            } else 1f
            tween<Float>(
                durationMillis = (remainingFraction *
                    transition.totalDurationNanos / 1_000_000L).roundToInt().coerceAtLeast(0),
                easing = if (continuingPreview) previewSettleEasing else previewRetargetEasing
            )
        } else null
        transitionState.animateTo(targetState, animationSpec = settleSpec)
    }
}

internal fun hostBackCancelDurationMillis(
    fraction: Float,
    totalDurationNanos: Long,
    minimumDurationMillis: Int = 0
): Int {
    if (fraction <= 0f || totalDurationNanos <= 0L) return 0
    val duration = (fraction * totalDurationNanos / 1_000_000L).roundToInt()
    return maxOf(duration, minimumDurationMillis)
}

private suspend fun <S> SeekableTransitionState<S>.settleCancelledBack(
    targetState: S,
    totalDurationNanos: Long,
    minimumDurationMillis: Int
) {
    val startFraction = fraction
    coroutineScope {
        animate(
            initialValue = startFraction,
            targetValue = 0f,
            animationSpec = tween(
                hostBackCancelDurationMillis(startFraction, totalDurationNanos, minimumDurationMillis)
            )
        ) { value, _ ->
            launch { seekTo(value) }
        }
    }
    snapTo(targetState)
    // 等恢复目标完成组合后再收尾，让子转场也停在恢复后的状态
    animateTo(targetState)
}
