package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos

internal data class HostScrollPosition(
    val index: Int,
    val offset: Int,
    val key: String? = null
)

internal fun LazyGridState.captureHostScrollPosition(): HostScrollPosition {
    val index = firstVisibleItemIndex
    return HostScrollPosition(
        index = index,
        offset = firstVisibleItemScrollOffset,
        key = layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.key as? String
    )
}

internal fun LazyListState.captureHostScrollPosition(): HostScrollPosition {
    val index = firstVisibleItemIndex
    return HostScrollPosition(
        index = index,
        offset = firstVisibleItemScrollOffset,
        key = layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == index }
            ?.key as? String
    )
}

internal suspend fun LazyGridState.restoreHostScrollPosition(
    position: HostScrollPosition,
    resolvedIndex: Int? = null
) {
    val requestedIndex = resolvedIndex ?: position.index
    val itemCount = awaitHostItemCount(requestedIndex) { layoutInfo.totalItemsCount }
    if (itemCount <= 0) return
    val safeIndex = requestedIndex.coerceAtMost(itemCount - 1)
    scrollToItem(
        index = safeIndex,
        scrollOffset = if (safeIndex == requestedIndex) position.offset else 0
    )
}

internal suspend fun LazyListState.restoreHostScrollPosition(
    position: HostScrollPosition,
    resolvedIndex: Int? = null
) {
    val requestedIndex = resolvedIndex ?: position.index
    val itemCount = awaitHostItemCount(requestedIndex) { layoutInfo.totalItemsCount }
    if (itemCount <= 0) return
    val safeIndex = requestedIndex.coerceAtMost(itemCount - 1)
    scrollToItem(
        index = safeIndex,
        scrollOffset = if (safeIndex == requestedIndex) position.offset else 0
    )
}

private suspend fun awaitHostItemCount(
    requestedIndex: Int,
    itemCount: () -> Int
): Int {
    var count = itemCount()
    var attempts = 0
    while (count <= requestedIndex && attempts < HOST_SCROLL_RESTORE_MAX_FRAMES) {
        withFrameNanos { }
        count = itemCount()
        attempts++
    }
    return count
}

private const val HOST_SCROLL_RESTORE_MAX_FRAMES = 60
