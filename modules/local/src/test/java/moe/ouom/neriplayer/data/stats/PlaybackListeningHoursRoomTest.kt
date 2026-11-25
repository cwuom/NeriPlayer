package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.TimeZone
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
class PlaybackListeningHoursRoomTest {
    @Test fun groupsWholeDaysByRecordingDateAndClipsShorterRangesByInstant() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repo = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            val midnight = Instant.parse("2026-11-23T00:00:00Z").toEpochMilli()
            val minute = 60_000L
            val song = SongItem(7, "Song", "Artist", "Album", 0, 180_000, null)
            for (offset in listOf(-15, 5, 15)) {
                repo.recordListenDeltaNow(song, 10 * minute, 0, false, "end-$offset",
                    midnight + offset * minute, playedZone = "Asia/Kolkata")
            }
            suspend fun duration(start: Long, end: Long): Long {
                val hours = repo.readHourlyListening(PlaybackStatsQuery(range = PlaybackStatsTimeRange(start, end)))
                assertEquals(hours[5], hours.sum())
                return hours[5]
            }
            assertEquals(30 * minute, duration(midnight, midnight + 86_400_000))
            assertEquals(0L, duration(midnight - 86_400_000, midnight))
            assertEquals(2 * minute, duration(midnight - minute, midnight + minute))
            assertEquals(0L, duration(midnight - 15 * minute, midnight - 5 * minute))
            assertEquals(30 * minute, repo.readHourlyListening(PlaybackStatsQuery()).sum())
            val db = database.openHelper.writableDatabase
            db.query("SELECT COUNT(*) FROM playback_listening_interval").use { rows ->
                rows.moveToFirst()
                assertEquals(2, rows.getInt(0))
            }
            db.execSQL("INSERT INTO playback_stat_hour (hour_start_at, identity_key, local_hour, listened_ms, epoch_started_at) " +
                "VALUES (?, ?, 5, 9000, 0)",
                arrayOf<Any>(midnight - 30 * minute, song.toStatisticsMetadata().identityKey))
            assertEquals(30 * minute, duration(midnight, midnight + 86_400_000))
            assertEquals(9000L, duration(midnight - 86_400_000, midnight))
        } finally {
            TimeZone.setDefault(previousZone)
            database.close()
        }
    }

    @Test fun realDeltasAreAtomicAndOldTotalsStayOutOfHourlyListening() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val store = PlaybackStatsRoomStore(database)
            store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            val repo = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
            val query = PlaybackStatsQuery()
            val midnight = Instant.parse("2026-11-23T00:00:00Z").toEpochMilli()
            val song = SongItem(7, "Song", "Artist", "Album", 0, 180_000, null)
            repo.recordListenDeltaNow(song, 15_000, 0, false, "old", midnight)
            assertEquals(0L, repo.readHourlyListening(query).sum())
            repeat(2) {
                repo.recordListenDeltaNow(song, 15_000, 0, false, "known", midnight + 5_000, playedZone = "UTC")
            }
            val hours = repo.readHourlyListening(query)
            assertEquals(24, hours.size)
            assertEquals(15_000L, hours.sum())
            assertEquals(10_000L, hours[23])
            assertEquals(5_000L, hours[0])
            val nextDay = query.copy(range = PlaybackStatsTimeRange(midnight, midnight + 86_400_000))
            assertEquals(5_000L, repo.readHourlyListening(nextDay).sum())
            assertEquals(0L, repo.readHourlyListening(query.copy(requirePlayCount = true)).sum())
            assertEquals(30_000L, repo.readSummary(query).totalListenMs)
            val frozen = store.freezeSnapshot()
            store.commitFrozenSnapshot(frozen.id, frozen.revision)
            store.releaseSnapshot(frozen.id)
            assertEquals(hours, repo.readHourlyListening(query))
            store.removeTracks(setOf(song.toStatisticsMetadata().identityKey))
            assertEquals(0L, repo.readHourlyListening(query).sum())
            repo.recordListenDeltaNow(song, 10_000, 0, false, "afterRemoval", midnight, playedZone = "UTC")
            val clear = store.clear(midnight + 1)
            repo.recordListenDeltaNow(song, 10_000, 0, false, "stale", midnight + 5_000, observedClearedAt = 0, playedZone = "UTC")
            assertEquals(0L, repo.readHourlyListening(query).sum())
            repo.recordListenDeltaNow(song, 10_000, 0, false, "fresh", midnight + 15_000, observedClearedAt = clear.clearedAt, playedZone = "UTC")
            assertEquals(10_000L, repo.readHourlyListening(query).sum())
        } finally {
            database.close()
        }
    }
}
