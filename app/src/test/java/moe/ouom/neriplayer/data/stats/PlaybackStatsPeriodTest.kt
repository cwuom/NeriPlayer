package moe.ouom.neriplayer.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class PlaybackStatsPeriodTest {

    @Test
    fun `resolvePlaybackStatsTimeRange returns local day week month and year boundaries`() {
        withCalendarDefaults {
            val now = utcMillis(2026, Calendar.JULY, 2, 10)

            val day = PlaybackStatsPeriod.DAY.resolvePlaybackStatsTimeRange(now)
            val week = PlaybackStatsPeriod.WEEK.resolvePlaybackStatsTimeRange(now)
            val month = PlaybackStatsPeriod.MONTH.resolvePlaybackStatsTimeRange(now)
            val year = PlaybackStatsPeriod.YEAR.resolvePlaybackStatsTimeRange(now)
            val all = PlaybackStatsPeriod.ALL.resolvePlaybackStatsTimeRange(now)

            assertEquals(utcMillis(2026, Calendar.JULY, 2), day.startInclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 3), day.endExclusive)
            assertEquals(utcMillis(2026, Calendar.JUNE, 29), week.startInclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 6), week.endExclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 1), month.startInclusive)
            assertEquals(utcMillis(2026, Calendar.AUGUST, 1), month.endExclusive)
            assertEquals(utcMillis(2026, Calendar.JANUARY, 1), year.startInclusive)
            assertEquals(utcMillis(2027, Calendar.JANUARY, 1), year.endExclusive)
            assertNull(all.startInclusive)
            assertEquals(Long.MAX_VALUE, all.endExclusive)
        }
    }

    @Test
    fun `aggregatePlaybackStatBuckets merges the same track inside selected range`() {
        withCalendarDefaults {
            val range = PlaybackStatsTimeRange(
                startInclusive = utcMillis(2026, Calendar.JULY, 1),
                endExclusive = utcMillis(2026, Calendar.AUGUST, 1)
            )
            val firstPlay = utcMillis(2026, Calendar.JULY, 1, 9)
            val secondPlay = utcMillis(2026, Calendar.JULY, 2, 11)
            val buckets = listOf(
                bucket(
                    key = "netease:1",
                    name = "A",
                    dayStartAt = utcMillis(2026, Calendar.JULY, 1),
                    totalListenMs = 10_000L,
                    playCount = 1,
                    firstPlayedAt = firstPlay,
                    lastPlayedAt = firstPlay
                ),
                bucket(
                    key = "netease:1",
                    name = "A+",
                    dayStartAt = utcMillis(2026, Calendar.JULY, 2),
                    totalListenMs = 20_000L,
                    playCount = 2,
                    firstPlayedAt = secondPlay,
                    lastPlayedAt = secondPlay
                ),
                bucket(
                    key = "netease:2",
                    name = "B",
                    dayStartAt = utcMillis(2026, Calendar.JUNE, 30),
                    totalListenMs = 30_000L,
                    playCount = 3
                )
            )

            val stats = aggregatePlaybackStatBuckets(buckets, range)

            assertEquals(1, stats.size)
            val stat = stats.first()
            assertEquals("netease:1", stat.identityKey)
            assertEquals("A+", stat.name)
            assertEquals(30_000L, stat.totalListenMs)
            assertEquals(3, stat.playCount)
            assertEquals(firstPlay, stat.firstPlayedAt)
            assertEquals(secondPlay, stat.lastPlayedAt)
        }
    }

    @Test
    fun `aggregatePlaybackStatsCompatForPeriod keeps only stats fully inside range`() {
        withCalendarDefaults {
            val exact = stat(
                key = "netease:1",
                firstPlayedAt = utcMillis(2026, Calendar.JULY, 3, 9),
                lastPlayedAt = utcMillis(2026, Calendar.JULY, 4, 9)
            )
            val spanning = stat(
                key = "netease:2",
                firstPlayedAt = utcMillis(2026, Calendar.JUNE, 30, 9),
                lastPlayedAt = utcMillis(2026, Calendar.JULY, 4, 9)
            )

            val stats = aggregatePlaybackStatsCompatForPeriod(
                stats = listOf(exact, spanning),
                period = PlaybackStatsPeriod.MONTH,
                nowMillis = utcMillis(2026, Calendar.JULY, 10, 10)
            )

            assertEquals(1, stats.size)
            assertEquals("netease:1", stats.single().identityKey)
        }
    }

    @Test
    fun `aggregatePlaybackStatsCompatForPeriod returns empty when no exact stats can be proven`() {
        withCalendarDefaults {
            val spanning = stat(
                key = "netease:2",
                firstPlayedAt = utcMillis(2026, Calendar.JUNE, 30, 9),
                lastPlayedAt = utcMillis(2026, Calendar.JULY, 4, 9)
            )

            val stats = aggregatePlaybackStatsCompatForPeriod(
                stats = listOf(spanning),
                period = PlaybackStatsPeriod.MONTH,
                nowMillis = utcMillis(2026, Calendar.JULY, 10, 10)
            )

            assertTrue(stats.isEmpty())
        }
    }

    private fun withCalendarDefaults(block: () -> Unit) {
        val originalTimeZone = TimeZone.getDefault()
        val originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.CHINA)
        try {
            block()
        } finally {
            TimeZone.setDefault(originalTimeZone)
            Locale.setDefault(originalLocale)
        }
    }

    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0
    ): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.CHINA).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis
    }

    private fun bucket(
        key: String,
        name: String,
        dayStartAt: Long,
        totalListenMs: Long,
        playCount: Int,
        firstPlayedAt: Long = dayStartAt,
        lastPlayedAt: Long = firstPlayedAt
    ): PlaybackStatBucket {
        return PlaybackStatBucket(
            dayStartAt = dayStartAt,
            id = key.substringAfter(':').toLong(),
            name = name,
            artist = "artist",
            album = "album",
            coverUrl = null,
            durationMs = 180_000L,
            totalListenMs = totalListenMs,
            playCount = playCount,
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            mediaUri = null,
            localFilePath = null,
            localFileName = null,
            customName = null,
            customArtist = null,
            customCoverUrl = null,
            identityKey = key
        )
    }

    private fun stat(
        key: String,
        firstPlayedAt: Long,
        lastPlayedAt: Long
    ): TrackStat {
        return TrackStat(
            id = key.substringAfter(':').toLong(),
            name = key,
            artist = "artist",
            album = "album",
            coverUrl = null,
            durationMs = 180_000L,
            totalListenMs = 10_000L,
            playCount = 1,
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            mediaUri = null,
            localFilePath = null,
            localFileName = null,
            customName = null,
            customArtist = null,
            customCoverUrl = null,
            identityKey = key
        )
    }
}
