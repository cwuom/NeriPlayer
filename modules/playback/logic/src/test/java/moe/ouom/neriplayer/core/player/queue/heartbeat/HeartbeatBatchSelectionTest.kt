package moe.ouom.neriplayer.core.player.queue.heartbeat

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatBatchSelectionTest {

    private val current = song(1)

    @Test
    fun `official batch is returned as is`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(officialSource = BatchSelector(listOf(song(2), song(3))))

        assertEquals(listOf(song(2), song(3)), coordinator.selectBatch(current, emptyList(), 30))
    }

    @Test
    fun `empty official batch yields nothing`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(officialSource = BatchSelector(emptyList()))

        assertEquals(emptyList<SongItem>(), coordinator.selectBatch(current, emptyList(), 30))
    }

    @Test
    fun `default batch stops when the selector keeps returning the same song`() = runTest {
        val selector = object : HeartbeatNextTrackSelector {
            override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>) = song(2)
        }

        assertEquals(listOf(song(2)), selector.selectBatch(current, emptyList(), 30))
    }

    private class BatchSelector(private val batch: List<SongItem>) : HeartbeatNextTrackSelector {
        override suspend fun selectNext(currentSong: SongItem, currentQueue: List<SongItem>): SongItem? =
            batch.firstOrNull()

        override suspend fun selectBatch(
            currentSong: SongItem,
            currentQueue: List<SongItem>,
            limit: Int,
        ): List<SongItem> {
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
