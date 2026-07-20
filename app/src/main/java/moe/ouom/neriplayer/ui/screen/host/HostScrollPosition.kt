package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos

internal data class HostScrollPosition(
    val index: Int,
    val offset: Int
)

internal fun LazyGridState.captureHostScrollPosition(): HostScrollPosition =
    HostScrollPosition(
        index = firstVisibleItemIndex,
        offset = firstVisibleItemScrollOffset
    )

internal fun LazyListState.captureHostScrollPosition(): HostScrollPosition =
    HostScrollPosition(
        index = firstVisibleItemIndex,
        offset = firstVisibleItemScrollOffset
    )

internal suspend fun LazyGridState.restoreHostScrollPosition(position: HostScrollPosition) {
    val itemCount = awaitHostItemCount(position.index) { layoutInfo.totalItemsCount }
    if (itemCount <= 0) return
    val safeIndex = position.index.coerceAtMost(itemCount - 1)
    scrollToItem(
        index = safeIndex,
        scrollOffset = if (safeIndex == position.index) position.offset else 0
    )
}

internal suspend fun LazyListState.restoreHostScrollPosition(position: HostScrollPosition) {
    val itemCount = awaitHostItemCount(position.index) { layoutInfo.totalItemsCount }
    if (itemCount <= 0) return
    val safeIndex = position.index.coerceAtMost(itemCount - 1)
    scrollToItem(
        index = safeIndex,
        scrollOffset = if (safeIndex == position.index) position.offset else 0
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
