package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.player.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerQueueEditOwnerTest {
    private val first = song(1, "First")
    private val second = song(2, "Second")
    private val third = song(3, "Third")
    private val fourth = song(4, "Fourth")

    @Test
    fun `replacing current with an earlier queue entry removes the duplicate and follows the song`() {
        val edit = PlayerQueueEditOwner.replaceCurrent(queue(listOf(first, second, third), 2), first)

        assertEquals(listOf(second, first), edit?.queue?.playlist)
        assertEquals(1, edit?.queue?.currentIndex)
        assertEquals(0, edit?.existingIndex)
    }

    @Test
    fun `replacing current with a new song keeps queue length and selected slot`() {
        val edit = PlayerQueueEditOwner.replaceCurrent(queue(listOf(first, second, third), 1), fourth)

        assertEquals(listOf(first, fourth, third), edit?.queue?.playlist)
        assertEquals(1, edit?.queue?.currentIndex)
        assertEquals(-1, edit?.existingIndex)
        assertNull(PlayerQueueEditOwner.replaceCurrent(queue(listOf(first), -1), fourth))
    }

    @Test
    fun `replacing current with an existing later song or itself never leaves a duplicate`() {
        val original = queue(listOf(first, second, third), 0)
        val later = PlayerQueueEditOwner.replaceCurrent(original, third)
        val same = PlayerQueueEditOwner.replaceCurrent(original, first)

        assertEquals(listOf(third, second), later?.queue?.playlist)
        assertEquals(0, later?.queue?.currentIndex)
        assertEquals(listOf(first, second, third), same?.queue?.playlist)
        assertEquals(0, same?.queue?.currentIndex)
    }

    @Test
    fun `moving a row preserves the selected song and rejects no-op or invalid edits`() {
        val original = queue(listOf(first, second, third), 1)

        val moved = PlayerQueueEditOwner.move(original, 0, 2)
        assertEquals(listOf(second, third, first), moved?.playlist)
        assertEquals(0, moved?.currentIndex)
        assertNull(PlayerQueueEditOwner.move(original, 1, 1))
        assertNull(PlayerQueueEditOwner.move(original, 3, 0))
        assertNull(PlayerQueueEditOwner.move(queue(listOf(first), 0), 0, 0))
        assertEquals(listOf(first, second, third), original.playlist)
    }

    @Test
    fun `removing the current row chooses continuation only while transport remains active`() {
        val original = queue(listOf(first, second, third), 1)
        val edit = PlayerQueueEditOwner.remove(original, 1)!!

        assertEquals(second, edit.removedSong)
        assertEquals(listOf(first, third), edit.queue.playlist)
        assertEquals(1, edit.queue.currentIndex)
        assertEquals(RemovedQueuePlaybackAction.PLAY_NEXT, PlayerQueueEditOwner.removalPlaybackAction(edit, true))
        assertEquals(RemovedQueuePlaybackAction.STOP, PlayerQueueEditOwner.removalPlaybackAction(edit, false))
        assertEquals(listOf(first, second, third), original.playlist)
    }

    @Test
    fun `removing another row keeps playback and removing the last row stops`() {
        val original = queue(listOf(first, second, third), 2)
        val nonCurrent = PlayerQueueEditOwner.remove(original, 0)!!
        assertEquals(RemovedQueuePlaybackAction.KEEP_PLAYING, PlayerQueueEditOwner.removalPlaybackAction(nonCurrent, true))
        assertEquals(1, nonCurrent.queue.currentIndex)

        val empty = PlayerQueueEditOwner.remove(queue(listOf(first), 0), 0)!!
        assertEquals(RemovedQueuePlaybackAction.STOP, PlayerQueueEditOwner.removalPlaybackAction(empty, true))
        assertEquals(-1, empty.queue.currentIndex)
        assertNull(PlayerQueueEditOwner.remove(original, 3))
    }

    @Test
    fun `reorder retains latest song metadata and follows the current song`() {
        val updatedSecond = second.copy(name = "Updated")
        val original = queue(listOf(first, updatedSecond, third), 1)

        val reordered = PlayerQueueEditOwner.reorder(
            current = original,
            requestedQueue = listOf(third, first, second),
            requestedIndex = 0,
            currentSong = updatedSecond,
        )

        assertEquals(listOf(third, first, updatedSecond), reordered?.playlist)
        assertEquals(2, reordered?.currentIndex)
        assertNull(PlayerQueueEditOwner.reorder(original, listOf(first, first, third), 0, updatedSecond))
    }

    @Test
    fun `reorder falls back to stored selection when no current song is supplied`() {
        val reordered = PlayerQueueEditOwner.reorder(
            current = queue(listOf(first, second), 0),
            requestedQueue = listOf(second, first),
            requestedIndex = -1,
            currentSong = null,
        )

        assertEquals(listOf(second, first), reordered?.playlist)
        assertEquals(1, reordered?.currentIndex)
    }

    @Test
    fun `insert next moves an existing occurrence without changing the selected song`() {
        val original = queue(listOf(first, second, third), 1)
        val edit = PlayerQueueEditOwner.insertNext(original, first, second)

        assertEquals(listOf(second, first, third), edit?.queue?.playlist)
        assertEquals(0, edit?.queue?.currentIndex)
        assertEquals(0, edit?.existingIndex)
        assertEquals(1, edit?.insertedIndex)
        assertNull(PlayerQueueEditOwner.insertNext(PlayerQueueSnapshot.EMPTY, first, null))
    }

    @Test
    fun `insert next places a new song after current and falls back for stale current identity`() {
        val original = queue(listOf(first, second, third), 0)
        val inserted = PlayerQueueEditOwner.insertNext(original, fourth, song(99, "Missing"))

        assertEquals(listOf(first, fourth, second, third), inserted?.queue?.playlist)
        assertEquals(0, inserted?.queue?.currentIndex)
        assertEquals(1, inserted?.insertedIndex)
        assertEquals(-1, inserted?.existingIndex)
    }

    @Test
    fun `insert next relocates a later duplicate to immediately after current`() {
        val original = queue(listOf(first, second, third, fourth), 0)
        val edit = PlayerQueueEditOwner.insertNext(original, fourth, null)

        assertEquals(listOf(first, fourth, second, third), edit?.queue?.playlist)
        assertEquals(0, edit?.queue?.currentIndex)
        assertEquals(3, edit?.existingIndex)
        assertEquals(1, edit?.insertedIndex)
    }

    @Test
    fun `insert end moves duplicates and new songs to the tail`() {
        val original = queue(listOf(first, second, third), 1)
        val existing = PlayerQueueEditOwner.insertEnd(original, first, second)
        val added = PlayerQueueEditOwner.insertEnd(original, fourth, second)

        assertEquals(listOf(second, third, first), existing?.queue?.playlist)
        assertEquals(0, existing?.queue?.currentIndex)
        assertEquals(listOf(first, second, third, fourth), added?.queue?.playlist)
        assertEquals(1, added?.queue?.currentIndex)
        assertNull(PlayerQueueEditOwner.insertEnd(PlayerQueueSnapshot.EMPTY, first, null))
    }

    @Test
    fun `insert placement routes to the requested queue position`() {
        val original = queue(listOf(first, second), 0)

        assertEquals(
            listOf(first, third, second),
            PlayerQueueEditOwner.insert(original, third, first, QueueInsertPlacement.NEXT)?.queue?.playlist,
        )
        assertEquals(
            listOf(first, second, third),
            PlayerQueueEditOwner.insert(original, third, first, QueueInsertPlacement.END)?.queue?.playlist,
        )
    }

    @Test
    fun `remote queue selection clamps out of range and accepts an empty queue`() {
        assertEquals(0, PlayerQueueEditOwner.remote(listOf(first, second), -8).currentIndex)
        assertEquals(1, PlayerQueueEditOwner.remote(listOf(first, second), 8).currentIndex)
        assertEquals(-1, PlayerQueueEditOwner.remote(emptyList(), 8).currentIndex)
    }

    @Test
    fun `index remapping rejects stale indices without selecting another song`() {
        assertEquals(-1, resolveQueueCurrentIndexAfterMove(2, 0, 1, 0))
        assertEquals(-1, resolveQueueCurrentIndexAfterMove(3, 0, 1, 3))
        assertEquals(1, resolveQueueCurrentIndexAfterMove(1, -1, 2, 3))
        assertEquals(1, resolveQueueCurrentIndexAfterMove(1, 0, 3, 3))
        assertEquals(-1, resolveQueueCurrentIndexAfterRemoval(3, 0, 3))
        assertEquals(1, resolveQueueCurrentIndexAfterRemoval(1, 3, 3))
        assertEquals(-1, resolveQueueCurrentIndexAfterRemoval(0, 0, 1))
    }

    @Test
    fun `moving an unrelated row outside the current interval keeps the same selection`() {
        assertEquals(2, resolveQueueCurrentIndexAfterMove(2, 0, 1, 3))
        assertEquals(2, resolveQueueCurrentIndexAfterMove(2, 1, 0, 3))
        assertEquals(0, resolveQueueCurrentIndexAfterMove(0, 2, 1, 3))
        assertEquals(0, resolveQueueCurrentIndexAfterMove(1, 0, 2, 3))
        assertEquals(2, resolveQueueCurrentIndexAfterMove(1, 2, 0, 3))
    }

    @Test
    fun `reorder index keeps submitted selection when the current song is unavailable`() {
        val songs = listOf(first, second, third)

        assertEquals(-1, resolveQueueCurrentIndexAfterReorder(emptyList(), null, 0, 0))
        assertEquals(1, resolveQueueCurrentIndexAfterReorder(songs, null, 1, 0))
        assertEquals(2, resolveQueueCurrentIndexAfterReorder(songs, song(99, "Missing"), -1, 8))
        assertEquals(0, resolveQueueCurrentIndexAfterReorder(songs, first, 0, 2))
        assertEquals(2, resolveQueueCurrentIndexAfterReorder(songs, third, 0, 0))
        assertEquals(1, resolveQueueCurrentIndexAfterReorder(songs, second, -1, 0))
    }

    private fun queue(songs: List<SongItem>, index: Int): PlayerQueueSnapshot =
        PlayerQueueSnapshot.from(songs, index)

    private fun song(id: Long, name: String): SongItem = SongItem(
        id = id,
        name = name,
        artist = "Artist",
        album = "Album",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null,
    )
}
