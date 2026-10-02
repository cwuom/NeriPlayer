package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsDeltaRows
import moe.ouom.neriplayer.data.model.stats.TrackStat
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsDeltaPolicyTest {
    @Test
    fun `one delta updates the track day and owned shards without losing metadata`() {
        val metadata = track().copy(localFilePath = "/audio.flac", customName = "my title")
        val delta = PlaybackStatsPendingDeltaEntity("event", 1, "{}", 30_000, 1, 100, 50, "device")
        val result = PlaybackStatsDeltaPolicy.apply(delta, metadata, PlaybackStatsDeltaRows(null, null, null, null))
        assertEquals(30_000L, result.track?.totalListenMs)
        assertEquals(result.track?.totalListenMs, result.bucket?.totalListenMs)
        assertEquals(result.track?.totalListenMs, result.counter?.totalListenMs)
        assertEquals(result.track?.playCount, result.dailyCounter?.playCount)
        assertEquals("my title", result.track?.customName)
        assertEquals("/audio.flac", result.bucket?.localFilePath)
        assertEquals(50L, result.counter?.epochStartedAt)
    }

    @Test
    fun `a delta saturates Long and Int counters instead of wrapping negative`() {
        val previous = track().copy(totalListenMs = Long.MAX_VALUE - 1, playCount = Int.MAX_VALUE)
        val result = PlaybackStatsDeltaPolicy.apply(PlaybackStatsPendingDeltaEntity("event", 1, "{}", 30_000, 1, 200, 0, "device"),
            track(), PlaybackStatsDeltaRows(previous.toEntity(), null, null, null))
        assertEquals(Long.MAX_VALUE, result.track?.totalListenMs)
        assertEquals(Int.MAX_VALUE, result.track?.playCount)
    }

    @Test
    fun `a fresh clear epoch does not carry an old aggregate into new playback`() {
        val old = track().copy(totalListenMs = 1_000_000, playCount = 50, firstPlayedAt = 1, lastPlayedAt = 200)
        val result = PlaybackStatsDeltaPolicy.apply(PlaybackStatsPendingDeltaEntity("event", 1, "{}", 30_000, 1, 300, 250, "device"),
            track(), PlaybackStatsDeltaRows(old.toEntity(), null, null, null))
        assertEquals(30_000L, result.track?.totalListenMs)
        assertEquals(1, result.track?.playCount)
        assertEquals(300L, result.track?.firstPlayedAt)
    }

    private fun track() = TrackStat(7, "song", "artist", "netease", 0, null, 180_000, 0, 0, 200, 100, null, null, null, null, null, null, "track|7")
}
