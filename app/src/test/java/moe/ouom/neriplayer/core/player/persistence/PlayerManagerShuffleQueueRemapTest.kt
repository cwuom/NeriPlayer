package moe.ouom.neriplayer.core.player.persistence

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.core.player.model.resolvePlayerQueueDisplayIndices
import moe.ouom.neriplayer.core.player.model.resolvePlayerSequentialShuffleOrder

class PlayerManagerQueueOrderTest {

    @Test
    fun `queue display follows the current playlist order`() {
        val displayIndices = resolvePlayerQueueDisplayIndices(
            queueSize = 4
        )

        assertEquals(listOf(0, 1, 2, 3), displayIndices)
    }

    @Test
    fun `queue display is empty for invalid queue size`() {
        val displayIndices = resolvePlayerQueueDisplayIndices(
            queueSize = 0
        )

        assertEquals(emptyList<Int>(), displayIndices)
    }

    @Test
    fun `sequential shuffle keeps current song first then applies shuffled order`() {
        val order = resolvePlayerSequentialShuffleOrder(
            queueSize = 5,
            currentIndex = 2,
            shuffleRemaining = { remaining -> remaining.reverse() }
        )

        assertEquals(listOf(2, 4, 3, 1, 0), order.queueIndices)
        assertEquals(0, order.currentIndex)
    }

    @Test
    fun `sequential shuffle falls back to first song when current index is invalid`() {
        val order = resolvePlayerSequentialShuffleOrder(
            queueSize = 5,
            currentIndex = 8,
            shuffleRemaining = { remaining -> remaining.reverse() }
        )

        assertEquals(listOf(0, 4, 3, 2, 1), order.queueIndices)
        assertEquals(0, order.currentIndex)
    }

    @Test
    fun `sequential shuffle returns missing current index for empty queue`() {
        val order = resolvePlayerSequentialShuffleOrder(
            queueSize = 0,
            currentIndex = 0
        )

        assertEquals(emptyList<Int>(), order.queueIndices)
        assertEquals(-1, order.currentIndex)
    }

    @Test
    fun `queue current index follows dragged current item`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 1,
            fromIndex = 1,
            toIndex = 3,
            queueSize = 5
        )

        assertEquals(3, index)
    }

    @Test
    fun `queue current index shifts when earlier item moves after it`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 2,
            fromIndex = 0,
            toIndex = 3,
            queueSize = 5
        )

        assertEquals(1, index)
    }

    @Test
    fun `queue current index shifts when later item moves before it`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 2,
            fromIndex = 4,
            toIndex = 1,
            queueSize = 5
        )

        assertEquals(3, index)
    }

    @Test
    fun `queue current index survives invalid move`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 2,
            fromIndex = -1,
            toIndex = 1,
            queueSize = 5
        )

        assertEquals(2, index)
    }

    @Test
    fun `queue current index returns missing for empty queue`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 0,
            fromIndex = 0,
            toIndex = 0,
            queueSize = 0
        )

        assertEquals(-1, index)
    }

    @Test
    fun `queue current index stays on next item when current row is removed`() {
        val index = resolveQueueCurrentIndexAfterRemoval(
            currentIndex = 2,
            removedIndex = 2,
            queueSize = 5
        )

        assertEquals(2, index)
    }

    @Test
    fun `queue current index moves to previous item when last current row is removed`() {
        val index = resolveQueueCurrentIndexAfterRemoval(
            currentIndex = 4,
            removedIndex = 4,
            queueSize = 5
        )

        assertEquals(3, index)
    }

    @Test
    fun `queue current index shifts left when an earlier row is removed`() {
        val index = resolveQueueCurrentIndexAfterRemoval(
            currentIndex = 3,
            removedIndex = 1,
            queueSize = 5
        )

        assertEquals(2, index)
    }

    @Test
    fun `queue current index survives invalid removal`() {
        val index = resolveQueueCurrentIndexAfterRemoval(
            currentIndex = 2,
            removedIndex = -1,
            queueSize = 5
        )

        assertEquals(2, index)
    }

    @Test
    fun `queue current index returns missing after removing the only row`() {
        val index = resolveQueueCurrentIndexAfterRemoval(
            currentIndex = 0,
            removedIndex = 0,
            queueSize = 1
        )

        assertEquals(-1, index)
    }

}
