package moe.ouom.neriplayer.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class PlaybackStatsPeriodTest {

    @Test
    fun `resolvePlaybackStatsTimeRange returns nested rolling day windows`() {
        withCalendarDefaults {
            val now = utcMillis(2026, Calendar.JULY, 2, 10)

            val day = PlaybackStatsPeriod.DAY.resolvePlaybackStatsTimeRange(now)
            val week = PlaybackStatsPeriod.WEEK.resolvePlaybackStatsTimeRange(now)
            val month = PlaybackStatsPeriod.MONTH.resolvePlaybackStatsTimeRange(now)
            val year = PlaybackStatsPeriod.YEAR.resolvePlaybackStatsTimeRange(now)
            val all = PlaybackStatsPeriod.ALL.resolvePlaybackStatsTimeRange(now)

            assertEquals(utcMillis(2026, Calendar.JULY, 2), day.startInclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 3), day.endExclusive)
            assertEquals(utcMillis(2026, Calendar.JUNE, 26), week.startInclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 3), week.endExclusive)
            assertEquals(utcMillis(2026, Calendar.JUNE, 3), month.startInclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 3), month.endExclusive)
            assertEquals(utcMillis(2025, Calendar.JULY, 3), year.startInclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 3), year.endExclusive)
            assertNull(all.startInclusive)
            assertEquals(Long.MAX_VALUE, all.endExclusive)
        }
    }

    @Test
    fun `rolling periods stay nested across a month boundary`() {
        withCalendarDefaults {
            val now = utcMillis(2026, Calendar.AUGUST, 2, 10)

            val week = PlaybackStatsPeriod.WEEK.resolvePlaybackStatsTimeRange(now)
            val month = PlaybackStatsPeriod.MONTH.resolvePlaybackStatsTimeRange(now)

            assertEquals(utcMillis(2026, Calendar.JULY, 27), week.startInclusive)
            assertEquals(utcMillis(2026, Calendar.AUGUST, 3), week.endExclusive)
            assertEquals(utcMillis(2026, Calendar.JULY, 4), month.startInclusive)
            assertEquals(utcMillis(2026, Calendar.AUGUST, 3), month.endExclusive)
            assertTrue(month.startInclusive!! <= week.startInclusive!!)
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
                firstPlayedAt = utcMillis(2026, Calendar.JUNE, 10, 9),
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
                firstPlayedAt = utcMillis(2026, Calendar.JUNE, 10, 9),
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

    @Test
    fun `weekly hot playlist excludes tracks below ten minutes`() {
        withCalendarDefaults {
            val now = utcMillis(2026, Calendar.JULY, 10, 10)
            val day = utcMillis(2026, Calendar.JULY, 9)
            val hotPlaylist = buildPlaybackStatsHotPlaylist(
                stats = emptyList(),
                dailyStats = listOf(
                    bucket(
                        key = "netease:1",
                        name = "first",
                        dayStartAt = day,
                        totalListenMs = 600_000L,
                        playCount = 2
                    ),
                    bucket(
                        key = "netease:2",
                        name = "second",
                        dayStartAt = day,
                        totalListenMs = 599_999L,
                        playCount = 5
                    ),
                    bucket(
                        key = "netease:3",
                        name = "third",
                        dayStartAt = day,
                        totalListenMs = 900_000L,
                        playCount = 1
                    )
                ),
                period = PlaybackStatsPeriod.WEEK,
                nowMillis = now
            )

            assertEquals(listOf("netease:1", "netease:3"), hotPlaylist.tracks.map { it.identityKey })
            assertEquals(3L, hotPlaylist.totalPlayCount)
            assertFalse(hotPlaylist.usesLegacyBreakdown)
        }
    }

    @Test
    fun `monthly hot playlist requires thirty minutes with legacy stats`() {
        withCalendarDefaults {
            val belowThreshold = stat(
                key = "netease:1",
                firstPlayedAt = utcMillis(2026, Calendar.JULY, 3, 9),
                lastPlayedAt = utcMillis(2026, Calendar.JULY, 4, 9),
                totalListenMs = 1_799_999L
            )
            val atThreshold = stat(
                key = "netease:2",
                firstPlayedAt = utcMillis(2026, Calendar.JULY, 5, 9),
                lastPlayedAt = utcMillis(2026, Calendar.JULY, 6, 9),
                totalListenMs = 1_800_000L
            )

            val hotPlaylist = buildPlaybackStatsHotPlaylist(
                stats = listOf(belowThreshold, atThreshold),
                dailyStats = emptyList(),
                period = PlaybackStatsPeriod.MONTH,
                nowMillis = utcMillis(2026, Calendar.JULY, 10, 10)
            )

            assertTrue(hotPlaylist.usesLegacyBreakdown)
            assertEquals(listOf("netease:2"), hotPlaylist.tracks.map { it.identityKey })
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
        lastPlayedAt: Long,
        totalListenMs: Long = 10_000L
    ): TrackStat {
        return TrackStat(
            id = key.substringAfter(':').toLong(),
            name = key,
            artist = "artist",
            album = "album",
            coverUrl = null,
            durationMs = 180_000L,
            totalListenMs = totalListenMs,
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
