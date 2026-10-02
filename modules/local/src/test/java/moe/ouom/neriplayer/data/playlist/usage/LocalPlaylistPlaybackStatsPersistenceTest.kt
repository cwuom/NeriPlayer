package moe.ouom.neriplayer.data.playlist.usage

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistPlaybackRoomStore
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

class LocalPlaylistPlaybackStatsPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `durable playback event cannot acknowledge JSON fallback across process reopen`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        val file = File(temporary.root, "local_playlist_playback_stats.json")
        file.writeText(Gson().toJson(listOf(original())))
        val originalBytes = file.readBytes()
        doAnswer { throw IOException("Room promotion unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())

        repeat(2) {
            val reopened = repository(room)
            val failure = runCatching { reopened.recordPlayNow(1, 200, "durable-event") }.exceptionOrNull()
            assertTrue("durable event requires a persisted receipt", failure is IOException)
            assertEquals(1L, reopened.statsFlow.value.single().totalPlayCount)
            assertArrayEquals(originalBytes, file.readBytes())
        }
        verify(room, never()).writeIncrementalOnce(anyList(), anyList(), anyString(), anyString(), anyLong())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `durable playback retries promotion and reopens without counting the same event twice`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        val file = File(temporary.root, "local_playlist_playback_stats.json")
        file.writeText(Gson().toJson(listOf(original())))
        var primary: List<LocalPlaylistPlaybackStat>? = null
        var unavailable = true
        val receipts = mutableSetOf<String>()
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            if (unavailable) throw IOException("promotion unavailable")
            primary = it.getArgument(0)
            Unit
        }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer {
            if (!receipts.add(it.getArgument(2))) false else {
                primary = it.getArgument(1)
                true
            }
        }.`when`(room).writeIncrementalOnce(anyList(), anyList(), anyString(), anyString(), anyLong())
        val repository = repository(room)
        assertTrue(runCatching { repository.recordPlayNow(1, 200, "durable-event") }.exceptionOrNull() is IOException)
        unavailable = false
        repository.recordPlayNow(1, 200, "durable-event")
        assertEquals(2L, repository.syncSnapshot().stats.single().totalPlayCount)
        repository(room).recordPlayNow(1, 200, "durable-event")
        assertEquals(2L, primary!!.single().totalPlayCount)
        assertEquals(setOf("durable-event"), receipts)
        assertEquals(1L, Gson().fromJson(file.readText(), Array<LocalPlaylistPlaybackStat>::class.java).single().totalPlayCount)
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `cancelled promotion confirmation retries the real Room primary before event mutation`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        File(temporary.root, "local_playlist_playback_stats.json").writeText(Gson().toJson(listOf(original())))
        var primary: List<LocalPlaylistPlaybackStat>? = null
        var initialPromotion = true
        var promotions = 0
        val cancelled = CancellationException("promotion committed before cancellation")
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            promotions++
            if (initialPromotion) throw IOException("initial promotion unavailable")
            primary = it.getArgument(0)
            throw cancelled
        }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer { primary = it.getArgument(1); true }.`when`(room).writeIncrementalOnce(anyList(), anyList(), anyString(), anyString(), anyLong())
        val repository = repository(room)
        initialPromotion = false
        assertCancellation(cancelled, runCatching { repository.recordPlayNow(1, 200, "durable-event") }.exceptionOrNull())
        assertEquals(1L, repository.statsFlow.value.single().totalPlayCount)
        repository.recordPlayNow(1, 200, "durable-event")
        assertEquals(2, promotions)
        assertEquals(2L, primary!!.single().totalPlayCount)
        verify(room, times(1)).writeIncrementalOnce(anyList(), anyList(), anyString(), anyString(), anyLong())
    }

    @Test
    fun `same playback event retries a cancelled Room transaction without dropping its increment`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var primary = listOf(original())
        var attempts = 0
        val cancelled = CancellationException("cancelled before playlist commit")
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            if (++attempts == 1) throw cancelled
            primary = it.getArgument(1)
            true
        }.`when`(room).writeIncrementalOnce(anyList(), anyList(), anyString(), anyString(), anyLong())
        val repository = repository(room)
        assertCancellation(cancelled, runCatching { repository.recordPlayNow(1, 200, "event") }.exceptionOrNull())
        assertEquals(1L, primary.single().totalPlayCount)
        repository.recordPlayNow(1, 200, "event")
        assertEquals(2L, primary.single().totalPlayCount)
        assertEquals(2L, repository.syncSnapshot().stats.single().totalPlayCount)
    }

    @Test
    fun `ordinary JSON marker cancellation flushes the pending play without incrementing again`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        File(temporary.root, "local_playlist_playback_stats.json").writeText(Gson().toJson(listOf(original())))
        doAnswer { throw IOException("Room promotion unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        var markers = 0
        val cancelled = CancellationException("JSON marker committed before cancellation")
        doAnswer {
            if (++markers == 1) throw cancelled
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room)
        assertCancellation(cancelled, runCatching { repository.recordPlayNow(1, 200) }.exceptionOrNull())
        assertTrue(repository.awaitInitialized())
        assertEquals(2L, repository.syncSnapshot().stats.single().totalPlayCount)
        assertEquals(2L, repository(room).statsFlow.value.single().totalPlayCount)
    }

    @Test
    fun `failed duplicate confirmation cannot persist an unaccepted second increment`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        val actual = listOf(original().copy(totalPlayCount = 2))
        var reads = 0
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (++reads == 2) throw IOException("duplicate confirmation unavailable")
            actual
        }
        `when`(room.writeIncrementalOnce(anyList(), anyList(), anyString(), anyString(), anyLong())).thenReturn(false)
        val repository = repository(room)
        assertTrue(runCatching { repository.recordPlayNow(1, 200, "already-recorded") }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        assertEquals(2L, repository.syncSnapshot().stats.single().totalPlayCount)
        verify(room, never()).writeIncremental(anyList(), anyList(), anyLong())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `same playback event is not counted again after a committed Room cancellation`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var primary = listOf(original())
        var writes = 0
        val cancelled = CancellationException("play event committed before cancellation")
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            primary = it.getArgument(1)
            if (++writes == 1) throw cancelled
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer {
            primary = it.getArgument(1)
            if (++writes == 1) throw cancelled
            true
        }.`when`(room).writeIncrementalOnce(anyList(), anyList(), anyString(), anyString(), anyLong())
        val repository = repository(room)

        assertCancellation(cancelled, runCatching { repository.recordPlayNow(1, 200, "event") }.exceptionOrNull())
        repository.recordPlayNow(1, 200, "event")

        assertEquals(2L, primary.single().totalPlayCount)
        assertEquals(2L, repository.syncSnapshot().stats.single().totalPlayCount)
    }

    @Test
    fun `unreadable Room primary cannot replace playback from a stale JSON snapshot`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        val stale = File(temporary.root, "local_playlist_playback_stats.json").also { it.writeText(Gson().toJson(listOf(original()))) }
        val originalBytes = stale.readBytes()
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary temporarily unavailable") }

        val repository = repository(room)
        assertTrue(repository.statsFlow.value.isEmpty())
        assertTrue(runCatching { repository.applyMergedStats(listOf(remote()), emptyList()) }.exceptionOrNull() is IOException)
        assertTrue(originalBytes.contentEquals(stale.readBytes()))
        verify(room, never()).writeIncremental(anyList(), anyList(), anyLong())
        verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `playback recovery retries the original primary and retains all playlist counters`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var unavailable = true
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (unavailable) throw IOException("primary unavailable") else listOf(original(), original().copy(playlistId = 2))
        }
        val repository = repository(room)
        assertFalse(repository.awaitInitialized())
        assertTrue(runCatching { repository.syncSnapshot() }.exceptionOrNull() is IOException)
        unavailable = false
        assertTrue(repository.awaitInitialized())
        assertEquals(setOf(1L, 2L), repository.syncSnapshot().stats.map { it.playlistId }.toSet())
        verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
    }

    @Test
    fun `unknown playback refuses ordinary recording without writing legacy data`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary unavailable") }
        val repository = repository(room)
        repository.recordPlayNow(2, 200)
        assertTrue(repository.statsFlow.value.isEmpty())
        assertFalse(File(temporary.root, "local_playlist_playback_stats.json").exists())
        verify(room, never()).writeIncremental(anyList(), anyList(), anyLong())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `playback recovery cancellation propagates and cannot import legacy`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary unavailable") }
        val repository = repository(room)
        val cancelled = CancellationException("recovery cancelled")
        doAnswer { throw cancelled }.`when`(room).readIfRoomPrimary()
        assertCancellation(cancelled, runCatching { repository.awaitInitialized() }.exceptionOrNull())
        assertTrue(repository.statsFlow.value.isEmpty())
        verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `invalid playback JSON cannot import empty state and repair can recover`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        val file = File(temporary.root, "local_playlist_playback_stats.json").also { it.writeText("null") }
        val repository = repository(room)
        assertFalse(repository.awaitInitialized())
        verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
        assertEquals("null", file.readText())
        file.writeText(Gson().toJson(listOf(original())))
        assertTrue(repository.awaitInitialized())
        assertEquals(listOf(1L), repository.syncSnapshot().stats.map { it.playlistId })
        verify(room).importLegacyAndPromote(org.mockito.ArgumentMatchers.eq(listOf(original())) ?: emptyList(), anyLong())
    }

    @Test
    fun `ordinary failed playback save flushes full counters once before allowing sync`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var roomPrimary = true
        var markerFails = true
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) listOf(original()) else null }
        failRoomWrites(room)
        doAnswer { throw IOException("import unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer {
            if (markerFails) throw IOException("marker unavailable")
            roomPrimary = false
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room)
        repository.recordPlayNow(1, 200)
        repository.recordPlayNow(1, 300)
        val current = repository.statsFlow.value
        assertEquals(3L, current.single().totalPlayCount)
        assertTrue(runCatching { repository.syncSnapshot() }.exceptionOrNull() is IOException)
        assertFalse(repository.awaitInitialized())
        markerFails = false
        assertTrue(repository.awaitInitialized())
        repeat(2) { assertTrue(repository.awaitInitialized()) }
        assertEquals(3L, repository.syncSnapshot().stats.single().totalPlayCount)
        assertEquals(current, repository(room).statsFlow.value)
    }

    @Test
    fun `committed then cancelled playback sync recovers actual counters rather than old UI`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var primary = listOf(original())
        var cancelAfterCommit = true
        val cancelled = CancellationException("committed before cancellation delivery")
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            primary = it.getArgument(1)
            if (cancelAfterCommit) { cancelAfterCommit = false; throw cancelled }
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        val repository = repository(room)
        assertCancellation(cancelled, runCatching { repository.applyMergedStats(listOf(remote().copy(playlistId = 2)), emptyList()) }.exceptionOrNull())
        assertEquals(setOf(1L, 2L), primary.map { it.playlistId }.toSet())
        assertTrue(runCatching { repository.syncSnapshot() }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        assertEquals(primary, repository.statsFlow.value)
        assertEquals(setOf(1L, 2L), repository.syncSnapshot().stats.map { it.playlistId }.toSet())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `committed then cancelled playback marker recovers the switched JSON primary`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var roomPrimary = true
        var cancelAfterMarker = true
        val cancelled = CancellationException("marker committed before cancellation delivery")
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) listOf(original()) else null }
        failRoomWrites(room)
        doAnswer {
            roomPrimary = false
            if (cancelAfterMarker) { cancelAfterMarker = false; throw cancelled }
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room)
        assertCancellation(cancelled, runCatching { repository.applyMergedStats(listOf(remote().copy(playlistId = 2)), emptyList()) }.exceptionOrNull())
        assertTrue(runCatching { repository.syncSnapshot() }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        assertEquals(setOf(1L, 2L), repository.syncSnapshot().stats.map { it.playlistId }.toSet())
        assertEquals(repository.statsFlow.value, repository(room).statsFlow.value)
    }

    @Test
    fun `ordinary committed playback cancellation recovers authority without counting the play twice`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var primary = listOf(original())
        var unavailable = false
        val previousWrites = mutableListOf<List<LocalPlaylistPlaybackStat>>()
        val cancelled = CancellationException("ordinary save committed before cancellation")
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (unavailable) throw IOException("authority temporarily unavailable")
            primary
        }
        doAnswer {
            previousWrites += it.getArgument<List<LocalPlaylistPlaybackStat>>(0)
            primary = it.getArgument(1)
            if (previousWrites.size == 1) throw cancelled
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        val repository = repository(room)
        assertCancellation(cancelled, runCatching { repository.recordPlayNow(1, 200) }.exceptionOrNull())
        unavailable = true
        assertFalse(repository.awaitInitialized())
        assertTrue(runCatching { repository.syncSnapshot() }.exceptionOrNull() is IOException)
        repository.recordPlayNow(1, 300)
        assertEquals(2L, repository.statsFlow.value.single().totalPlayCount)
        unavailable = false
        assertTrue(repository.awaitInitialized())
        assertEquals(1, previousWrites.size)
        repository.recordPlayNow(1, 300)
        assertEquals(2L, previousWrites.last().single().totalPlayCount)
        assertEquals(3L, primary.single().totalPlayCount)
        assertEquals(3L, repository.syncSnapshot().stats.single().totalPlayCount)
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `failed marker preserves old primary across retries and repaired marker persists replacement`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var primary = true
        var markerFails = true
        var attempts = 0
        `when`(room.readIfRoomPrimary()).thenAnswer { if (primary) listOf(original()) else null }
        failRoomWrites(room)
        doAnswer { throw IOException("import failed") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer {
            attempts++
            if (markerFails) throw IOException("marker failed")
            primary = false
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room)
        repeat(2) {
            assertTrue(runCatching { repository.applyMergedStats(listOf(remote()), emptyList()) }.exceptionOrNull() is IOException)
            assertEquals(listOf(original()), repository.statsFlow.value)
            assertEquals(listOf(original()), repository(room).statsFlow.value)
        }
        assertEquals(2, attempts)
        markerFails = false
        repository.applyMergedStats(listOf(remote()), emptyList())
        assertEquals(4L, repository.statsFlow.value.single().totalPlayCount)
        assertEquals(repository.statsFlow.value, repository(room).statsFlow.value)
    }

    @Test
    fun `JSON failure cannot change primary and repaired target retries without data loss`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(original()))
        failRoomWrites(room)
        val file = File(temporary.root, "local_playlist_playback_stats.json")
        assertTrue(file.mkdir())
        val blocker = File(file, "blocker").also { it.writeText("blocked") }
        val repository = repository(room)
        repeat(2) {
            assertTrue(runCatching { repository.applyMergedStats(listOf(remote()), emptyList()) }.exceptionOrNull() is IOException)
            assertEquals(listOf(original()), repository.statsFlow.value)
        }
        verify(room, never()).markLegacyJsonPrimary(anyLong())
        assertEquals(listOf(original()), repository(room).statsFlow.value)
        assertTrue(blocker.delete())
        assertTrue(file.delete())
        repository.applyMergedStats(listOf(remote()), emptyList())
        verify(room).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `Room cancellation does not enter fallback and marker cancellation cannot complete apply`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        val cancellation = CancellationException("cancelled")
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(original()))
        doAnswer { throw cancellation }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        val repository = repository(room)
        assertCancellation(cancellation, runCatching { repository.applyMergedStats(listOf(remote()), emptyList()) }.exceptionOrNull())
        assertFalse(File(temporary.root, "local_playlist_playback_stats.json").exists())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
        failRoomWrites(room)
        doAnswer { throw cancellation }.`when`(room).markLegacyJsonPrimary(anyLong())
        repeat(2) {
            assertCancellation(cancellation, runCatching { repository.applyMergedStats(listOf(remote()), emptyList()) }.exceptionOrNull())
            assertEquals(listOf(original()), repository.statsFlow.value)
        }
    }

    @Test
    fun `successful Room persistence occurs before publishing and survives reopen`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var primary = listOf(original())
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        val repository = repository(room)
        doAnswer {
            assertEquals(listOf(original()), repository.statsFlow.value)
            primary = it.getArgument(1)
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        repository.applyMergedStats(listOf(remote()), emptyList())
        assertEquals(primary, repository.statsFlow.value)
        assertEquals(primary, repository(room).statsFlow.value)
        verify(room, never()).markLegacyJsonPrimary(anyLong())
    }

    @Test
    fun `ordinary playback updates remain compatible when fallback fails`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(original()))
        failRoomWrites(room)
        doAnswer { throw IOException("marker failed") }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room)
        repository.recordPlayNow(1, 300)
        assertTrue(repository.statsFlow.value.single().totalPlayCount > original().totalPlayCount)
        assertEquals(listOf(original()), repository(room).statsFlow.value)
    }

    @Test
    fun `cancelled ordinary playback save retries the same snapshot before acknowledging durability`() = runTest {
        val room = mock(LocalPlaylistPlaybackRoomStore::class.java)
        var primary = listOf(original())
        var roomPrimary = true
        var attempts = 0
        val cancellation = CancellationException("ordinary Room save cancelled")
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) primary else null }
        doAnswer {
            attempts++
            if (attempts == 1) throw cancellation
            val previous = it.getArgument<List<LocalPlaylistPlaybackStat>>(0)
            val next = it.getArgument<List<LocalPlaylistPlaybackStat>>(1)
            if (previous != next) primary = next
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { throw IOException("Room import unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer { roomPrimary = false; Unit }.`when`(room).markLegacyJsonPrimary(anyLong())
        val repository = repository(room)

        assertCancellation(cancellation, runCatching { repository.recordPlayNow(1, 300) }.exceptionOrNull())
        val replacement = repository.statsFlow.value
        assertTrue(replacement.single().totalPlayCount > original().totalPlayCount)
        assertEquals(listOf(original()), primary)
        assertFalse(File(temporary.root, "local_playlist_playback_stats.json").exists())
        verify(room, never()).markLegacyJsonPrimary(anyLong())
        assertEquals(primary, repository(room).statsFlow.value)

        assertTrue(runCatching { repository.syncSnapshot() }.exceptionOrNull() is IOException)
        assertTrue(repository.awaitInitialized())
        val snapshot = repository.syncSnapshot()
        repository.applyMergedStats(snapshot.stats, snapshot.buckets)
        assertEquals(2, attempts)
        assertEquals(replacement, repository.statsFlow.value)
        assertEquals(replacement, repository(room).statsFlow.value)
    }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue(actual is CancellationException)
        assertEquals(expected.message, actual?.message)
    }

    private suspend fun failRoomWrites(room: LocalPlaylistPlaybackRoomStore) {
        doAnswer { throw IOException("Room write failed") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
    }

    private fun repository(room: LocalPlaylistPlaybackRoomStore): LocalPlaylistPlaybackStatsRepository {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporary.root)
        val constructor = LocalPlaylistPlaybackStatsRepository::class.java.getDeclaredConstructor(Context::class.java, LocalPlaylistPlaybackRoomStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context, room)
    }

    private fun original() = LocalPlaylistPlaybackStat(1, totalPlayCount = 1, firstPlayedAt = 100, lastPlayedAt = 100)
    private fun remote() = SyncLocalPlaylistPlaybackStat(1, totalPlayCount = 4, firstPlayedAt = 100, lastPlayedAt = 200)
}
