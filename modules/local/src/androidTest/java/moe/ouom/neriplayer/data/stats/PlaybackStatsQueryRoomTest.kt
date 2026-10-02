package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsQueryRoomTest {
    @Test
    fun summarySaturatesLongExactlyAndNeverUsesFloatingPoint() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(listOf(track(0).copy(totalListenMs = Long.MAX_VALUE - 5), track(1).copy(totalListenMs = 10)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            assertEquals(Long.MAX_VALUE, store.readSummary(PlaybackStatsQuery()).totalListenMs)
            val exact = 9_007_199_254_740_993L
            store.replaceAll(listOf(track(0).copy(totalListenMs = exact), track(1).copy(totalListenMs = 12_345)), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            assertEquals(exact + 12_345, store.readSummary(PlaybackStatsQuery()).totalListenMs)
        } finally { database.close() }
    }

    @Test
    fun periodBucketsSaturateAndChooseLatestDayMetadataWithoutWindowFunctions() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            val now = System.currentTimeMillis()
            val day = playbackStatsDayStartAt(now)
            val original = track(0)
            val buckets = listOf(bucket(original, day - 86_400_000).copy(totalListenMs = Long.MAX_VALUE - 1, playCount = Int.MAX_VALUE),
                bucket(original, day).copy(name = "latest name", totalListenMs = 10, playCount = Int.MAX_VALUE))
            store.importLegacyAndPromote(listOf(original), buckets, PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val query = PlaybackStatsQuery(PlaybackStatsPeriod.MONTH, nowMillis = now)
            val page = store.readPage(query, null, 100)
            assertEquals(Long.MAX_VALUE, page.tracks.single().totalListenMs)
            assertEquals(Int.MAX_VALUE, page.tracks.single().playCount)
            assertEquals("latest name", page.tracks.single().name)
            assertEquals(Long.MAX_VALUE, store.readSummary(query).totalListenMs)
        } finally { database.close() }
    }

    @Test
    fun allSortModesTraverseEqualValuesWithoutDuplicatesAndReturnPreviousPage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote((0..599).map { track(it).copy(playCount = it % 3, totalListenMs = (it % 4).toLong(), firstPlayedAt = (it % 5).toLong()) }, emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            for (sort in PlaybackStatsSort.entries) {
                val query = PlaybackStatsQuery(sort = sort)
                val first = store.readPage(query, null, 100)
                val second = store.readPage(query, first.nextCursor, 100)
                val previous = store.readPage(query, second.previousCursor, 100, before = true)
                assertEquals(first.tracks, previous.tracks)
                val keys = mutableListOf<String>()
                var cursor: PlaybackStatsCursor? = null
                do {
                    val page = store.readPage(query, cursor, 100)
                    assertTrue(page.tracks.size <= 100)
                    keys.addAll(page.tracks.map { it.identityKey })
                    cursor = page.nextCursor
                } while (cursor != null)
                assertEquals(600, keys.size)
                assertEquals(600, keys.toSet().size)
            }
        } finally { database.close() }
    }

    private fun track(index: Int) = TrackStat(index.toLong(), "song $index", "artist", "netease", 0, null, 180_000, 30_000, 1, 200, 100,
        null, null, null, null, null, null, "track|${index.toString().padStart(4, '0')}")
    private fun bucket(track: TrackStat, day: Long) = PlaybackStatBucket(day, track.id, track.name, track.artist, track.album, track.albumId, track.coverUrl,
        track.durationMs, track.totalListenMs, track.playCount, track.lastPlayedAt, track.firstPlayedAt, track.mediaUri,
        track.localFilePath, track.localFileName, track.customName, track.customArtist, track.customCoverUrl, track.identityKey)
}
