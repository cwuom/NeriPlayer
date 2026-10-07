package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.resolvePlaybackStatsTimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackStatsCompatPeriodFilterTest {
    @Test
    fun `stats without a first play time are placed by their last play`() {
        val now = 1_700_000_000_000L
        val range = PlaybackStatsPeriod.WEEK.resolvePlaybackStatsTimeRange(now)
        val start = requireNotNull(range.startInclusive)
        val stats = listOf(
            stat(id = 1, firstPlayedAt = 0, lastPlayedAt = start + 1),
            stat(id = 2, firstPlayedAt = 0, lastPlayedAt = start - 1),
            stat(id = 3, firstPlayedAt = start, lastPlayedAt = range.endExclusive - 1),
            stat(id = 4, firstPlayedAt = start + 1, lastPlayedAt = range.endExclusive)
        )

        assertEquals(
            listOf(1L, 3L),
            aggregatePlaybackStatsCompatForPeriod(stats, PlaybackStatsPeriod.WEEK, now).map(TrackStat::id)
        )
    }

    @Test
    fun `the all-time period returns the stats untouched`() {
        val stats = listOf(stat(id = 1, firstPlayedAt = 0, lastPlayedAt = 10), stat(id = 2, firstPlayedAt = 5, lastPlayedAt = 3))

        assertSame(stats, aggregatePlaybackStatsCompatForPeriod(stats, PlaybackStatsPeriod.ALL, nowMillis = 0))
    }

    private fun stat(id: Long, firstPlayedAt: Long, lastPlayedAt: Long) = TrackStat(
        id, "song $id", "artist", "album", 0, null, 180_000, 60_000, 1,
        lastPlayedAt, firstPlayedAt, null, null, null, null, null, null, "track|$id"
    )
}
