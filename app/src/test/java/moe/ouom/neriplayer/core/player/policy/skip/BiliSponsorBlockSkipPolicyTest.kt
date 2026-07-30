package moe.ouom.neriplayer.core.player.policy.skip

import moe.ouom.neriplayer.core.api.bili.BiliSponsorBlockSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliSponsorBlockSkipPolicyTest {
    @Test
    fun `overlapping segments jump to the furthest end only once`() {
        val tracker = BiliSponsorBlockSkipTracker()
        val segments = listOf(
            BiliSponsorBlockSegment("short", "sponsor", 10_000L, 15_000L),
            BiliSponsorBlockSegment("long", "music_offtopic", 12_000L, 20_000L)
        )

        assertEquals(20_000L, tracker.nextSkipPosition(segments, 12_500L, 30_000L))
        assertNull(tracker.nextSkipPosition(segments, 13_000L, 30_000L))
    }

    @Test
    fun `touching segments are skipped as one continuous range`() {
        val tracker = BiliSponsorBlockSkipTracker()
        val segments = listOf(
            BiliSponsorBlockSegment("first", "sponsor", 10_000L, 15_000L),
            BiliSponsorBlockSegment("second", "filler", 15_000L, 25_000L),
            BiliSponsorBlockSegment("third", "padding", 24_000L, 30_000L)
        )

        assertEquals(30_000L, tracker.nextSkipPosition(segments, 12_000L, 35_000L))
        assertNull(tracker.nextSkipPosition(segments, 16_000L, 35_000L))
    }

    @Test
    fun `rewinding rearms a previously skipped segment`() {
        val tracker = BiliSponsorBlockSkipTracker()
        val segments = listOf(
            BiliSponsorBlockSegment("segment", "sponsor", 10_000L, 20_000L)
        )

        assertEquals(20_000L, tracker.nextSkipPosition(segments, 12_000L, 30_000L))
        assertNull(tracker.nextSkipPosition(segments, 15_000L, 30_000L))
        assertNull(tracker.nextSkipPosition(segments, 5_000L, 30_000L))
        assertEquals(20_000L, tracker.nextSkipPosition(segments, 12_000L, 30_000L))
    }
}
