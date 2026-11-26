package moe.ouom.neriplayer.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import moe.ouom.neriplayer.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 底栏重复点击当前 Tab 的一次性事件。
 *
 * 不重放：页面因关闭二级页重新进入组合时，不能把触发关闭的那次点击再当成「回到顶部」。
 */
@Stable
class MainTabReselectRequests {
    private val mutableEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    internal val events: SharedFlow<Unit> = mutableEvents.asSharedFlow()

    internal fun dispatch() {
        mutableEvents.tryEmit(Unit)
    }
}

/** 按 Tab 路由分发重复点击，宿主各自只订阅自己的那一路 */
@Stable
internal class MainTabReselectDispatcher {
    private val requestsByRoute = mutableMapOf<String, MainTabReselectRequests>()

    fun requestsFor(route: String): MainTabReselectRequests =
        requestsByRoute.getOrPut(route) { MainTabReselectRequests() }

    fun dispatch(route: String) {
        requestsFor(route).dispatch()
    }
}

/**
 * 只有 Tab 已选中、导航已落定且没有待切换目标时才算重复点击；
 * 否则仍走切换逻辑，避免 navigate(restoreState) 把刚离开的二级页恢复回来。
 */
internal fun isMainTabReselect(
    selectedRoute: String,
    currentRoute: String?,
    pendingRoute: String?,
    targetRoute: String
): Boolean = pendingRoute == null &&
    selectedRoute == targetRoute &&
    currentRoute == targetRoute

@Composable
internal fun MainTabReselectEffect(
    requests: MainTabReselectRequests?,
    onReselect: suspend () -> Unit
) {
    val latestOnReselect by rememberUpdatedState(onReselect)
    LaunchedEffect(requests) {
        // 连点时以最后一次为准，打断上一次未完成的回顶动画
        requests?.events?.collectLatest { latestOnReselect() }
    }
}

// 固定时长的 ease-out 回顶；每帧重新估算剩余距离，第 0 项进入布局后改用精确值
internal const val SCROLL_TO_TOP_DURATION_MS = 380f
private const val SCROLL_TO_TOP_SETTLE_PX = 0.5f
// 超过约两屏时先瞬移，避免一帧内组合大量条目
private const val SCROLL_TO_TOP_JUMP_SCREENS = 2

/**
 * 到顶部的剩余像素。第 0 项已布局时用它的偏移得到精确值；
 * 否则按可见行的平均高度估算，估算误差会在后续帧里随布局更新被修正。
 */
internal fun estimateDistanceToTop(
    firstItemOffset: Int?,
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
    itemsPerLine: Int,
    averageLineSize: Float
): Float = if (firstItemOffset != null) {
    (-firstItemOffset).coerceAtLeast(0).toFloat()
} else {
    firstVisibleScrollOffset + (firstVisibleIndex / itemsPerLine.coerceAtLeast(1)) * averageLineSize
}

/** 本帧应滚动的距离：剩余距离按 ease-out 曲线的剩余比例收缩，时长用尽时一次走完 */
internal fun scrollToTopFrameStep(
    remaining: Float,
    elapsedBeforeMs: Float,
    elapsedAfterMs: Float,
    durationMs: Float = SCROLL_TO_TOP_DURATION_MS
): Float {
    val before = (durationMs - elapsedBeforeMs).coerceAtLeast(0f)
    if (before <= 0f) return remaining
    val after = (durationMs - elapsedAfterMs).coerceAtLeast(0f)
    val keep = after / before
    return remaining * (1f - keep * keep * keep)
}

private suspend fun ScrollableState.glideToTop(distanceToTop: () -> Float) {
    scroll {
        var elapsedMs = 0f
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val remaining = distanceToTop()
            if (remaining <= SCROLL_TO_TOP_SETTLE_PX) break
            val frameNanos = withFrameNanos { it }
            val nextElapsedMs = elapsedMs + (frameNanos - lastFrameNanos) / 1_000_000f
            lastFrameNanos = frameNanos
            val step = scrollToTopFrameStep(remaining, elapsedMs, nextElapsedMs)
            elapsedMs = nextElapsedMs
            // 已被顶部边界截住时退出，避免空转
            if (step > 0f && scrollBy(-step) == 0f) break
        }
    }
}

