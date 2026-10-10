package moe.ouom.neriplayer.ui.screen.playlist.reorder

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.burnoutcrew.reorderable.ItemPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlaylistReorderStateTest {
    private var layout: LazyListLayoutInfo = rows((0 until 10).map { "k$it" })
    private var firstVisibleIndex = 0
    private var firstVisibleOffset = 0
    private var canScrollBackward = true
    private var canScrollForward = true
    private var bottomControls: Rect? = null
    private var overlayAvailable = true
    private val blockedTargets = mutableSetOf<Any>()
    private val moves = mutableListOf<Pair<ItemPosition, ItemPosition>>()
    private val dragEnds = mutableListOf<Pair<Int, Int>>()
    private val scrolled = mutableListOf<Float>()

    @Test
    fun `drag starts only for a visible row under an active pointer`() = runTest {
        withState { state, _ ->
            state.updateListOrigin(Offset(0f, 100f))
            state.startDrag("k2")

            assertNull(state.draggingItemKey)

            state.updatePointer(Offset(0f, 250f))
            state.startDrag("missing")

            assertNull(state.draggingItemKey)

            state.startDrag("k2")
            state.startDrag("k3")

            assertEquals("k2", state.draggingItemKey)
            assertEquals(2, state.draggingItemIndex)
            assertEquals(0f, state.draggingItemTop)
            assertEquals(Offset(0f, 400f), state.itemPositionInRoot("k3"))
            assertNull(state.itemPositionInRoot("missing"))

            state.endDrag()
            state.endDrag()

            assertNull(state.draggingItemKey)
            assertEquals(listOf(2 to 2), dragEnds)
        }
    }

    @Test
    fun `dragged row follows the pointer and is clamped only without an overlay`() = runTest {
        withState { state, _ ->
            state.updatePointer(Offset(0f, 250f))
            state.startDrag("k2")
            state.updatePointer(Offset(0f, 2000f))

            assertEquals(1750f, state.draggingItemTop)

            overlayAvailable = false

            assertEquals(700f, state.draggingItemTop)

            state.updatePointer(null)

            assertEquals(-200f, state.draggingItemTop)
        }
    }

    @Test
    fun `crossing the next row moves once and settles when the new order is laid out`() = runTest {
        withState { state, _ ->
            state.updatePointer(Offset(0f, 250f))
            state.startDrag("k2")
            frames(2)

            state.updatePointer(Offset(0f, 420f))
            frames(2)

            assertEquals(listOf(position(2, "k2") to position(3, "k3")), moves)

            layout = rows(listOf("k0", "k1", "k3", "k2", "k4", "k5", "k6", "k7", "k8", "k9"))
            frames(3)
            state.endDrag()

            assertEquals(1, moves.size)
            assertEquals(listOf(2 to 3), dragEnds)
            assertTrue(scrolled.isEmpty())
        }
    }

    @Test
    fun `unsettled move is not repeated for the same target but a new target moves again`() = runTest {
        withState { state, _ ->
            state.updatePointer(Offset(0f, 250f))
            state.startDrag("k2")
            frames(1)
            state.updatePointer(Offset(0f, 420f))

            frames(40)

            assertEquals(listOf(position(2, "k2") to position(3, "k3")), moves)

            state.updatePointer(Offset(0f, 520f))
            frames(2)
            state.endDrag()

            assertEquals(position(2, "k2") to position(4, "k4"), moves.last())
            assertEquals(2, moves.size)
        }
    }

    @Test
    fun `bottom edge drag targets the furthest allowed row and scrolls forward`() = runTest {
        bottomControls = Rect(0f, 900f, 400f, 1100f)
        blockedTargets += "k7"
        withState { state, listState ->
            state.updateListOrigin(Offset(0f, 100f))
            state.updatePointer(Offset(0f, 250f))
            state.startDrag("k2")
            frames(1)

            state.updatePointer(Offset(0f, 1010f))
            frames(3)
            state.endDrag()

            assertEquals(position(2, "k2") to position(6, "k6"), moves.first())
            assertTrue(scrolled.isNotEmpty())
            assertTrue(scrolled.all { it > 0f })
            verify(listState, never()).requestScrollToItem(anyInt(), anyInt())
        }
    }

    @Test
    fun `edge drag does not scroll when the list cannot scroll further`() = runTest {
        canScrollForward = false
        withState { state, _ ->
            state.updatePointer(Offset(0f, 250f))
            state.startDrag("k2")
            frames(1)

            state.updatePointer(Offset(0f, 1010f))
            frames(3)
            state.endDrag()

            assertEquals(position(2, "k2") to position(9, "k9"), moves.first())
            assertTrue(scrolled.isEmpty())
        }
    }

    @Test
    fun `top edge drag targets the first visible row and keeps the scroll anchor`() = runTest {
        layout = rows((0 until 10).map { "k$it" }, firstOffset = -100)
        firstVisibleIndex = 1
        firstVisibleOffset = 30
        withState { state, listState ->
            state.updatePointer(Offset(0f, 450f))
            state.startDrag("k5")
            frames(1)

            state.updatePointer(Offset(0f, 10f))
            frames(1)

            assertEquals(listOf(position(5, "k5") to position(1, "k1")), moves)
            verify(listState).requestScrollToItem(1, 30)
            assertTrue(scrolled.isNotEmpty())
            assertTrue(scrolled.all { it < 0f })

            firstVisibleOffset = 12
            frames(1)
            state.updatePresentedOrder { key -> if (key == "k5") 1 else 9 }
            state.updatePresentedOrder { 5 }

            verify(listState).requestScrollToItem(1, 12)
            state.endDrag()
            state.updatePresentedOrder { 1 }
            verify(listState, times(2)).requestScrollToItem(anyInt(), anyInt())
        }
    }

    @Test
    fun `top edge drag does not scroll backward at the start of the list`() = runTest {
        canScrollBackward = false
        withState { state, _ ->
            state.updatePointer(Offset(0f, 450f))
            state.startDrag("k4")
            frames(1)

            state.updatePointer(Offset(0f, 10f))
            frames(2)
            state.endDrag()

            assertEquals(position(4, "k4") to position(0, "k0"), moves.first())
            assertTrue(scrolled.isEmpty())
        }
    }

    @Test
    fun `drag ending after its row left the viewport reports the last known index`() = runTest {
        withState { state, _ ->
            state.updatePointer(Offset(0f, 250f))
            state.startDrag("k2")
            frames(1)
            state.updatePointer(Offset(0f, 420f))
            frames(2)

            layout = rows(listOf("k5", "k6", "k7"), firstIndex = 5)
            frames(2)

            assertNull(state.draggingItemIndex)
            assertEquals(0f, state.draggingItemTop)

            state.endDrag()

            assertEquals(listOf(2 to 2), dragEnds)
        }
    }

    @Test
    fun `horizontal reversed or pointerless frames never reorder rows`() = runTest {
        withState { state, _ ->
            state.updatePointer(Offset(0f, 250f))
            state.startDrag("k2")
            frames(1)
            state.updatePointer(Offset(0f, 420f))
            layout = rows((0 until 10).map { "k$it" }, orientation = Orientation.Horizontal)
            frames(2)
            layout = rows((0 until 10).map { "k$it" }, reverseLayout = true)
            frames(2)
            layout = rows((0 until 10).map { "k$it" })
            state.updatePointer(null)
            frames(2)
            state.endDrag()

            assertTrue(moves.isEmpty())
            assertEquals(listOf(2 to 2), dragEnds)
        }
    }

    private suspend fun TestScope.withState(
        block: suspend TestScope.(LocalPlaylistReorderState, LazyListState) -> Unit
    ) {
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + VirtualFrameClock(testScheduler) + Job()
        )
        val listState = mock(LazyListState::class.java)
        `when`(listState.layoutInfo).thenAnswer { layout }
        `when`(listState.firstVisibleItemIndex).thenAnswer { firstVisibleIndex }
        `when`(listState.firstVisibleItemScrollOffset).thenAnswer { firstVisibleOffset }
        `when`(listState.canScrollBackward).thenAnswer { canScrollBackward }
        `when`(listState.canScrollForward).thenAnswer { canScrollForward }
        val scrollScope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float {
                scrolled += pixels
                return pixels
            }
        }
        doAnswer { invocation ->
            val scrollBlock = invocation.getArgument<suspend ScrollScope.() -> Unit>(1)
            @Suppress("UNCHECKED_CAST")
            val completion = invocation.rawArguments.last() as Continuation<Unit>
            scrollBlock.startCoroutine(scrollScope, completion)
            COROUTINE_SUSPENDED
        }.`when`(listState).scroll(any(MutatePriority::class.java) ?: MutatePriority.UserInput, any() ?: {})
        val state = LocalPlaylistReorderState(
            listState = listState,
            scope = scope,
            scrollThreshold = 48f,
            scrollSpeed = 1000f,
            bottomControlBoundsInRoot = { bottomControls },
            dragOverlayAvailable = { overlayAvailable },
            onMove = { from, to -> moves += from to to },
            canDragOver = { target, _ -> target.key !in blockedTargets },
            onDragEnd = { start, end -> dragEnds += start to end }
        )
        try {
            block(state, listState)
        } finally {
            scope.cancel()
        }
    }

    private fun TestScope.frames(count: Int) {
        runCurrent()
        repeat(count) {
            advanceTimeBy(FRAME_MS)
            runCurrent()
        }
    }

    private fun position(index: Int, key: Any) = ItemPosition(index, key)

    private data class Row(
        override val index: Int,
        override val key: Any,
        override val offset: Int,
        override val size: Int = ROW_HEIGHT
    ) : LazyListItemInfo

    private class Layout(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val orientation: Orientation,
        override val reverseLayout: Boolean
    ) : LazyListLayoutInfo {
        override val viewportStartOffset: Int = 0
        override val viewportEndOffset: Int = VIEWPORT_HEIGHT
        override val totalItemsCount: Int = 10
        override val viewportSize: IntSize = IntSize(400, VIEWPORT_HEIGHT)
    }

    private fun rows(
        keys: List<String>,
        firstIndex: Int = 0,
        firstOffset: Int = 0,
        orientation: Orientation = Orientation.Vertical,
        reverseLayout: Boolean = false
    ): LazyListLayoutInfo = Layout(
        visibleItemsInfo = keys.mapIndexed { position, key ->
            Row(firstIndex + position, key, firstOffset + position * ROW_HEIGHT)
        },
        orientation = orientation,
        reverseLayout = reverseLayout
    )

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
        const val ROW_HEIGHT = 100
        const val VIEWPORT_HEIGHT = 1000
    }
}
