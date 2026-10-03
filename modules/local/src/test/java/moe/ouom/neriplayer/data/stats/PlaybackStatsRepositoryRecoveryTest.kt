package moe.ouom.neriplayer.data.stats

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsDeltaRows
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomState
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.any
import java.io.File
import java.io.IOException

class PlaybackStatsRepositoryRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed Room read does not read legacy or acknowledge a playback delta`() = runTest {
        val room = mock(PlaybackStatsRoomStore::class.java)
        doAnswer { throw IOException("read unavailable") }.`when`(room).readPrimaryState()
        val legacy = File(temporaryFolder.root, "playback_stats.json").apply { writeText("old data") }
        val repository = repository(room, backgroundScope)
        assertFalse(repository.awaitInitialized())
        assertTrue(runCatching { repository.recordListenDeltaNow(song(), 100, 0, false) }.exceptionOrNull() is IOException)
        assertEquals("old data", legacy.readText())
        verify(room, never()).readIfRoomPrimary()
    }

    @Test
    fun `Room primary initialization only reads state and pending journal`() = runTest {
        val room = healthyRoom()
        File(temporaryFolder.root, "playback_stats.json").writeText("{broken")
        File(temporaryFolder.root, "playback_stats_meta.json").writeText("{broken")
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())
        assertEquals(50L, repository.statsClearedAtFlow.value)
        assertFalse(repository.hasPendingWrites())
        verify(room, never()).readIfRoomPrimary()
        assertEquals("{broken", File(temporaryFolder.root, "playback_stats_meta.json").readText())
    }

    @Test
    fun `constructor returns before state loading starts`() = runTest {
        var started = false
        val room = healthyRoom()
        `when`(room.readPrimaryState()).thenAnswer { started = true; state() }
        val repository = repository(room, backgroundScope)
        assertFalse(started)
        assertTrue(repository.awaitInitialized())
        assertTrue(started)
        verify(room, never()).readIfRoomPrimary()
    }

    @Test
    fun `durable enqueue acknowledges even if main table application fails and flush retries`() = runTest {
        val room = healthyRoom()
        val pending = mutableListOf<PlaybackStatsPendingDeltaEntity>()
        var fails = true
        `when`(room.pendingDeltas()).thenAnswer { pending.toList() }
        doAnswer { invocation ->
            pending.add(PlaybackStatsPendingDeltaEntity(invocation.getArgument(0), 1, invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4), 50, "device"))
            true
        }.`when`(room).enqueueDelta(sameString("event"), anyString(), anyLong(), eq(0), anyLong(), sameString("device"), isNull(), eq(true))
        doAnswer { if (fails) throw IOException("apply unavailable"); pending.clear(); Unit }.`when`(room).applyDelta(
            any(PlaybackStatsPendingDeltaEntity::class.java) ?: delta(), anyString(), any<(PlaybackStatsDeltaRows) -> PlaybackStatsDeltaRows>() ?: { it }
        )
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())
        repository.recordListenDeltaNow(song(), 100, 0, false, eventId = "event", playedAt = 100)
        assertTrue(repository.hasPendingWrites())
        assertEquals(1, pending.size)
        assertTrue(runCatching { repository.flushPendingWrites() }.exceptionOrNull() is IOException)
        fails = false
        repository.flushPendingWrites()
        assertFalse(repository.hasPendingWrites())
        assertTrue(pending.isEmpty())
        assertFalse(File(temporaryFolder.root, "playback_stats_meta.json").exists())
    }

    @Test
    fun `failed durable enqueue is not acknowledged`() = runTest {
        val room = healthyRoom()
        doAnswer { throw IOException("disk full") }.`when`(room).enqueueDelta(sameString("event"), anyString(), anyLong(), eq(1), anyLong(), sameString("device"), isNull(), eq(true))
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())
        assertTrue(runCatching { repository.recordListenDeltaNow(song(), 100, 1, false, "event", 100) }.exceptionOrNull() is IOException)
        assertFalse(File(temporaryFolder.root, "playback_stats.json").exists())
    }

    @Test
    fun `device identity read failure does not fall back to shared local shard`() = runTest {
        val room = healthyRoom()
        val repository = repository(room, backgroundScope) { throw IOException("identity unavailable") }
        assertTrue(repository.awaitInitialized())
        assertTrue(runCatching { repository.recordListenDeltaNow(song(), 100, 1, false) }.exceptionOrNull() is IOException)
        verify(room, never()).enqueueDelta(anyString(), anyString(), anyLong(), eq(1), anyLong(), sameString("local"), isNull(), eq(true))
    }

    @Test
    fun `initial load may retry without switching primary stores`() = runTest {
        val room = healthyRoom()
        var readFails = true
        `when`(room.readPrimaryState()).thenAnswer { if (readFails) throw IOException("temporary"); state() }
        val repository = repository(room, backgroundScope)
        assertFalse(repository.awaitInitialized())
        readFails = false
        assertTrue(repository.awaitInitialized())
        assertFalse(repository.hasPendingWrites())
        verify(room, never()).readIfRoomPrimary()
    }

    @Test
    fun `cancellation before durable enqueue stays cancellation`() = runTest {
        val room = healthyRoom()
        doAnswer { throw CancellationException("cancel") }.`when`(room).enqueueDelta(sameString("event"), anyString(), anyLong(), eq(1), anyLong(), sameString("device"), isNull(), eq(true))
        val repository = repository(room, backgroundScope)
        assertTrue(repository.awaitInitialized())
        assertTrue(runCatching { repository.recordListenDeltaNow(song(), 100, 1, false, "event", 100) }.exceptionOrNull() is CancellationException)
    }

    private suspend fun healthyRoom(): PlaybackStatsRoomStore = mock(PlaybackStatsRoomStore::class.java).also {
        `when`(it.readPrimaryState()).thenReturn(state())
        `when`(it.pendingDeltas()).thenReturn(emptyList())
        `when`(it.clearedAtFlow).thenReturn(flowOf(50L))
    }
    private fun repository(room: PlaybackStatsRoomStore, scope: CoroutineScope, device: () -> String = { "device" }): PlaybackStatsRepository {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        return PlaybackStatsRepository(context, room, scope, device)
    }
    private fun state() = PlaybackStatsRoomState(1, 50, 50)
    private fun delta() = PlaybackStatsPendingDeltaEntity("event", 1, "{}", 100, 0, 100, 50, "device")
    private fun sameString(value: String): String = eq(value) ?: value
    private fun song() = SongItem(7, "song", "artist", "netease", 0, 180_000, null)
}
