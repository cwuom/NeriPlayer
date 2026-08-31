package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.model.SongIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistReorderActionsTest {

    @Test
    fun `selected rows move as one block and preserve their order`() {
        val result = moveSelectedItemsToOneBasedPosition(
            items = listOf("a", "b", "c", "d", "e"),
            selectedIndices = setOf(1, 3),
            requestedPosition = 2
        )

        assertEquals(listOf("a", "b", "d", "c", "e"), result)
    }

    @Test
    fun `position is validated after selected rows are removed`() {
        assertEquals(1..4, insertionPositionRange(itemCount = 5, selectedCount = 2))
        assertNull(
            moveSelectedItemsToOneBasedPosition(
                items = listOf(1, 2, 3, 4, 5),
                selectedIndices = setOf(0, 1),
                requestedPosition = 5
            )
        )
    }

    @Test
    fun `insert selection uses the complete source when the visible list is filtered`() {
        val completeSource = listOf("hidden", "selected", "visible", "selected-again")
        val visibleSource = completeSource.filter { it != "hidden" && it != "selected-again" }
        val selectedKeys = setOf("selected", "selected-again")

        assertEquals(
            setOf(1, 3),
            selectedIndicesForPlaylistInsert(
                items = completeSource,
                selectedKeys = selectedKeys,
                keyOf = { it }
            )
        )
        assertEquals(
            setOf(0),
            selectedIndicesForPlaylistInsert(
                items = visibleSource,
                selectedKeys = selectedKeys,
                keyOf = { it }
            )
        )
    }

    @Test
    fun `invalid and duplicate indices do not lose rows`() {
        val result = moveSelectedItemsToOneBasedPosition(
            items = listOf("a", "b", "c"),
            selectedIndices = setOf(-1, 1, 1, 99),
            requestedPosition = 1
        )

        assertEquals(listOf("b", "a", "c"), result)
        assertNull(parseOneBasedInsertionPosition("x", 3, 1))
        assertEquals(3, parseOneBasedInsertionPosition(" 3 ", 3, 1))
    }

    @Test
    fun `auto scroll delta is symmetric and bounded at both viewport edges`() {
        val upward = resolveReorderAutoScrollDelta(
            draggingItemTop = 80f,
            draggingItemHeight = 56,
            viewportStart = 100,
            viewportEnd = 700,
            edgeDistance = 48f,
            maxPerFrame = 12f
        )
        val downward = resolveReorderAutoScrollDelta(
            draggingItemTop = 664f,
            draggingItemHeight = 56,
            viewportStart = 100,
            viewportEnd = 700,
            edgeDistance = 48f,
            maxPerFrame = 12f
        )

        assertEquals(-12f, upward, 0.001f)
        assertEquals(12f, downward, 0.001f)
        assertEquals(
            0f,
            resolveReorderAutoScrollDelta(300f, 56, 100, 700, 48f, 12f),
            0.001f
        )
        assertEquals(kotlin.math.abs(upward), kotlin.math.abs(downward), 0.001f)
    }

    @Test
    fun `auto scroll starts before leaving the viewport and accelerates near the edge`() {
        val insideEdge = resolveReorderAutoScrollDelta(
            draggingItemTop = 130f,
            draggingItemHeight = 56,
            viewportStart = 100,
            viewportEnd = 700,
            edgeDistance = 80f,
            maxPerFrame = 18f
        )
        val pastEdge = resolveReorderAutoScrollDelta(
            draggingItemTop = 90f,
            draggingItemHeight = 56,
            viewportStart = 100,
            viewportEnd = 700,
            edgeDistance = 80f,
            maxPerFrame = 18f
        )

        assert(insideEdge < 0f)
        assert(pastEdge < insideEdge)
        assert(kotlin.math.abs(pastEdge) <= 18f)
    }

    @Test
    fun `offscreen continuation keeps the last direction without exceeding the frame bound`() {
        assertEquals(
            -12f,
            resolveReorderOffscreenContinuation(lastDelta = -12f, maxPerFrame = 18f),
            0.001f
        )
        assertEquals(
            18f,
            resolveReorderOffscreenContinuation(lastDelta = 40f, maxPerFrame = 18f),
            0.001f
        )
        assertEquals(
            0f,
            resolveReorderOffscreenContinuation(lastDelta = Float.NaN, maxPerFrame = 18f),
            0.001f
        )
    }

    @Test
    fun `edge delta is stopped at either logical list boundary`() {
        assertEquals(
            0f,
            gateReorderScrollDelta(
                delta = -8f,
                reverseLayout = false,
                canScrollForward = true,
                canScrollBackward = false
            ),
            0.001f
        )
        assertEquals(
            0f,
            gateReorderScrollDelta(
                delta = 8f,
                reverseLayout = false,
                canScrollForward = false,
                canScrollBackward = true
            ),
            0.001f
        )
    }

    @Test
    fun `reverse layout maps physical edge velocity to the logical direction`() {
        assertEquals(
            -8f,
            gateReorderScrollDelta(
                delta = -8f,
                reverseLayout = true,
                canScrollForward = true,
                canScrollBackward = false
            ),
            0.001f
        )
        assertEquals(
            0f,
            gateReorderScrollDelta(
                delta = -8f,
                reverseLayout = true,
                canScrollForward = false,
                canScrollBackward = true
            ),
            0.001f
        )
    }

    @Test
    fun `dragging top uses the item offset instead of the relative drag delta`() {
        assertEquals(
            332f,
            resolveReorderDraggingItemTop(
                itemOffset = 300,
                itemHeight = 56,
                viewportHeight = 800,
                draggingOffset = 32f,
                reverseLayout = false
            ),
            0.001f
        )
        assertEquals(
            476f,
            resolveReorderDraggingItemTop(
                itemOffset = 300,
                itemHeight = 56,
                viewportHeight = 800,
                draggingOffset = 32f,
                reverseLayout = true
            ),
            0.001f
        )
    }

    @Test
    fun `undo is allowed only while the applied order is unchanged`() {
        val first = SongIdentity(id = 1L, album = "album", mediaUri = null)
        val second = SongIdentity(id = 2L, album = "album", mediaUri = null)

        assertTrue(canUndoPlaylistReorder(listOf(first, second), listOf(first, second)))
        assertTrue(!canUndoPlaylistReorder(listOf(second, first), listOf(first, second)))
    }
}
