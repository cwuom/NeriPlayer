package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsTimeRange
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsInsightsRoomTest {
    private val monday = Instant.parse("2026-11-16T12:00:00Z").toEpochMilli()
    private val day = 86_400_000L

    @Test
    fun rankingsUseAllMatchingTracksAndAFullRangeDenominatorRegardlessOfPageSize() = runTest {
        withRoom { db, store ->
            val solo = track(1, "Solo", "Netease", 500, monday)
            val duet = track(2, "Band / AC/DC / Band", "Album", 40, monday)
            val band = (3..107).map { track(it, "Band", "Album", 10, monday + day) }
            val nextWeek = track(108, "Other", "Other", 10_000, monday + 7 * day)
            val tracks = listOf(solo, duet, nextWeek) + band
            db.playbackStatsDao().upsertStats(tracks.map { it.toEntity() })
            db.playbackStatsDao().upsertBuckets(tracks.map { bucket(it).toEntity() })
            val query = PlaybackStatsQuery(sort = PlaybackStatsSort.RECENT, range = PlaybackStatsTimeRange(monday, monday + 2 * day))
            val insights = store.readInsights(query, 1)
            assertEquals(store.readSummary(query), insights.summary)
            assertEquals(107L, insights.summary.trackCount)
            assertEquals(1590L, insights.summary.totalListenMs)
            assertEquals(solo.identityKey, insights.topTrack?.identityKey)
            assertEquals(monday, insights.earliestListenAt)
            assertEquals(3L, insights.artistCount)
            assertEquals(1L, insights.albumCount)
            val artist = insights.artists.single()
            assertEquals("Band", artist.name)
            assertEquals(106L, artist.trackCount)
            assertEquals(1090L, artist.totalListenMs)
            assertEquals(1090.0 / 1590, artist.share, 1e-8)
            assertEquals(1090L, insights.albums.single().totalListenMs)
            assertEquals(listOf(540L, 1050L, 0L, 0L, 0L, 0L, 0L), insights.weekdayListenMs)
            assertEquals(2L, insights.activeDays)
            assertEquals(795L, insights.averageDailyListenMs)
            assertEquals(1590L / 107, insights.averageListenMsPerPlay)
            val nextPeriod = store.readInsights(query.copy(range = PlaybackStatsTimeRange(monday + 7 * day, monday + 8 * day)), 1)
            assertEquals("Other", nextPeriod.artists.single().name)
            assertEquals("Other", nextPeriod.albums.single().name)
            assertEquals(10_000L, nextPeriod.summary.totalListenMs)
        }
    }

    @Test
    fun legacyTotalsDoNotInventDatesForMultiDayRecordsAndRetainKnownSameDayHistory() = runTest {
        withRoom { db, store ->
            val single = track(1, "Singer", "Album", 10, monday)
            val span = track(2, "Singer", "Album", 100, monday).copy(lastPlayedAt = monday + 2 * day)
            val unknown = track(3, "Singer", "Album", 15, monday).copy(firstPlayedAt = 0)
            db.playbackStatsDao().upsertStats(listOf(single, span, unknown).map { it.toEntity() })
            val insights = store.readInsights(PlaybackStatsQuery(), 10)
            assertEquals(125L, insights.summary.totalListenMs)
            assertEquals(10L, insights.weekdayListenMs.first())
            assertEquals(1L, insights.activeDays)
            assertEquals(10L, insights.averageDailyListenMs)
            val between = store.readInsights(PlaybackStatsQuery(range = PlaybackStatsTimeRange(monday + day, monday + 2 * day)), 10)
            assertEquals(0L, between.summary.trackCount)
            assertNull(between.topTrack)
            assertNull(between.earliestListenAt)
        }
    }

    @Test
    fun dateRangeUsesRecordedBucketDurationForPartiallyDatedTracks() = runTest {
        withRoom { db, store ->
            val track = track(1, "Singer", "Record", 100, monday).copy(lastPlayedAt = monday + 3 * day)
            db.playbackStatsDao().upsertStats(listOf(track.toEntity()))
            db.playbackStatsDao().upsertBuckets(listOf(bucket(track).copy(totalListenMs = 20).toEntity()))
            val query = PlaybackStatsQuery(range = PlaybackStatsTimeRange(monday, monday + day))
            val insights = store.readInsights(query, 1)
            assertEquals(20L, insights.summary.totalListenMs)
            assertEquals(20L, insights.weekdayListenMs.first())
        }
    }

    @Test
    fun sameAlbumTitlesUseProviderAlbumIdsOrSeparatePrimaryArtists() = runTest {
        withRoom { db, store ->
            val tracks = listOf(
                track(1, "Artist A / Guest", "Greatest Hits", 100, monday),
                track(2, "Artist A", "Greatest Hits", 50, monday),
                track(3, "Artist B", "Greatest Hits", 60, monday),
                track(4, "Artist A", "Greatest Hits", 60, monday).copy(identityKey = "4|netease|", albumId = 99),
                track(5, "Artist B", "Greatest Hits", 80, monday).copy(identityKey = "5|netease|", albumId = 99),
                track(6, "Artist C", "Greatest Hits", 20, monday).copy(identityKey = "6|netease|", albumId = 98),
                track(7, "Artist A", "Greatest Hits", 30, monday).copy(mediaUri = "neri-server://profile/song/a", albumId = -98),
                track(8, "Artist B", "Greatest Hits", 20, monday).copy(mediaUri = "neri-server://profile/song/b", albumId = -98)
            )
            db.playbackStatsDao().upsertStats(tracks.map { it.toEntity() })
            val insights = store.readInsights(PlaybackStatsQuery(), 10)
            assertEquals(5L, insights.albumCount)
            assertEquals(listOf(150L, 140L, 60L, 50L, 20L), insights.albums.map { it.totalListenMs })
            assertEquals(listOf(2L, 2L, 1L, 2L, 1L), insights.albums.map { it.trackCount })
        }
    }

    private suspend fun withRoom(block: suspend (NeriUserDataDatabase, PlaybackStatsRoomStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            block(database, PlaybackStatsRoomStore(database))
        } finally {
            database.close()
        }
    }

    private fun track(id: Int, artist: String, album: String, ms: Long, start: Long) =
        TrackStat(id.toLong(), "Song $id", artist, album, 0, null, 60_000, ms, 1, start, start,
            null, null, null, null, null, null, "k$id")

    private fun bucket(t: TrackStat) = PlaybackStatBucket(t.firstPlayedAt, t.id, t.name, t.artist, t.album,
        t.albumId, t.coverUrl, t.durationMs, t.totalListenMs, t.playCount, t.lastPlayedAt, t.firstPlayedAt,
        null, null, null, null, null, null, t.identityKey)
}
