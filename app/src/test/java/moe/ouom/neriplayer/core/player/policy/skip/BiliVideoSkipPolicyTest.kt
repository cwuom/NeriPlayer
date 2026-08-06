package moe.ouom.neriplayer.core.player.policy.skip

import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliVideoSkipPolicyTest {
    @Test
    fun `touching user intervals are skipped as one range`() {
        val tracker = BiliVideoSkipTracker()
        val intervals = listOf(
            BiliVideoSkipInterval(10_000L, 15_000L),
            BiliVideoSkipInterval(15_000L, 20_000L),
            BiliVideoSkipInterval(19_000L, 25_000L)
        )

        assertEquals(25_000L, tracker.nextSkipPosition(intervals, 12_000L, 30_000L))
        assertNull(tracker.nextSkipPosition(intervals, 21_000L, 30_000L))
    }

    @Test
    fun `rewinding rearms an already skipped user interval`() {
        val tracker = BiliVideoSkipTracker()
        val intervals = listOf(BiliVideoSkipInterval(10_000L, 20_000L))

        assertEquals(20_000L, tracker.nextSkipPosition(intervals, 12_000L, 30_000L))
        assertNull(tracker.nextSkipPosition(intervals, 15_000L, 30_000L))
        assertNull(tracker.nextSkipPosition(intervals, 5_000L, 30_000L))
        assertEquals(20_000L, tracker.nextSkipPosition(intervals, 12_000L, 30_000L))
    }
}
