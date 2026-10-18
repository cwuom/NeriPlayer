package moe.ouom.neriplayer.ui.screen.nowplaying

import moe.ouom.neriplayer.core.player.queue.identity.QueueSongIdentity
import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueEditOwner
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueDisplayItem
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.data.model.playback.queue.RemovedQueuePlaybackAction
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingQueueRemovalTest {
    private val first = song(1)
    private val second = song(2)
    private val third = song(3)
    private val fourth = song(4)

    @Test
    fun `batch removal keeps unselected rows after earlier indices shift`() {
        val queue = mutableListOf(first, second, third, fourth)
        val entries = buildNowPlayingQueueEntries(queue)

        removeSelectedNowPlayingQueueEntries(
            entries,
            setOf(entries[0].key, entries[2].key),
            queue::removeAt
        )

        assertEquals(listOf(second, fourth), queue)
    }

    @Test
    fun `batch removal follows source queue indices rather than display positions`() {
        val queue = mutableListOf(first, second, third)
        val entries = buildNowPlayingQueueEntriesFromDisplayItems(
            listOf(
                PlayerQueueDisplayItem(queueIndex = 2, song = third),
                PlayerQueueDisplayItem(queueIndex = 0, song = first),
                PlayerQueueDisplayItem(queueIndex = 1, song = second)
            )
        )

        removeSelectedNowPlayingQueueEntries(entries, setOf(entries[0].key), queue::removeAt)

        assertEquals(listOf(first, second), queue)
    }

    @Test
    fun `batch removal only removes the selected occurrence of a repeated song`() {
        val queue = mutableListOf(first, second, first, third)
        val entries = buildNowPlayingQueueEntries(queue)

        removeSelectedNowPlayingQueueEntries(entries, setOf(entries[2].key), queue::removeAt)

        assertEquals(listOf(first, second, third), queue)
    }

    @Test
    fun `empty or obsolete selection leaves the queue intact`() {
        val queue = mutableListOf(first, second)
        val entries = buildNowPlayingQueueEntries(queue)

        removeSelectedNowPlayingQueueEntries(entries, emptySet(), queue::removeAt)
        removeSelectedNowPlayingQueueEntries(entries, setOf("missing"), queue::removeAt)

        assertEquals(listOf(first, second), queue)
    }

    @Test
    fun `batch removal preserves a playing row when only other occurrences are selected`() {
        val owner = PlayerQueueEditOwner(identity)
        var queue = PlayerQueueSnapshot.from(listOf(first, second, first, third), 2)
        val entries = buildNowPlayingQueueEntries(queue.playlist)
        val actions = mutableListOf<RemovedQueuePlaybackAction>()

        removeSelectedNowPlayingQueueEntries(entries, setOf(entries[0].key, entries[3].key)) { index ->
            val edit = requireNotNull(owner.remove(queue, index))
            queue = edit.queue
            actions += owner.removalPlaybackAction(edit, transportActive = true)
        }

        assertEquals(listOf(second, first), queue.playlist)
        assertEquals(1, queue.currentIndex)
        assertEquals(
            listOf(RemovedQueuePlaybackAction.KEEP_PLAYING, RemovedQueuePlaybackAction.KEEP_PLAYING),
            actions
        )
    }

    @Test
    fun `removing current and upcoming selected rows continues at the next retained song`() {
        val owner = PlayerQueueEditOwner(identity)
        var queue = PlayerQueueSnapshot.from(listOf(first, second, third, fourth), 1)
        val entries = buildNowPlayingQueueEntries(queue.playlist)
        val actions = mutableListOf<RemovedQueuePlaybackAction>()

        removeSelectedNowPlayingQueueEntries(entries, setOf(entries[1].key, entries[2].key)) { index ->
            val edit = requireNotNull(owner.remove(queue, index))
            queue = edit.queue
            actions += owner.removalPlaybackAction(edit, transportActive = true)
        }

        assertEquals(listOf(first, fourth), queue.playlist)
        assertEquals(1, queue.currentIndex)
        assertEquals(
            listOf(RemovedQueuePlaybackAction.KEEP_PLAYING, RemovedQueuePlaybackAction.PLAY_NEXT),
            actions
        )
    }

    @Test
    fun `select all removal empties the queue and stops playback`() {
        val owner = PlayerQueueEditOwner(identity)
        var queue = PlayerQueueSnapshot.from(listOf(first, second), 0)
        val entries = buildNowPlayingQueueEntries(queue.playlist)
        val actions = mutableListOf<RemovedQueuePlaybackAction>()

        removeSelectedNowPlayingQueueEntries(entries, entries.mapTo(mutableSetOf()) { it.key }) { index ->
            val edit = requireNotNull(owner.remove(queue, index))
            queue = edit.queue
            actions += owner.removalPlaybackAction(edit, transportActive = true)
        }

        assertEquals(emptyList<SongItem>(), queue.playlist)
        assertEquals(-1, queue.currentIndex)
        assertEquals(
            listOf(RemovedQueuePlaybackAction.KEEP_PLAYING, RemovedQueuePlaybackAction.STOP),
            actions
        )
    }

    private val identity = object : QueueSongIdentity {
        override fun stableKey(song: SongItem): String = song.id.toString()
        override fun sameIdentity(song: SongItem, other: SongItem?): Boolean = song.id == other?.id
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = id,
        durationMs = 1_000L,
        coverUrl = null
    )
}
