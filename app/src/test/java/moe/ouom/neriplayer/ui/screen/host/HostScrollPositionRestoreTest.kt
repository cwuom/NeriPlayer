package moe.ouom.neriplayer.ui.screen.host

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class HostScrollPositionRestoreTest {

    @Test
    fun `list capture keeps the string key of the first visible item`() {
        val state = listState(
            firstIndex = 4,
            firstOffset = 18,
            visible = listOf(listItem(3, "row-3"), listItem(4, "row-4"))
        )

        assertEquals(HostScrollPosition(index = 4, offset = 18, key = "row-4"), state.captureHostScrollPosition())
    }

    @Test
    fun `list capture drops keys that are not strings or not visible`() {
        assertEquals(
            HostScrollPosition(index = 2, offset = 0, key = null),
            listState(firstIndex = 2, visible = listOf(listItem(2, 42L))).captureHostScrollPosition()
        )
        assertEquals(
            HostScrollPosition(index = 2, offset = 0, key = null),
            listState(firstIndex = 2, visible = listOf(listItem(1, "row-1"))).captureHostScrollPosition()
        )
    }

    @Test
    fun `grid capture keeps the string key of the first visible item`() {
        val state = gridState(
            firstIndex = 6,
            firstOffset = 9,
            visible = listOf(gridItem(6, "cell-6"), gridItem(7, "cell-7"))
        )

        assertEquals(HostScrollPosition(index = 6, offset = 9, key = "cell-6"), state.captureHostScrollPosition())
    }

    @Test
    fun `grid capture drops keys that are not strings or not visible`() {
        assertEquals(
            HostScrollPosition(index = 1, offset = 0, key = null),
            gridState(firstIndex = 1, visible = listOf(gridItem(1, 7))).captureHostScrollPosition()
        )
        assertEquals(
            HostScrollPosition(index = 1, offset = 0, key = null),
            gridState(firstIndex = 1, visible = emptyList()).captureHostScrollPosition()
        )
    }

    @Test
    fun `list restore scrolls to the saved item when it is already laid out`() = runTest {
        val state = listState(totalItems = listOf(10))

        withFrames { state.restoreHostScrollPosition(HostScrollPosition(index = 3, offset = 12)) }

        verify(state).scrollToItem(3, 12)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `list restore waits for items and prefers the resolved index`() = runTest {
        val state = listState(totalItems = listOf(2, 2, 10))

        withFrames {
            state.restoreHostScrollPosition(HostScrollPosition(index = 1, offset = 30), resolvedIndex = 6)
        }

        verify(state).scrollToItem(6, 30)
        assertEquals(2 * FRAME_MS, currentTime)
    }

    @Test
    fun `list restore clamps to the last item and drops the stale offset`() = runTest {
        val state = listState(totalItems = listOf(4))

        withFrames { state.restoreHostScrollPosition(HostScrollPosition(index = 8, offset = 40)) }

        verify(state).scrollToItem(3, 0)
        assertEquals(MAX_RESTORE_FRAMES * FRAME_MS, currentTime)
    }

    @Test
    fun `list restore gives up when no items appear`() = runTest {
        val state = listState(totalItems = listOf(0))

        withFrames { state.restoreHostScrollPosition(HostScrollPosition(index = 0, offset = 5)) }

        verify(state, never()).scrollToItem(anyInt(), anyInt())
        assertEquals(MAX_RESTORE_FRAMES * FRAME_MS, currentTime)
    }

    @Test
    fun `grid restore uses the resolved index and offset`() = runTest {
        val state = gridState(totalItems = listOf(12))

        withFrames {
            state.restoreHostScrollPosition(HostScrollPosition(index = 2, offset = 16), resolvedIndex = 5)
        }

        verify(state).scrollToItem(5, 16)
    }

    @Test
    fun `grid restore clamps to the loaded items`() = runTest {
        val state = gridState(totalItems = listOf(3))

        withFrames { state.restoreHostScrollPosition(HostScrollPosition(index = 9, offset = 16)) }

        verify(state).scrollToItem(2, 0)
    }

    @Test
    fun `grid restore gives up when no items appear`() = runTest {
        val state = gridState(totalItems = listOf(0))

        withFrames { state.restoreHostScrollPosition(HostScrollPosition(index = 0, offset = 0)) }

        verify(state, never()).scrollToItem(anyInt(), anyInt())
    }

    private suspend fun TestScope.withFrames(block: suspend () -> Unit) {
        withContext(VirtualFrameClock(testScheduler)) { block() }
    }

    private fun listState(
        firstIndex: Int = 0,
        firstOffset: Int = 0,
        visible: List<LazyListItemInfo> = emptyList(),
        totalItems: List<Int> = listOf(visible.size)
    ): LazyListState {
        val layouts = totalItems.map { count ->
            mock(LazyListLayoutInfo::class.java).also { layout ->
                `when`(layout.visibleItemsInfo).thenReturn(visible)
                `when`(layout.totalItemsCount).thenReturn(count)
            }
        }
        val state = mock(LazyListState::class.java)
        `when`(state.firstVisibleItemIndex).thenReturn(firstIndex)
        `when`(state.firstVisibleItemScrollOffset).thenReturn(firstOffset)
        `when`(state.layoutInfo).thenReturn(layouts.first(), *layouts.drop(1).toTypedArray())
        return state
    }

    private fun gridState(
        firstIndex: Int = 0,
        firstOffset: Int = 0,
        visible: List<LazyGridItemInfo> = emptyList(),
        totalItems: List<Int> = listOf(visible.size)
    ): LazyGridState {
        val layouts = totalItems.map { count ->
            mock(LazyGridLayoutInfo::class.java).also { layout ->
                `when`(layout.visibleItemsInfo).thenReturn(visible)
                `when`(layout.totalItemsCount).thenReturn(count)
            }
        }
        val state = mock(LazyGridState::class.java)
        `when`(state.firstVisibleItemIndex).thenReturn(firstIndex)
        `when`(state.firstVisibleItemScrollOffset).thenReturn(firstOffset)
        `when`(state.layoutInfo).thenReturn(layouts.first(), *layouts.drop(1).toTypedArray())
        return state
    }

    private fun listItem(index: Int, key: Any): LazyListItemInfo {
        val item = mock(LazyListItemInfo::class.java)
        `when`(item.index).thenReturn(index)
        `when`(item.key).thenReturn(key)
        return item
    }

    private fun gridItem(index: Int, key: Any): LazyGridItemInfo {
        val item = mock(LazyGridItemInfo::class.java)
        `when`(item.index).thenReturn(index)
        `when`(item.key).thenReturn(key)
        return item
    }

    private class VirtualFrameClock(
        private val scheduler: TestCoroutineScheduler
    ) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            delay(FRAME_MS)
            return onFrame(scheduler.currentTime * 1_000_000L)
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        const val MAX_RESTORE_FRAMES = 60L
    }
}
