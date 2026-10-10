package moe.ouom.neriplayer.data.model.stats

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PlaybackStatsTimeRangeTest {
    private val originalTimeZone = TimeZone.getDefault()
    private val now = 1_710_084_600_000L // 2024-03-10T15:30:00Z
    private val nextDayStart = 1_710_115_200_000L // 2024-03-11T00:00:00Z

    @Before
    fun useUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `all time has no lower bound`() {
        assertEquals(
            PlaybackStatsTimeRange(startInclusive = null, endExclusive = Long.MAX_VALUE),
            PlaybackStatsPeriod.ALL.resolvePlaybackStatsTimeRange(now)
        )
    }

    @Test
    fun `bounded periods count whole days back from the next midnight`() {
        assertEquals(PlaybackStatsTimeRange(1_710_028_800_000L, nextDayStart), PlaybackStatsPeriod.DAY.resolvePlaybackStatsTimeRange(now))
        assertEquals(PlaybackStatsTimeRange(1_709_510_400_000L, nextDayStart), PlaybackStatsPeriod.WEEK.resolvePlaybackStatsTimeRange(now))
        assertEquals(PlaybackStatsTimeRange(1_707_523_200_000L, nextDayStart), PlaybackStatsPeriod.MONTH.resolvePlaybackStatsTimeRange(now))
        assertEquals(PlaybackStatsTimeRange(1_678_579_200_000L, nextDayStart), PlaybackStatsPeriod.YEAR.resolvePlaybackStatsTimeRange(now))
        assertEquals(1_710_028_800_000L, playbackStatsDayStartAt(now))
    }
}
