package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// 拖进度条时手指常滑出 48dp 触摸区, 过小的阈值会把调进度误判成切页或返回 (#717)
internal val NowPlayingPageSwipeThreshold = 96.dp

internal enum class NowPlayingPageSwipeDirection { Left, Right }

internal fun isNowPlayingPageSwipe(
    totalDragPx: Float,
    thresholdPx: Float,
    direction: NowPlayingPageSwipeDirection
): Boolean = when (direction) {
    NowPlayingPageSwipeDirection.Left -> totalDragPx <= -thresholdPx
    NowPlayingPageSwipeDirection.Right -> totalDragPx >= thresholdPx
}

/**
 * 横向切页手势: 只在抬手时按累计位移判断, 中途回拖或被系统取消都不触发
 */
internal fun Modifier.nowPlayingPageSwipe(
    key: Any?,
    direction: NowPlayingPageSwipeDirection,
    onSwipe: () -> Unit
): Modifier = pointerInput(key, direction) {
    val thresholdPx = NowPlayingPageSwipeThreshold.toPx()
    var totalDragPx = 0f
    detectHorizontalDragGestures(
        onDragStart = { totalDragPx = 0f },
        onDragCancel = { totalDragPx = 0f },
        onDragEnd = {
            val shouldSwipe = isNowPlayingPageSwipe(totalDragPx, thresholdPx, direction)
            totalDragPx = 0f
            if (shouldSwipe) onSwipe()
        },
        onHorizontalDrag = { _, dragAmount -> totalDragPx += dragAmount }
    )
}
