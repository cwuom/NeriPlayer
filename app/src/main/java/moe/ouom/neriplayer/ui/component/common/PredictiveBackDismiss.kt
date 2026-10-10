package moe.ouom.neriplayer.ui.component.common

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Material 全屏界面的预测性返回：缩小到 90%，朝手势方向偏移，四角逐渐变圆 */
internal const val PREDICTIVE_DISMISS_MIN_SCALE = 0.9f
internal const val PREDICTIVE_DISMISS_SHIFT_FRACTION = 1f / 20f
internal const val PREDICTIVE_DISMISS_CANCEL_DURATION_MS = 200
private val PredictiveDismissMaxCorner = 28.dp
// Material 规范：横向偏移为屏宽的 1/20 再减去 8dp，缩小后两侧都留出缝隙
private val PredictiveDismissEdgeMargin = 8.dp

internal data class PredictiveDismissTransform(
    val scale: Float,
    val translationX: Float,
    val cornerRadius: Float
)

internal fun predictiveDismissTransform(
    progress: Float,
    swipeEdge: Int,
    widthPx: Float,
    maxCornerRadiusPx: Float,
    edgeMarginPx: Float
): PredictiveDismissTransform {
    val fraction = progress.coerceIn(0f, 1f)
    val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val maxShift = (widthPx * PREDICTIVE_DISMISS_SHIFT_FRACTION - edgeMarginPx).coerceAtLeast(0f)
    return PredictiveDismissTransform(
        scale = 1f - (1f - PREDICTIVE_DISMISS_MIN_SCALE) * fraction,
        translationX = direction * maxShift * fraction,
        cornerRadius = maxCornerRadiusPx * fraction
    )
}

@Stable
internal class PredictiveDismissState {
    var progress by mutableFloatStateOf(0f)
        internal set
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set

    internal var settleJob: Job? = null

    /** 手势开始后底层界面应按关闭后的样子显示；松手后保持到下次 [reset]，避免退场时又被隐藏 */
    val revealsUnderlying: Boolean
        get() = progress > 0f

    fun reset() {
        settleJob?.cancel()
        progress = 0f
    }
}

@Composable
internal fun rememberPredictiveDismissState(): PredictiveDismissState = remember { PredictiveDismissState() }

/** 只在手势开始和 [PredictiveDismissState.reset] 时变化，调用方读取它不会随拖动进度重组 */
@Composable
internal fun PredictiveDismissState.rememberRevealsUnderlying(): State<Boolean> =
    remember(this) { derivedStateOf { revealsUnderlying } }

/**
 * 手势过程中只更新 [state]，松手后调用 [onDismiss]；取消时把进度动画回 0，
 * 关闭动画沿用调用方原有的退出动画，从松手时的形态接着播放
 */
@Composable
internal fun PredictiveDismissHandler(
    state: PredictiveDismissState,
    enabled: Boolean = true,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    PredictiveBackHandler(enabled = enabled) { events ->
        state.settleJob?.cancel()
        try {
            events.collect { event ->
                state.swipeEdge = event.swipeEdge
                state.progress = event.progress
            }
            currentOnDismiss()
        } catch (cancelled: CancellationException) {
            state.settleJob = scope.launch {
                animate(
                    initialValue = state.progress,
                    targetValue = 0f,
                    animationSpec = tween(PREDICTIVE_DISMISS_CANCEL_DURATION_MS)
                ) { value, _ -> state.progress = value }
            }
            throw cancelled
        }
    }
}

/** 只在绘制层读取进度，拖动手势不会触发重组 */
internal fun Modifier.predictiveDismissTransform(state: PredictiveDismissState): Modifier =
    graphicsLayer {
        val transform = predictiveDismissTransform(
            progress = state.progress,
            swipeEdge = state.swipeEdge,
            widthPx = size.width,
            maxCornerRadiusPx = PredictiveDismissMaxCorner.toPx(),
            edgeMarginPx = PredictiveDismissEdgeMargin.toPx()
        )
        scaleX = transform.scale
        scaleY = transform.scale
        translationX = transform.translationX
        shape = RoundedCornerShape(transform.cornerRadius)
        clip = transform.cornerRadius > 0f
    }
