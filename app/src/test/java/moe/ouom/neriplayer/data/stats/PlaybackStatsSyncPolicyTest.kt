package moe.ouom.neriplayer.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsSyncPolicyTest {
    @Test
    fun `automatic playback stats sync leaves a full minute for playback`() {
        assertEquals(60_000L, PLAYBACK_STATS_SYNC_DELAY_MS)
    }
}
