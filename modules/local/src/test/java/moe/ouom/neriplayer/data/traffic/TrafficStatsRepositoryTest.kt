package moe.ouom.neriplayer.data.traffic

import android.app.Application
import com.google.gson.Gson
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.TrafficStatsRoomStore
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.traffic.TrafficStatsBucket
import moe.ouom.neriplayer.data.model.traffic.TrafficUsageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficStatsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `Room primary takes precedence over malformed legacy JSON`() = runTest {
        val existing = listOf(TrafficStatsBucket(dayStartAt = 100L, mobileBytes = 300L))
        val fixture = fixture(existing) { file.writeText("broken JSON") }

        assertEquals(existing, fixture.stats)
        assertEquals("broken JSON", fixture.file.readText())
        assertTrue(fixture.imports.isEmpty())
        assertEquals(TrafficNetworkType.MOBILE, fixture.repository.currentNetworkType())
    }

    @Test
    fun `legacy JSON drops invalid dates sorts buckets and promotes original values`() = runTest {
        val earlier = TrafficStatsBucket(dayStartAt = 100L, wifiBytes = 7L,
            playbackNetworkBytes = 7L, requestCount = 2)
        val later = TrafficStatsBucket(dayStartAt = 200L, roamingBytes = 9L,
            downloadNetworkBytes = 9L, cacheHitBytes = 3L, cacheHitCount = 1)
        val fixture = fixture(null) {
            file.writeText(Gson().toJson(listOf(later, earlier,
                TrafficStatsBucket(dayStartAt = 0L), TrafficStatsBucket(dayStartAt = -1L))))
        }

        assertEquals(listOf(earlier, later), fixture.stats)
        assertEquals(listOf(listOf(earlier, later)), fixture.imports)
    }

    @Test
    fun `missing null and malformed legacy JSON all promote an empty snapshot`() = runTest {
        listOf(null, "null", "{broken").forEach { content ->
            val fixture = fixture(null) {
                if (content != null) file.writeText(content)
            }

            assertTrue(fixture.stats.isEmpty())
            assertEquals(listOf(emptyList<TrafficStatsBucket>()), fixture.imports)
        }
    }

    @Test
    fun `Room read failure never promotes legacy JSON or writes over stored data`() = runTest {
        val legacy = listOf(TrafficStatsBucket(dayStartAt = 100L, mobileBytes = 50L))
        val fixture = fixture {
            file.writeText(Gson().toJson(legacy))
            doThrow(IllegalStateException("read failed")).`when`(room).readIfRoomPrimary()
        }

        assertTrue(fixture.stats.isEmpty())
        fixture.repository.recordCacheHitBytes(7L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(7L, fixture.stats.single().cacheHitBytes)
        assertTrue(fixture.imports.isEmpty())
        assertTrue(fixture.writes.isEmpty())
        assertEquals(legacy, fixture.diskStats())
        verify(fixture.room, times(0)).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `activity during an in flight Room write keeps Room as the store`() = runTest {
        var suspendedWrite: Continuation<Unit>? = null
        val fixture = fixture {
            doAnswer { invocation ->
                writes += SnapshotWrite(invocation.getArgument(0), invocation.getArgument(1))
                if (suspendedWrite != null) return@doAnswer Unit
                // Mockito 只在 rawArguments 中保留 suspend 函数的 Continuation
                @Suppress("UNCHECKED_CAST")
                suspendedWrite = invocation.rawArguments.last() as Continuation<Unit>
                COROUTINE_SUSPENDED
            }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        }
        fixture.repository.recordCacheHitBytes(10L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        val write = requireNotNull(suspendedWrite)

        fixture.repository.recordCacheHitBytes(20L)
        runCurrent()
        // 与 Room 事务一致：所属协程被取消时以 CancellationException 结束
        val cancelled = write.context[Job]?.isCancelled == true
        write.resumeWith(
            if (cancelled) Result.failure(CancellationException("transaction cancelled"))
            else Result.success(Unit)
        )
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(2, fixture.writes.size)
        assertEquals(30L, fixture.writes.last().next.single().cacheHitBytes)
        assertFalse(fixture.file.exists())
        verify(fixture.room, times(0)).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `failed legacy promotion keeps JSON primary and retains accumulated bytes`() = runTest {
        val fixture = fixture(null) {
            file.writeText("[]")
            doThrow(IllegalStateException("import failed")).`when`(room)
                .importLegacyAndPromote(anyList(), anyLong())
        }
        fixture.repository.recordNetworkBytes(TrafficNetworkType.WIFI, 10L,
            TrafficUsageSource.PLAYBACK)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(fixture.stats, fixture.diskStats())
        assertEquals(10L, fixture.diskStats().single().wifiBytes)
        assertTrue(fixture.writes.isEmpty())
        verify(fixture.room).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `all billing classes sources and cache hits update one daily bucket`() = runTest {
        val fixture = fixture()
        fixture.repository.recordNetworkBytes(TrafficNetworkType.WIFI, 10L,
            TrafficUsageSource.PLAYBACK)
        fixture.repository.recordNetworkBytes(TrafficNetworkType.MOBILE, 20L,
            TrafficUsageSource.DOWNLOAD)
        fixture.repository.recordNetworkBytes(TrafficNetworkType.ROAMING, 30L,
            TrafficUsageSource.PLAYBACK)
        fixture.repository.recordCacheHitBytes(40L)
        runCurrent()

        assertEquals(listOf(TrafficStatsBucket(dayStartAt = fixture.dayStart,
            wifiBytes = 10L, mobileBytes = 20L, roamingBytes = 30L,
            playbackNetworkBytes = 40L, downloadNetworkBytes = 20L,
            cacheHitBytes = 40L, requestCount = 3, cacheHitCount = 1)), fixture.stats)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun `nonpositive bytes do not create a bucket or schedule persistence`() = runTest {
        val fixture = fixture()
        fixture.repository.recordNetworkBytes(TrafficNetworkType.WIFI, 0L,
            TrafficUsageSource.PLAYBACK)
        fixture.repository.recordNetworkBytes(TrafficNetworkType.MOBILE, -10L,
            TrafficUsageSource.DOWNLOAD)
        fixture.repository.recordCacheHitBytes(0L)
        fixture.repository.recordCacheHitBytes(-10L)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(fixture.stats.isEmpty())
        assertTrue(fixture.writes.isEmpty())
        assertFalse(fixture.file.exists())
    }

    @Test
    fun `an existing daily bucket is replaced without losing previous days`() = runTest {
        val fixture = fixture()
        fixture.repository.recordCacheHitBytes(12L)
        runCurrent()
        val firstDay = fixture.stats.single()
        fixture.clock.set(GregorianCalendar(2026, Calendar.OCTOBER, 2, 12, 0).timeInMillis)
        fixture.repository.recordCacheHitBytes(5L)
        fixture.repository.recordCacheHitBytes(7L)
        runCurrent()

        val nextDay = GregorianCalendar(2026, Calendar.OCTOBER, 2).timeInMillis
        assertEquals(listOf(firstDay, TrafficStatsBucket(dayStartAt = nextDay,
            cacheHitBytes = 12L, cacheHitCount = 2)), fixture.stats)
    }

    @Test
    fun `new activity restarts the debounce and writes only the latest snapshot`() = runTest {
        val fixture = fixture()
        fixture.repository.recordCacheHitBytes(10L)
        runCurrent()
        advanceTimeBy(4_000L)
        fixture.repository.recordCacheHitBytes(20L)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(fixture.writes.isEmpty())
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(emptyList<TrafficStatsBucket>(), fixture.writes.single().previous)
        assertEquals(fixture.stats, fixture.writes.single().next)
        assertEquals(30L, fixture.writes.single().next.single().cacheHitBytes)
        assertFalse(fixture.file.exists())

        val previous = fixture.stats
        fixture.repository.recordCacheHitBytes(7L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(previous, fixture.writes.last().previous)
        assertEquals(fixture.stats, fixture.writes.last().next)
    }

    @Test
    fun `Room write failure persists JSON and later writes remain on JSON fallback`() = runTest {
        val fixture = fixture {
            doThrow(IllegalStateException("write failed")).`when`(room)
                .writeIncremental(anyList(), anyList(), anyLong())
        }
        fixture.repository.recordCacheHitBytes(10L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(fixture.stats, fixture.diskStats())

        fixture.repository.recordCacheHitBytes(20L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(fixture.stats, fixture.diskStats())
        assertEquals(30L, fixture.diskStats().single().cacheHitBytes)
        verify(fixture.room, times(1)).writeIncremental(anyList(), anyList(), anyLong())
        verify(fixture.room, times(2)).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `fallback metadata failure does not discard a successfully written JSON snapshot`() = runTest {
        val fixture = fixture {
            doThrow(IllegalStateException("write failed")).`when`(room)
                .writeIncremental(anyList(), anyList(), anyLong())
            doThrow(IllegalStateException("metadata failed")).`when`(room)
                .markLegacyJsonPrimary(anyLong())
        }
        fixture.repository.recordCacheHitBytes(10L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(fixture.stats, fixture.diskStats())
        fixture.repository.clearAll()
        runCurrent()
        assertTrue(fixture.stats.isEmpty())
        assertTrue(fixture.diskStats().isEmpty())
    }

    @Test
    fun `failed JSON replacement preserves existing target and retries on later activity`() = runTest {
        val fixture = fixture {
            assertTrue(file.mkdir())
            File(file, "marker").writeText("preserve")
            doThrow(IllegalStateException("write failed")).`when`(room)
                .writeIncremental(anyList(), anyList(), anyLong())
        }
        fixture.repository.recordCacheHitBytes(10L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals("preserve", File(fixture.file, "marker").readText())
        assertEquals(10L, fixture.stats.single().cacheHitBytes)
        verify(fixture.room, times(0)).markLegacyJsonPrimary(anyLong())

        assertTrue(File(fixture.file, "marker").delete())
        assertTrue(fixture.file.delete())
        fixture.repository.recordCacheHitBytes(20L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(fixture.stats, fixture.diskStats())
        assertEquals(30L, fixture.diskStats().single().cacheHitBytes)
        verify(fixture.room).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `clear removes all buckets immediately and cancels the older delayed write`() = runTest {
        val fixture = fixture(listOf(TrafficStatsBucket(dayStartAt = 100L, wifiBytes = 50L)))
        fixture.repository.recordCacheHitBytes(10L)
        runCurrent()
        fixture.repository.clearAll()
        runCurrent()

        assertTrue(fixture.stats.isEmpty())
        assertTrue(fixture.writes.single().next.isEmpty())
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(1, fixture.writes.size)
    }

    @Test
    fun `continuous traffic is still persisted within the maximum deferral`() = runTest {
        val fixture = fixture()
        repeat(10) {
            fixture.repository.recordCacheHitBytes(1L)
            runCurrent()
            advanceTimeBy(4_000L)
            fixture.clock.addAndGet(4_000L)
            runCurrent()
        }

        val deferredWrite = fixture.writes.first().next.single()
        assertEquals(8L, deferredWrite.cacheHitBytes)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(10L, fixture.writes.last().next.single().cacheHitBytes)
    }

    @Test
    fun `persist delay shrinks to the remaining deferral budget`() {
        assertEquals(5_000L, trafficPersistDelayMs(pendingForMs = 0L))
        assertEquals(4_000L, trafficPersistDelayMs(pendingForMs = 26_000L))
        assertEquals(0L, trafficPersistDelayMs(pendingForMs = 30_000L))
        assertEquals(0L, trafficPersistDelayMs(pendingForMs = 45_000L))
        assertEquals(5_000L, trafficPersistDelayMs(pendingForMs = -60_000L))
    }

    private suspend fun TestScope.fixture(
        initial: List<TrafficStatsBucket>? = emptyList(),
        prepare: suspend Fixture.() -> Unit = {}
    ): Fixture {
        val fixture = Fixture(tempFolder.newFolder())
        val app = mock(Application::class.java)
        `when`(app.filesDir).thenReturn(fixture.directory)
        `when`(app.applicationContext).thenReturn(app)
        `when`(fixture.room.readIfRoomPrimary()).thenReturn(initial)
        `when`(fixture.room.importLegacyAndPromote(anyList(), anyLong())).thenAnswer { invocation ->
            fixture.imports += invocation.getArgument<List<TrafficStatsBucket>>(0)
            Unit
        }
        `when`(fixture.room.writeIncremental(anyList(), anyList(), anyLong())).thenAnswer { invocation ->
            fixture.writes += SnapshotWrite(invocation.getArgument(0), invocation.getArgument(1))
            Unit
        }
        fixture.prepare()
        fixture.repository = TrafficStatsRepository(app, fixture.room, backgroundScope, fixture.clock::get)
        return fixture
    }

    private data class SnapshotWrite(
        val previous: List<TrafficStatsBucket>,
        val next: List<TrafficStatsBucket>
    )

    private class Fixture(val directory: File) {
        val dayStart = GregorianCalendar(2026, Calendar.OCTOBER, 1).timeInMillis
        val clock = AtomicLong(GregorianCalendar(2026, Calendar.OCTOBER, 1, 12, 0).timeInMillis)
        val room = mock(TrafficStatsRoomStore::class.java)
        val file = File(directory, "traffic_stats_daily.json")
        val imports = mutableListOf<List<TrafficStatsBucket>>()
        val writes = mutableListOf<SnapshotWrite>()
        lateinit var repository: TrafficStatsRepository
        val stats get() = repository.dailyStatsFlow.value

        fun diskStats(): List<TrafficStatsBucket> =
            Gson().fromJson(file.readText(), Array<TrafficStatsBucket>::class.java).toList()
    }
}
