package moe.ouom.neriplayer.core.player.queue.heartbeat

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatBatchSelectionTest {

    private val current = song(1)

    @Test
    fun `official batch is returned as is and local is not asked`() = runTest {
        val local = BatchSelector(listOf(song(9)))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = BatchSelector(listOf(song(2), song(3))),
            localSource = local,
        )

        assertEquals(listOf(song(2), song(3)), coordinator.selectBatch(current, emptyList(), 30))
        assertEquals(0, local.batchCalls)
        assertTrue(coordinator.lastResultFromOfficial)
    }

    @Test
    fun `empty official batch falls back to local batch`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = BatchSelector(emptyList()),
            localSource = BatchSelector(listOf(song(9))),
        )

        assertEquals(listOf(song(9)), coordinator.selectBatch(current, emptyList(), 30))
        assertFalse(coordinator.lastResultFromOfficial)
    }

    @Test
    fun `default batch stops when the selector keeps returning the same song`() = runTest {
        val selector = object : HeartbeatNextTrackSelector {
            override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>) = song(2)
        }

        assertEquals(listOf(song(2)), selector.selectBatch(current, emptyList(), 30))
    }

    @Test
    fun `local batch picks distinct songs outside the queue up to the limit`() = runTest {
        val favorites = (2L..8L).map(::song)
        val source = LocalHeartbeatCandidateSource(provider = object : HeartbeatCandidateProvider {
            override suspend fun favorites(): List<SongItem> = favorites
            override suspend fun recentHistory(limit: Int): List<SongItem> = emptyList()
        })

        val batch = source.selectBatch(current, listOf(current, song(2)), 4)

        assertEquals(4, batch.size)
        assertEquals(batch.size, batch.map { it.id }.toSet().size)
        assertFalse(batch.any { it.id == 1L || it.id == 2L })
    }

    private class BatchSelector(private val batch: List<SongItem>) : HeartbeatNextTrackSelector {
        var batchCalls = 0
            private set

        override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>): SongItem? =
            batch.firstOrNull()

        override suspend fun selectBatch(
            currentSong: SongItem,
            currentQueue: List<SongItem>,
            limit: Int,
        ): List<SongItem> {
            batchCalls++
            return batch.take(limit)
        }
    }

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
