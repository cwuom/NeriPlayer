package moe.ouom.neriplayer.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsTimeRange
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackListeningHoursSyncTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var firstDatabase: NeriUserDataDatabase
    private lateinit var secondDatabase: NeriUserDataDatabase
    private lateinit var dataset: FileSyncPlaybackDatasetStore
    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        firstDatabase = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        secondDatabase = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        dataset = FileSyncPlaybackDatasetStore(temporary.newFolder())
    }

    @After
    fun tearDown() {
        secondDatabase.close()
        firstDatabase.close()
        TimeZone.setDefault(originalZone)
    }

    private suspend fun TestScope.repository(db: NeriUserDataDatabase, device: String): PlaybackStatsRepository {
        val store = PlaybackStatsRoomStore(db)
        store.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
        return PlaybackStatsRepository(ApplicationProvider.getApplicationContext(), store, backgroundScope) { device }
    }

    private suspend fun synchronize(from: PlaybackStatsRepository, to: PlaybackStatsRepository, db: NeriUserDataDatabase) {
        dataset.newOrderedSink().use { sink ->
            val captured = from.captureSyncSnapshot(sink)
            sink.seal().use { source ->
                val revision = checkNotNull(PlaybackStatsRoomStore(db).readPrimaryState()).revision
                assertTrue(to.applySyncSnapshot(source, captured.clearedAt, revision))
            }
        }
    }

    @Test
    fun synchronizingBothWaysKeepsHourlyTotalsAndLocalIntervalPrecision() = runTest {
        val first = repository(firstDatabase, "first")
        val second = repository(secondDatabase, "second")
        val midnight = Instant.parse("2024-01-02T00:00:00Z").toEpochMilli()
        val song = SongItem(7, "Song", "Artist", "Netease - Album", 0, 180_000, null)
        first.recordListenDeltaNow(song, 15_000, 1, scheduleSync = false,
            eventId = "cross-midnight", playedAt = midnight + 5_000, playedZone = "UTC")

        val all = PlaybackStatsQuery()
        val expectedHours = MutableList(24) { 0L }.apply {
            this[23] = 10_000L
            this[0] = 5_000L
        }
        assertEquals(expectedHours, first.readHourlyListening(all))

        repeat(2) { synchronize(first, second, secondDatabase) }

        val insights = second.readInsights(all)
        assertEquals(15_000L, insights.summary.totalListenMs)
        assertEquals(1L, insights.summary.totalPlayCount)
        assertEquals(2L, insights.activeDays)
        assertEquals(listOf(10_000L, 5_000L, 0L, 0L, 0L, 0L, 0L), insights.weekdayListenMs)
        assertEquals(expectedHours, second.readHourlyListening(all))
        val tuesday = all.copy(range = PlaybackStatsTimeRange(midnight, midnight + 86_400_000))
        assertEquals(5_000L, second.readHourlyListening(tuesday).sum())
        assertEquals(0L, second.readHourlyListening(all.copy(minimumListenMs = 20_000)).sum())

        second.recordListenDeltaNow(song, 5_000, 1, scheduleSync = false,
            eventId = "simultaneous", playedAt = midnight + 5_000, playedZone = "UTC")
        synchronize(second, first, firstDatabase)
        synchronize(first, second, secondDatabase)
        expectedHours[0] = 10_000L
        assertEquals(expectedHours, first.readHourlyListening(all))
        assertEquals(expectedHours, second.readHourlyListening(all))
        assertEquals(20_000L, second.readSummary(all).totalListenMs)
        // 接收的汇总按小时起点筛选，本机录制的部分仍能精确裁切
        val clipped = all.copy(range = PlaybackStatsTimeRange(midnight + 1_000, midnight + 3_000))
        assertEquals(2_000L, first.readHourlyListening(clipped)[0])
        assertEquals(2_000L, second.readHourlyListening(clipped)[0])

        PlaybackStatsRoomStore(firstDatabase).clear(midnight + 60_000)
        synchronize(first, second, secondDatabase)
        assertEquals(List(24) { 0L }, second.readHourlyListening(all))
    }

    @Test
    fun hourChartUsesEachRecordingDateWhenDevicesShareAMidnightAcrossTheDateLine() = runTest {
        val first = repository(firstDatabase, "kiribati")
        val second = repository(secondDatabase, "hawaii")
        val song = SongItem(8, "Song", "Artist", "Netease - Album", 0, 180_000, null)
        val playedAt = Instant.parse("2024-01-01T10:30:00Z").toEpochMilli()
        first.recordListenDeltaNow(song, 6_000, 1, scheduleSync = false,
            eventId = "jan-2", playedAt = playedAt, playedZone = "Pacific/Kiritimati")
        synchronize(first, second, secondDatabase)
        second.recordListenDeltaNow(song, 9_000, 1, scheduleSync = false,
            eventId = "jan-1", playedAt = playedAt, playedZone = "Pacific/Honolulu")
        synchronize(second, first, firstDatabase)
        synchronize(first, second, secondDatabase)
        val midnight = Instant.parse("2024-01-02T00:00:00Z").toEpochMilli()
        val day = 86_400_000L
        for (repo in listOf(first, second)) {
            for ((start, duration) in listOf(midnight - day to 9_000L, midnight to 6_000L)) {
                val query = PlaybackStatsQuery(range = PlaybackStatsTimeRange(start, start + day))
                assertEquals(duration, repo.readSummary(query).totalListenMs)
                val hours = repo.readHourlyListening(query)
                assertEquals(duration, hours[0])
                assertEquals(duration, hours.sum())
                assertEquals(0L, repo.readHourlyListening(query.copy(minimumListenMs = duration + 1)).sum())
            }
        }
    }
}
