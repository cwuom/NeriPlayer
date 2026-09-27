package moe.ouom.neriplayer.core.player.persistence

import org.junit.Assert.assertEquals
import org.junit.Test
import moe.ouom.neriplayer.core.player.model.resolvePlayerQueueDisplayIndices
import moe.ouom.neriplayer.core.player.model.resolvePlayerQueueRestoreOrder
import moe.ouom.neriplayer.core.player.model.resolvePlayerRepeatAllShuffleOrder
import moe.ouom.neriplayer.core.player.model.resolvePlayerSequentialShuffleOrder
import moe.ouom.neriplayer.core.player.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.model.reorderQueueSongsPreservingLatestMetadata
import moe.ouom.neriplayer.data.model.SongItem

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
    fun `queue snapshot keeps its own list and rejects a stale current index`() {
        val input = mutableListOf(testSong(1L, "First"), testSong(2L, "Second"))
        val snapshot = PlayerQueueSnapshot.from(input, currentIndex = 8)
        input.clear()

        assertEquals(listOf(1L, 2L), snapshot.playlist.map { it.id })
        assertEquals(-1, snapshot.currentIndex)
    }

    @Test
    fun `queue snapshot selects only an existing row`() {
        val snapshot = PlayerQueueSnapshot.from(
            listOf(testSong(1L, "First"), testSong(2L, "Second")),
            currentIndex = 0
        )

        assertEquals(1, snapshot.selecting(1).currentIndex)
        assertEquals(-1, snapshot.selecting(3).currentIndex)
        assertEquals(-1, PlayerQueueSnapshot.from(emptyList(), 0).currentIndex)
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
    fun `repeat all shuffle starts a fresh order without replaying the completed song`() {
        val order = resolvePlayerRepeatAllShuffleOrder(
            queueSize = 4,
            completedIndex = 3,
            shuffleQueue = { queue -> queue.reverse() }
        )

        assertEquals(listOf(2, 3, 1, 0), order.queueIndices)
        assertEquals(0, order.currentIndex)
    }

    @Test
    fun `repeat all shuffle changes an otherwise unchanged order when possible`() {
        val order = resolvePlayerRepeatAllShuffleOrder(
            queueSize = 3,
            completedIndex = 2,
            shuffleQueue = {}
        )

        assertEquals(listOf(0, 2, 1), order.queueIndices)
        assertEquals(0, order.currentIndex)
    }

    @Test
    fun `repeat all shuffle returns missing current index for an empty queue`() {
        val order = resolvePlayerRepeatAllShuffleOrder(
            queueSize = 0,
            completedIndex = 0
        )

        assertEquals(emptyList<Int>(), order.queueIndices)
        assertEquals(-1, order.currentIndex)
    }

    @Test
    fun `shuffle restore returns the original queue order and current song position`() {
        val first = testSong(id = 1L, name = "First")
        val second = testSong(id = 2L, name = "Second")
        val third = testSong(id = 3L, name = "Third")
        val restoreOrder = resolvePlayerQueueRestoreOrder(
            restorePlaylist = listOf(first, second, third),
            currentSong = third,
            fallbackIndex = 0
        )

        assertEquals(listOf(first, second, third), restoreOrder?.playlist)
        assertEquals(2, restoreOrder?.currentIndex)
    }

    @Test
    fun `shuffle restore falls back to the captured index when current song is missing`() {
        val first = testSong(id = 1L, name = "First")
        val second = testSong(id = 2L, name = "Second")
        val restoreOrder = resolvePlayerQueueRestoreOrder(
            restorePlaylist = listOf(first, second),
            currentSong = testSong(id = 99L, name = "Missing"),
            fallbackIndex = 1
        )

        assertEquals(listOf(first, second), restoreOrder?.playlist)
        assertEquals(1, restoreOrder?.currentIndex)
    }

    @Test
    fun `shuffle restore returns null for an empty restore queue`() {
        val restoreOrder = resolvePlayerQueueRestoreOrder(
            restorePlaylist = emptyList(),
            currentSong = testSong(id = 1L, name = "Song"),
            fallbackIndex = 0
        )

        assertEquals(null, restoreOrder)
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
    fun `queue move discards a stale current index`() {
        val index = resolveQueueCurrentIndexAfterMove(
            currentIndex = 9,
            fromIndex = 0,
            toIndex = 2,
            queueSize = 3
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

    @Test
    fun `queue removal discards a stale current index`() {
        val index = resolveQueueCurrentIndexAfterRemoval(
            currentIndex = 9,
            removedIndex = 1,
            queueSize = 3
        )

        assertEquals(-1, index)
    }

    @Test
    fun `invalid removal cannot preserve a stale current index`() {
        val index = resolveQueueCurrentIndexAfterRemoval(
            currentIndex = 9,
            removedIndex = -1,
            queueSize = 3
        )

        assertEquals(-1, index)
    }

    @Test
    fun `reorder resolves current song when submitted index is stale`() {
        val current = testSong(2L, "Current")
        val queue = listOf(
            testSong(1L, "First"),
            testSong(3L, "Third"),
            current
        )

        val index = resolveQueueCurrentIndexAfterReorder(
            queue = queue,
            currentSong = current,
            submittedCurrentIndex = 1,
            fallbackCurrentIndex = 0
        )

        assertEquals(2, index)
    }

    @Test
    fun `reorder keeps submitted index when it still points to current song`() {
        val current = testSong(2L, "Current")
        val queue = listOf(
            testSong(1L, "First"),
            current,
            testSong(3L, "Third")
        )

        val index = resolveQueueCurrentIndexAfterReorder(
            queue = queue,
            currentSong = current,
            submittedCurrentIndex = 1,
            fallbackCurrentIndex = 0
        )

        assertEquals(1, index)
    }

    @Test
    fun `reorder uses current song metadata when the submitted list is stale`() {
        val first = testSong(1L, "First")
        val second = testSong(2L, "Second")
        val updatedSecond = second.copy(name = "Updated")

        val reordered = reorderQueueSongsPreservingLatestMetadata(
            currentQueue = listOf(first, updatedSecond),
            requestedQueue = listOf(second, first)
        )

        assertEquals(listOf("Updated", "First"), reordered?.map { it.name })
    }

    @Test
    fun `reorder rejects a different song multiset`() {
        val first = testSong(1L, "First")
        val second = testSong(2L, "Second")

        assertEquals(
            null,
            reorderQueueSongsPreservingLatestMetadata(
                currentQueue = listOf(first, second),
                requestedQueue = listOf(first, first)
            )
        )
    }

    @Test
    fun `an old shuffle order cannot restore a removed song`() {
        val first = testSong(1L, "First")
        val second = testSong(2L, "Second")

        assertEquals(
            null,
            reorderQueueSongsPreservingLatestMetadata(
                currentQueue = listOf(second),
                requestedQueue = listOf(first, second)
            )
        )
    }

    @Test
    fun `reorder preserves distinct occurrences of the same song`() {
        val first = testSong(1L, "First occurrence")
        val second = first.copy(name = "Second occurrence")

        assertEquals(
            listOf(second, first),
            reorderQueueSongsPreservingLatestMetadata(
                currentQueue = listOf(first, second),
                requestedQueue = listOf(second, first)
            )
        )
    }

    @Test
    fun `reorder keeps the latest metadata of the only unmatched occurrence`() {
        val first = testSong(1L, "First occurrence")
        val second = first.copy(name = "Second occurrence")
        val editedFirst = first.copy(name = "Edited first")

        assertEquals(
            listOf(second, editedFirst),
            reorderQueueSongsPreservingLatestMetadata(
                currentQueue = listOf(editedFirst, second),
                requestedQueue = listOf(second, first)
            )
        )
    }

    @Test
    fun `reorder rejects ambiguous changed duplicate occurrences`() {
        val first = testSong(1L, "First occurrence")
        val second = first.copy(name = "Second occurrence")

        assertEquals(
            null,
            reorderQueueSongsPreservingLatestMetadata(
                currentQueue = listOf(first.copy(name = "Edited first"), second.copy(name = "Edited second")),
                requestedQueue = listOf(second, first)
            )
        )
    }

    private fun testSong(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = id,
            durationMs = 180_000L,
            coverUrl = null
        )
    }

}
