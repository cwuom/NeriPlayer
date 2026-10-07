package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsDeltaRows
import moe.ouom.neriplayer.data.model.stats.TrackStat
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsDeltaSessionCountTest {
    @Test
    fun `a first session counts once it reaches thirty seconds`() {
        assertEquals(1, playCountAfter(listenedMs = 30_000, previous = null))
        assertEquals(0, playCountAfter(listenedMs = 29_999, previous = null))
    }

    @Test
    fun `short follow-up sessions count only when they complete another full listen`() {
        val previous = track().copy(totalListenMs = 170_000, playCount = 4)

        assertEquals(5, playCountAfter(listenedMs = 30_000, previous = previous))
        assertEquals(5, playCountAfter(listenedMs = 20_000, previous = previous))
        assertEquals(4, playCountAfter(listenedMs = 5_000, previous = previous))
    }

    @Test
    fun `sessions without a known duration measure full listens with the stored duration`() {
        val previous = track().copy(totalListenMs = 170_000, playCount = 4)
        val unknownDuration = track().copy(durationMs = 0)

        assertEquals(5, playCountAfter(listenedMs = 20_000, previous = previous, metadata = unknownDuration))
        assertEquals(4, playCountAfter(listenedMs = 5_000, previous = previous, metadata = unknownDuration))
    }

    private fun playCountAfter(listenedMs: Long, previous: TrackStat?, metadata: TrackStat = track()): Int? {
        val delta = PlaybackStatsPendingDeltaEntity("event", 1, "{}", listenedMs, null, 300, 0, "device")
        val rows = PlaybackStatsDeltaRows(previous?.toEntity(), null, null, null)
        return PlaybackStatsDeltaPolicy.apply(delta, metadata, rows).track?.playCount
    }

    private fun track() = TrackStat(7, "song", "artist", "netease", 0, null, 180_000, 0, 0, 200, 100, null, null, null, null, null, null, "track|7")
}
