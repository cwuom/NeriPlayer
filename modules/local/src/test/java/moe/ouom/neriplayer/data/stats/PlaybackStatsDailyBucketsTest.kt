package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PlaybackStatsDailyBucketsTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun useUtcCalendar() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreCalendar() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `legacy totals migrate only when their daily attribution is provable`() {
        val sameDay = stat(firstPlayedAt = utcMillis(2, 9), lastPlayedAt = utcMillis(2, 17))
        val spanningDays = stat(
            key = "spanning",
            firstPlayedAt = utcMillis(1, 9),
            lastPlayedAt = utcMillis(2, 17)
        )
        val missingFirstPlay = stat(
            key = "missing-first",
            firstPlayedAt = 0L,
            lastPlayedAt = utcMillis(2, 17)
        )

        val buckets = buildLegacyDailyStats(listOf(sameDay, spanningDays, missingFirstPlay), clearedAt = 0L)

        assertEquals(listOf("netease:42", "missing-first"), buckets.map { it.identityKey })
        assertEquals(utcMillis(2), buckets.first().dayStartAt)
        assertEquals(sameDay.totalListenMs, buckets.first().totalListenMs)
        assertEquals(sameDay.playCount, buckets.first().playCount)
        assertEquals(sameDay.firstPlayedAt, buckets.first().firstPlayedAt)
        assertEquals(sameDay.lastPlayedAt, buckets.first().lastPlayedAt)
        assertEquals(0L, buckets.last().firstPlayedAt)
    }

    @Test
    fun `legacy migration keeps the inclusive clear boundary and newer rows`() {
        val clearedAt = utcMillis(2, 10)
        val before = stat(key = "before", firstPlayedAt = clearedAt - 1L, lastPlayedAt = clearedAt - 1L)
        val boundary = stat(key = "boundary", firstPlayedAt = clearedAt, lastPlayedAt = clearedAt)
        val after = stat(key = "after", firstPlayedAt = clearedAt, lastPlayedAt = clearedAt + 1L)

        assertEquals(
            listOf("boundary", "after"),
            buildLegacyDailyStats(listOf(before, boundary, after), clearedAt).map { it.identityKey }
        )
        assertEquals(3, buildLegacyDailyStats(listOf(before, boundary, after), clearedAt = -1L).size)
    }

    @Test
    fun `legacy migration excludes rows with unusable playback timestamps`() {
        val invalid = listOf(
            stat(firstPlayedAt = 0L, lastPlayedAt = 0L),
            stat(firstPlayedAt = utcMillis(2), lastPlayedAt = 0L),
            stat(firstPlayedAt = -2L, lastPlayedAt = -1L)
        )

        assertTrue(buildLegacyDailyStats(invalid, clearedAt = 0L).isEmpty())
        assertTrue(buildLegacyDailyStats(emptyList(), clearedAt = 0L).isEmpty())
    }

    @Test
    fun `new daily bucket records the increment and preserves track metadata`() {
        val playedAt = utcMillis(2, 10)
        val stat = stat()

        val recorded = recordPlaybackStatBucket(emptyList(), stat, 250L, 1, playedAt).single()

        assertEquals(utcMillis(2), recorded.dayStartAt)
        assertEquals(250L, recorded.totalListenMs)
        assertEquals(1, recorded.playCount)
        assertEquals(playedAt, recorded.firstPlayedAt)
        assertEquals(playedAt, recorded.lastPlayedAt)
        assertEquals(stat.id, recorded.id)
        assertEquals(stat.identityKey, recorded.identityKey)
        assertEquals(stat.albumId, recorded.albumId)
        assertEquals(stat.name, recorded.name)
        assertEquals(stat.artist, recorded.artist)
        assertEquals(stat.album, recorded.album)
        assertEquals(stat.coverUrl, recorded.coverUrl)
        assertEquals(stat.durationMs, recorded.durationMs)
        assertEquals(stat.mediaUri, recorded.mediaUri)
        assertEquals(stat.localFilePath, recorded.localFilePath)
        assertEquals(stat.localFileName, recorded.localFileName)
        assertEquals(stat.customName, recorded.customName)
        assertEquals(stat.customArtist, recorded.customArtist)
        assertEquals(stat.customCoverUrl, recorded.customCoverUrl)
    }

    @Test
    fun `same track and day accumulates counters without changing other buckets`() {
        val current = listOf(
            bucket(dayStartAt = utcMillis(1)),
            bucket(key = "other"),
            bucket(firstPlayedAt = utcMillis(2, 10), lastPlayedAt = utcMillis(2, 12), playCount = 2)
        )
        val updatedStat = stat().copy(name = "updated title", customName = "edited title")

        val recorded = recordPlaybackStatBucket(current, updatedStat, 250L, 1, utcMillis(2, 9))

        assertEquals(3, recorded.size)
        assertSame(current[0], recorded[0])
        assertSame(current[1], recorded[1])
        assertEquals(350L, recorded[2].totalListenMs)
        assertEquals(3, recorded[2].playCount)
        assertEquals(utcMillis(2, 9), recorded[2].firstPlayedAt)
        assertEquals(utcMillis(2, 12), recorded[2].lastPlayedAt)
        assertEquals("updated title", recorded[2].name)
        assertEquals("edited title", recorded[2].customName)
        assertEquals(100L, current[2].totalListenMs)
        assertEquals(2, current[2].playCount)

        val later = recordPlaybackStatBucket(recorded, updatedStat, 75L, 2, utcMillis(2, 15))[2]
        assertEquals(425L, later.totalListenMs)
        assertEquals(5, later.playCount)
        assertEquals(utcMillis(2, 9), later.firstPlayedAt)
        assertEquals(utcMillis(2, 15), later.lastPlayedAt)
    }

    @Test
    fun `first playback timestamp retains the existing nonpositive compatibility rules`() {
        val positive = utcMillis(2, 10)
        listOf(
            Triple(0L, positive, positive),
            Triple(10L, 0L, 10L),
            Triple(-10L, -5L, -5L)
        ).forEach { (firstPlayedAt, playedAt, expectedFirst) ->
            val existing = bucket(
                dayStartAt = playbackStatsDayStartAt(playedAt),
                firstPlayedAt = firstPlayedAt,
                lastPlayedAt = firstPlayedAt
            )

            val recorded = recordPlaybackStatBucket(listOf(existing), stat(), 1L, 0, playedAt).single()

            assertEquals(expectedFirst, recorded.firstPlayedAt)
        }
    }

    @Test
    fun `another identity or day does not absorb a new bucket`() {
        val current = listOf(bucket(dayStartAt = utcMillis(1)), bucket(key = "other"))

        val recorded = recordPlaybackStatBucket(current, stat(), 250L, 1, utcMillis(2, 10))

        assertEquals(3, recorded.size)
        assertSame(current[0], recorded[0])
        assertSame(current[1], recorded[1])
        assertEquals("netease:42", recorded.last().identityKey)
        assertEquals(utcMillis(2), recorded.last().dayStartAt)
    }

    private fun stat(
        key: String = "netease:42",
        firstPlayedAt: Long = utcMillis(2),
        lastPlayedAt: Long = firstPlayedAt
    ): TrackStat {
        return TrackStat(
            id = 42L,
            name = "song",
            artist = "artist",
            album = "album",
            albumId = 7L,
            coverUrl = "https://example.com/cover.jpg",
            durationMs = 180_000L,
            totalListenMs = 30_000L,
            playCount = 2,
            lastPlayedAt = lastPlayedAt,
            firstPlayedAt = firstPlayedAt,
            mediaUri = "content://media/audio/42",
            localFilePath = "/music/song.flac",
            localFileName = "song.flac",
            customName = "custom title",
            customArtist = "custom artist",
            customCoverUrl = "content://media/cover/42",
            identityKey = key
        )
    }

    private fun bucket(
        key: String = "netease:42",
        dayStartAt: Long = utcMillis(2),
        playCount: Int = 1,
        firstPlayedAt: Long = dayStartAt,
        lastPlayedAt: Long = firstPlayedAt
    ): PlaybackStatBucket {
        return PlaybackStatBucket(
            dayStartAt = dayStartAt,
            id = 42L,
            name = "old title",
            artist = "artist",
            album = "album",
            coverUrl = null,
            durationMs = 180_000L,
            totalListenMs = 100L,
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

    private fun utcMillis(day: Int, hour: Int = 0): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.JULY, day, hour, 0, 0)
        }.timeInMillis
    }

}
