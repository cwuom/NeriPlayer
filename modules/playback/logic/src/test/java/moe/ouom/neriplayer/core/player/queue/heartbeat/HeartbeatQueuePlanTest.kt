package moe.ouom.neriplayer.core.player.queue.heartbeat

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartbeatQueuePlanTest {

    private val a = song(1)
    private val b = song(2)
    private val c = song(3)
    private val d = song(4)
    private val h1 = song(101)
    private val h2 = song(102)
    private val h3 = song(103)

    @Test
    fun `heartbeat songs go right after the current song and keep the original tail`() {
        val plan = HeartbeatQueuePlan.insertion(listOf(a, b, c, d), 1, emptySet(), listOf(h1, h2))

        assertEquals(listOf(a, b, h1, h2, c, d), plan?.playlist)
        assertEquals(listOf(h1, h2), plan?.songs)
    }

    @Test
    fun `a refill goes after the heartbeat songs that are still unplayed`() {
        val inserted = keys(h1, h2)
        val plan = HeartbeatQueuePlan.insertion(listOf(a, h1, h2, c), 1, inserted, listOf(h3))

        assertEquals(listOf(a, h1, h2, h3, c), plan?.playlist)
        assertEquals(1, HeartbeatQueuePlan.upcomingCount(listOf(a, h1, h2, c), 1, inserted))
    }

    @Test
    fun `songs already in the queue are not inserted twice`() {
        val plan = HeartbeatQueuePlan.insertion(listOf(a, b, c), 0, emptySet(), listOf(c, h1, h1))

        assertEquals(listOf(a, h1, b, c), plan?.playlist)
        assertNull(HeartbeatQueuePlan.insertion(listOf(a, b, c), 0, emptySet(), listOf(b, c)))
    }

    @Test
    fun `turning heartbeat off removes inserted songs but keeps the one playing`() {
        val queue = listOf(a, b, h1, h2, h3, c, d)
        val removed = HeartbeatQueuePlan.removalIndices(queue, 3, keys(h1, h2, h3))

        assertEquals(setOf(2, 4), removed)
        assertEquals(listOf(a, b, h2, c, d), queue.filterIndexed { index, _ -> index !in removed })
    }

    private fun keys(vararg songs: SongItem): Set<String> = songs.mapTo(HashSet()) { HeartbeatSongIdentity.identityKey(it) }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artist = "歌手$id",
        album = "专辑$id",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null,
        channelId = "netease",
        sourceStableKey = "netease:$id",
    )
}
