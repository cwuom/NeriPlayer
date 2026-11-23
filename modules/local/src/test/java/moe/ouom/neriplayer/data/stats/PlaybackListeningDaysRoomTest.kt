package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsTimeRange
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackListeningDaysRoomTest {
    @Test
    fun dailyTotalsAndOwnedShardsFollowTheCapturedZoneAcrossMidnightAndReplayOnlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repo = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            val midnight = Instant.parse("2026-11-22T16:00:00Z").toEpochMilli()
            val day = 86_400_000L
            val song = SongItem(7, "Song", "Artist", "Album", 0, 180_000, null)
            val yesterday = PlaybackStatsQuery(range = PlaybackStatsTimeRange(midnight - day, midnight))
            val today = PlaybackStatsQuery(range = PlaybackStatsTimeRange(midnight, midnight + day))
            repeat(2) {
                repo.recordListenDeltaNow(song, 15_000, 1, false, "split", midnight + 5_000, playedZone = "Asia/Singapore")
            }
            assertEquals(10_000L, repo.readSummary(yesterday).totalListenMs)
            assertEquals(0L, repo.readSummary(yesterday).totalPlayCount)
            assertEquals(10_000L, repo.readHourlyListening(yesterday)[23])
            assertEquals(5_000L, repo.readSummary(today).totalListenMs)
            assertEquals(1L, repo.readSummary(today).totalPlayCount)
            assertEquals(5_000L, repo.readHourlyListening(today)[0])
            val rows = database.playbackStatsDao().getDailyCounterShards().sortedBy { it.dayStartAt }
            assertEquals(listOf(midnight - day, midnight), rows.map { it.dayStartAt })
            assertEquals(listOf(10_000L, 5_000L), rows.map { it.totalListenMs })
            assertEquals(listOf(0, 1), rows.map { it.playCount })
            assertEquals(1L, repo.readInsights(yesterday).activeDays)
            val lifetime = store.readTrack(song.toStatisticsMetadata().identityKey)!!
            assertEquals(midnight + 5_000, lifetime.firstPlayedAt)
            assertEquals(midnight + 5_000, lifetime.lastPlayedAt)
            assertEquals(15_000L, lifetime.totalListenMs)
            assertEquals(1, lifetime.playCount)
            repo.recordListenDeltaNow(song, 1_000, 0, false, "exact-midnight", midnight + day, playedZone = "Asia/Singapore")
            assertEquals(6_000L, repo.readSummary(today).totalListenMs)
            assertEquals(0L, repo.readSummary(PlaybackStatsQuery(range = PlaybackStatsTimeRange(midnight + day, midnight + 2 * day))).trackCount)
        } finally {
            database.close()
        }
    }
}
