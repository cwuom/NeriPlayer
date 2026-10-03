package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LocalPlaylistOutboxRecoveryTest : LocalPlaylistRepositoryTestSupport() {
    private val primary = listOf(LocalPlaylist(id = 7, name = "committed"))
    private val digest = LocalPlaylistRoomStore.domainDigest(primary)
    private val committed = LocalPlaylistSyncMutationOutbox(listOf(LocalPlaylistSyncMutation(
        expectedPrimaryDigest = digest, deletedPlaylistIds = listOf(9)
    )))

    @Test fun `unknown Room outbox blocks startup and repaired same instance replays original chain`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        val failure = IOException("outbox unavailable")
        var unavailable = true
        var pending: LocalPlaylistSyncMutationOutbox? = committed
        var roomClears = 0
        `when`(room.readPendingSyncMutationOutbox()).thenAnswer { if (unavailable) throw failure else pending }
        doAnswer { roomClears++; pending = null; Unit }.`when`(room).clearPendingSyncMutationOutbox()
        val storage = OutboxStorage(null)
        val mutations = RetryMutationStore()
        val repository = open("room-read", room, storage, mutations)

        assertFalse(repository.awaitInitialized())
        assertFalse(repository.initializationReadyFlow.value)
        assertTrue(repository.syncMutationPending.value)
        assertTrue(repository.playlists.value.isEmpty())
        assertEquals(0, roomClears)
        assertEquals(0, storage.clears)
        assertTrue(mutations.applied.isEmpty())
        assertTrue(repository.roomStorageEnabled)
        assertOriginalCause(failure, runCatching { repository.recoverPendingSyncMutation(digest) }.exceptionOrNull())
        verify(room, never()).markLegacyJsonPrimary(anyString(), anyLong())
        verify(room, never()).writeIncremental(anyList(), anyList(), anyString(), anyLong())

        unavailable = false
        assertTrue(repository.awaitInitialized())
        assertEquals(primary, repository.playlists.value)
        assertEquals(listOf(9L), mutations.applied.single().deletedPlaylistIds)
        assertEquals(1, roomClears)
        assertFalse(repository.syncMutationPending.value)
    }

    @Test fun `damaged legacy tail cannot clear or apply its earlier committed prefix`() = runTest {
        val storage = OutboxStorage(Gson().toJson(primary))
        val prefix = Gson().toJson(committed.mutations.single())
        storage.pending = "{\"mutations\":[$prefix,{\"expectedPrimaryDigest\":null}]}"
        val original = storage.pending
        val mutations = RetryMutationStore()
        val repository = open("legacy-corruption", null, storage, mutations)

        assertFalse(repository.awaitInitialized())
        assertTrue(repository.syncMutationPending.value)
        assertEquals(original, storage.pending)
        assertEquals(0, storage.clears)
        assertTrue(mutations.applied.isEmpty())

        val tail = committed.mutations.single().copy(expectedPrimaryDigest = "f".repeat(64), deletedPlaylistIds = listOf(10))
        storage.pending = Gson().toJson(LocalPlaylistSyncMutationOutbox(committed.mutations + tail))
        assertTrue(repository.awaitInitialized())
        assertEquals(listOf(9L), mutations.applied.flatMap { it.deletedPlaylistIds })
        assertEquals(null, storage.pending)
        assertFalse(repository.syncMutationPending.value)
    }

    @Test fun `startup checked apply failure keeps chain and same instance retries before ready`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        var pending: LocalPlaylistSyncMutationOutbox? = committed
        `when`(room.readPendingSyncMutationOutbox()).thenAnswer { pending }
        doAnswer { pending = null; Unit }.`when`(room).clearPendingSyncMutationOutbox()
        val mutations = RetryMutationStore().also { it.failure = IOException("checked commit rejected") }
        val storage = OutboxStorage(null)
        val repository = open("checked-apply", room, storage, mutations)

        assertFalse(repository.awaitInitialized())
        assertEquals(committed, pending)
        assertEquals(0, storage.clears)
        assertTrue(mutations.applied.isEmpty())
        assertTrue(repository.syncMutationPending.value)
        assertTrue(repository.roomStorageEnabled)

        mutations.failure = null
        assertTrue(repository.awaitInitialized())
        assertEquals(listOf(9L), mutations.applied.single().deletedPlaylistIds)
        assertEquals(null, pending)
        assertFalse(repository.syncMutationPending.value)
    }

    @Test fun `cancelled replay leaves pending ownership and original chain for retry`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        var pending: LocalPlaylistSyncMutationOutbox? = null
        `when`(room.readPendingSyncMutationOutbox()).thenAnswer { pending }
        doAnswer { pending = null; Unit }.`when`(room).clearPendingSyncMutationOutbox()
        val mutations = RetryMutationStore()
        val repository = open("cancelled-replay", room, OutboxStorage(null), mutations)
        pending = committed
        val cancelled = CancellationException("cancelled checked apply")
        mutations.failure = cancelled

        assertOriginalCause(cancelled, runCatching { repository.flushPendingSyncMutation(digest) }.exceptionOrNull())
        assertEquals(committed, pending)
        assertTrue(repository.syncMutationPending.value)
        assertTrue(repository.roomStorageEnabled)
        mutations.failure = null
        repository.flushPendingSyncMutation(digest)
        assertEquals(null, pending)
        assertFalse(repository.syncMutationPending.value)
    }

    @Test fun `Room commit followed by cancellation rereads real primary before trimming its tombstone`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        var actualPrimary = primary
        var pending: LocalPlaylistSyncMutationOutbox? = null
        `when`(room.readIfRoomPrimary()).thenAnswer { actualPrimary }
        `when`(room.readPendingSyncMutationOutbox()).thenAnswer { pending }
        doAnswer { pending = null; Unit }.`when`(room).clearPendingSyncMutationOutbox()
        doAnswer { pending = it.getArgument(0); Unit }.`when`(room).writePendingSyncMutationOutbox(
            org.mockito.ArgumentMatchers.any(LocalPlaylistSyncMutationOutbox::class.java) ?: committed, anyLong()
        )
        val mutations = RetryMutationStore()
        val storage = OutboxStorage(null)
        val repository = open("committed-cancellation", room, storage, mutations)
        val cancelled = CancellationException("Room committed before cancellation was delivered")
        doAnswer { actualPrimary = it.getArgument(1); throw cancelled }.`when`(room)
            .writeIncremental(anyList(), anyList(), anyString(), anyLong())

        assertOriginalCause(cancelled, runCatching { repository.deletePlaylist(7) }.exceptionOrNull())
        assertTrue(actualPrimary.isEmpty())
        assertEquals(primary, repository.playlists.value)
        assertTrue(repository.syncMutationPending.value)
        assertFalse(repository.initializationReadyFlow.value)
        assertTrue(mutations.applied.isEmpty())
        assertEquals(0, storage.commits)

        assertTrue(repository.awaitInitialized())
        assertTrue(repository.playlists.value.isEmpty())
        assertEquals(listOf(7L), mutations.applied.flatMap { it.deletedPlaylistIds })
        assertEquals(null, pending)
        assertFalse(repository.syncMutationPending.value)
    }

    @Test fun `edit queued behind a cancelled commit cannot publish from its old ready check`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        var actualPrimary = primary
        var pending: LocalPlaylistSyncMutationOutbox? = null
        `when`(room.readIfRoomPrimary()).thenAnswer { actualPrimary }
        `when`(room.readPendingSyncMutationOutbox()).thenAnswer { pending }
        doAnswer { pending = null; Unit }.`when`(room).clearPendingSyncMutationOutbox()
        doAnswer { pending = it.getArgument(0); Unit }.`when`(room).writePendingSyncMutationOutbox(
            org.mockito.ArgumentMatchers.any(LocalPlaylistSyncMutationOutbox::class.java) ?: committed, anyLong()
        )
        val cancelled = CancellationException("previous owner committed before cancellation")
        var rejectCommit = true
        var roomWrites = 0
        doAnswer {
            roomWrites++
            actualPrimary = it.getArgument(1)
            if (rejectCommit) throw cancelled
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyString(), anyLong())
        val mutations = RetryMutationStore()
        val storage = OutboxStorage(null)
        val repository = open("queued-edit", room, storage, mutations)

        withContext(Dispatchers.IO) {
            repository.playlistCommitMutex.lock()
            val queued = try {
                // 在同一 IO 上直接执行到提交锁暂停，确认它已经通过旧 ready 检查
                val edit = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { repository.renamePlaylist(7, "must not resurrect") }
                }
                assertFalse(edit.isCompleted)
                assertOriginalCause(cancelled, runCatching {
                    repository.publishLocked(emptyList(), syncMutation = LocalPlaylistSyncMutation(deletedPlaylistIds = listOf(7)))
                }.exceptionOrNull())
                edit
            } finally {
                repository.playlistCommitMutex.unlock()
            }
            assertOriginalCause(cancelled, queued.await().exceptionOrNull())
        }

        assertEquals(1, roomWrites)
        assertTrue(actualPrimary.isEmpty())
        assertEquals(primary, repository.playlists.value)
        assertTrue(repository.syncMutationPending.value)
        assertEquals(0, storage.commits)
        rejectCommit = false
        assertTrue(repository.awaitInitialized())
        assertTrue(repository.playlists.value.isEmpty())
        assertEquals(listOf(7L), mutations.applied.flatMap { it.deletedPlaylistIds })
        assertFalse(repository.syncMutationPending.value)
        repository.createPlaylist("recovered")
        assertEquals("recovered", actualPrimary.single().name)
        assertEquals(actualPrimary, repository.playlists.value)
    }

    @Test fun `Room outbox write failures never switch to unrelated legacy storage`() = runTest {
        listOf(IOException("write rejected"), CancellationException("write cancelled")).forEachIndexed { index, failure ->
            val room = mock(LocalPlaylistRoomStore::class.java)
            `when`(room.readIfRoomPrimary()).thenReturn(primary)
            val storage = OutboxStorage(null)
            val repository = open("write-$index", room, storage, RetryMutationStore())
            doAnswer { throw failure }.`when`(room).writePendingSyncMutationOutbox(org.mockito.ArgumentMatchers.any(LocalPlaylistSyncMutationOutbox::class.java) ?: committed, anyLong())

            assertOriginalCause(failure, runCatching { repository.writePendingSyncMutation(committed) }.exceptionOrNull())
            assertTrue(repository.roomStorageEnabled)
            assertTrue(repository.syncMutationPending.value)
            assertEquals(0, storage.writes)
            assertEquals(null, storage.pending)
            assertEquals(0, storage.commits)
        }
    }

    @Test fun `Room clear failures retain chain and cannot be bypassed on retry`() = runTest {
        listOf(IOException("clear rejected"), CancellationException("clear cancelled")).forEachIndexed { index, failure ->
            val room = mock(LocalPlaylistRoomStore::class.java)
            `when`(room.readIfRoomPrimary()).thenReturn(primary)
            var pending: LocalPlaylistSyncMutationOutbox? = null
            `when`(room.readPendingSyncMutationOutbox()).thenAnswer { pending }
            val storage = OutboxStorage(null)
            val repository = open("clear-$index", room, storage, RetryMutationStore())
            val previousLegacyClears = storage.clears
            pending = committed
            var rejectClear = true
            doAnswer { if (rejectClear) throw failure; pending = null; Unit }.`when`(room).clearPendingSyncMutationOutbox()

            assertOriginalCause(failure, runCatching { repository.flushPendingSyncMutation(digest) }.exceptionOrNull())
            assertEquals(committed, pending)
            assertEquals(previousLegacyClears, storage.clears)
            assertTrue(repository.roomStorageEnabled)
            assertTrue(repository.syncMutationPending.value)
            rejectClear = false
            repository.flushPendingSyncMutation(digest)
            assertEquals(null, pending)
            assertFalse(repository.syncMutationPending.value)
        }
    }

    @Test fun `checked legacy domain fallback still owns and recovers its Room outbox`() = runTest {
        val room = mock(LocalPlaylistRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(primary)
        var pending: LocalPlaylistSyncMutationOutbox? = null
        `when`(room.readPendingSyncMutationOutbox()).thenAnswer { pending }
        doAnswer { pending = null; Unit }.`when`(room).clearPendingSyncMutationOutbox()
        doAnswer { throw IOException("domain write unavailable") }.`when`(room).writeIncremental(anyList(), anyList(), anyString(), anyLong())
        val mutations = RetryMutationStore()
        val storage = OutboxStorage(null)
        val repository = open("domain-fallback", room, storage, mutations)
        repository.renamePlaylist(7, "legacy committed")
        assertFalse(repository.roomStorageEnabled)
        assertEquals(1, storage.commits)
        verify(room).markLegacyJsonPrimary(anyString(), anyLong())
        val nextDigest = LocalPlaylistRoomStore.domainDigest(repository.playlists.value)
        pending = LocalPlaylistSyncMutationOutbox(listOf(committed.mutations.single().withExpectedPrimaryDigest(nextDigest)))

        repository.flushPendingSyncMutation(nextDigest)

        assertEquals(listOf(9L), mutations.applied.single().deletedPlaylistIds)
        assertEquals(null, pending)
        assertFalse(repository.syncMutationPending.value)
    }

    private fun open(name: String, room: LocalPlaylistRoomStore?, storage: OutboxStorage, mutations: RetryMutationStore) =
        LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "$name.json"), normalizePlaylists = { it },
            storage = storage, syncMutationStore = mutations, roomStore = room
        )

    private fun assertOriginalCause(original: Throwable, actual: Throwable?) {
        assertTrue("Failure must propagate with its original cause", generateSequence(checkNotNull(actual)) { it.cause }.any { it === original })
    }

    private class RetryMutationStore(private val delegate: RecordingSyncMutationStore = RecordingSyncMutationStore()) :
        LocalPlaylistSyncMutationStore by delegate {
        var failure: Exception? = null
        val applied: List<LocalPlaylistSyncMutation> get() = delegate.applied
        override fun applyAndMarkMutation(mutation: LocalPlaylistSyncMutation): Long {
            failure?.let { throw it }
            return delegate.applyAndMarkMutation(mutation)
        }
    }

    private class OutboxStorage(primary: String?, private val delegate: RecordingStorage = RecordingStorage(primary)) :
        LocalPlaylistStorage by delegate {
        var clears = 0
        var writes = 0
        val commits: Int get() = delegate.commitCount
        var pending: String?
            get() = delegate.pendingSyncMutation
            set(value) { delegate.pendingSyncMutation = value }
        override fun clearPendingSyncMutation() { clears++; delegate.clearPendingSyncMutation() }
        override fun writePendingSyncMutation(text: String) { writes++; delegate.writePendingSyncMutation(text) }
    }
}