private fun LazyListState.distanceToTop(): Float {
    val items = layoutInfo.visibleItemsInfo
    val averageLineSize = if (items.isEmpty()) 0f else {
        items.sumOf { it.size }.toFloat() / items.size + layoutInfo.mainAxisItemSpacing
    }
    return estimateDistanceToTop(
        firstItemOffset = items.firstOrNull { it.index == 0 }?.offset,
        firstVisibleIndex = firstVisibleItemIndex,
        firstVisibleScrollOffset = firstVisibleItemScrollOffset,
        itemsPerLine = 1,
        averageLineSize = averageLineSize
    )
}

private fun LazyGridState.distanceToTop(): Float {
    val items = layoutInfo.visibleItemsInfo
    val lines = items.groupBy { it.row }
    val averageLineSize = if (lines.isEmpty()) 0f else {
        lines.values.sumOf { line -> line.maxOf { it.size.height } }.toFloat() / lines.size +
            layoutInfo.mainAxisItemSpacing
    }
    val itemsPerLine = lines.values.maxOfOrNull { it.size } ?: 1
    return estimateDistanceToTop(
        firstItemOffset = items.firstOrNull { it.index == 0 }?.offset?.y,
        firstVisibleIndex = firstVisibleItemIndex,
        firstVisibleScrollOffset = firstVisibleItemScrollOffset,
        itemsPerLine = itemsPerLine,
        averageLineSize = averageLineSize
    )
}

/*
 * 不用 animateScrollToItem(0)：它按平均尺寸估算终点，条目高度差异大时会先停在半路，
 * 停顿后再硬跳到顶部。
 */
internal suspend fun LazyListState.scrollToTopForReselect() {
    if (!canScrollBackward) return
    val jumpIndex = layoutInfo.visibleItemsInfo.size * SCROLL_TO_TOP_JUMP_SCREENS
    if (jumpIndex in 1 until firstVisibleItemIndex) scrollToItem(jumpIndex)
    glideToTop { distanceToTop() }
}

internal suspend fun LazyGridState.scrollToTopForReselect() {
    if (!canScrollBackward) return
    val jumpIndex = layoutInfo.visibleItemsInfo.size * SCROLL_TO_TOP_JUMP_SCREENS
    if (jumpIndex in 1 until firstVisibleItemIndex) scrollToItem(jumpIndex)
    glideToTop { distanceToTop() }
}

internal suspend fun ScrollState.scrollToTopForReselect() {
    if (value > 0) {
        animateScrollTo(0, tween(SCROLL_TO_TOP_DURATION_MS.toInt(), easing = FastOutSlowInEasing))
    }
}

/** 程序化滚动不经过 nestedScroll，折叠的顶部栏需要单独展开 */
@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun TopAppBarState.expandForReselect() {
    if (heightOffset != 0f) {
        animate(
            initialValue = heightOffset,
            targetValue = 0f,
            animationSpec = tween(SCROLL_TO_TOP_DURATION_MS.toInt(), easing = FastOutSlowInEasing)
        ) { value, _ ->
            heightOffset = value
        }
    }
    contentOffset = 0f
}

@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun scrollMainTabToTop(
    topAppBarState: TopAppBarState?,
    scrollList: suspend () -> Unit
) = coroutineScope {
    launch { topAppBarState?.expandForReselect() }
    scrollList()
}

/**
 * 点标题栏空白处回到顶部。用 pointerInput 而非 clickable，避免把标题栏内按钮的语义合并掉；
 * 子元素（按钮、搜索框）消费的点击和标题栏拖拽都不会触发。
 */
@Composable
internal fun Modifier.scrollToTopOnTitleBarTap(scrollToTop: suspend () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val latestScrollToTop by rememberUpdatedState(scrollToTop)
    val actionLabel = stringResource(R.string.action_scroll_to_top)
    return this
        .pointerInput(scope) {
            detectTapGestures(onTap = { scope.launch { latestScrollToTop() } })
        }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(actionLabel) {
                    scope.launch { latestScrollToTop() }
                    true
                }
            )
        }
}
