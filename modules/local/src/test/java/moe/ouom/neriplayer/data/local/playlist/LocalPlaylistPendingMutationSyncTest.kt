package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistPendingMutationSyncTest : LocalPlaylistRepositoryTestSupport() {
    @Test fun `pending deletion with persistent checked IO failure refuses old remote playlist`() = runTest {
        val failure = IOException("deletion checked commit still unavailable")
        assertPendingDeletionRefusesRemote(deleteWithFailedCommit("pending-io", failure), failure)
    }

    @Test fun `pending deletion with persistent cancellation cannot restore old remote playlist`() = runTest {
        val failure = CancellationException("deletion checked commit still cancelled")
        assertPendingDeletionRefusesRemote(deleteWithFailedCommit("pending-cancel", failure), failure)
    }

    @Test fun `repaired checked IO commit advances deletion version before checking old remote epoch`() = runTest {
        assertRepairedDeletionRejectsOldEpoch(deleteWithFailedCommit("repair-io", IOException("deletion checked commit unavailable")))
    }

    @Test fun `repaired cancelled commit advances deletion version before checking old remote epoch`() = runTest {
        assertRepairedDeletionRejectsOldEpoch(deleteWithFailedCommit("repair-cancel", CancellationException("deletion checked commit cancelled")))
    }

    @Test fun `sync capture confirms repaired deletion before reading containers and receipts`() = runTest {
        val pending = deleteWithFailedCommit("capture-repaired", IOException("deletion checked commit unavailable"))
        pending.mutations.failure = null

        val captured = pending.repository.withCommittedSyncSnapshot {
            readPlaylistState(pending.repository, pending.storage, pending.mutations)
        }

        assertTrue(captured.playlistIds.isEmpty())
        assertEquals(listOf(7L), captured.deletedIds)
        assertEquals(pending.oldVersion + 2L, captured.version)
        assertNull(captured.pending)
        assertFalse(pending.repository.syncMutationPending.value)
        assertEquals(pending.domainCommitCount, pending.storage.commitCount)
    }

    @Test fun `sync capture cannot invoke its reader with a failed checked deletion commit`() = runTest {
        val failure = IOException("deletion checked commit unavailable during capture")
        assertFailedCaptureDoesNotRead(deleteWithFailedCommit("capture-io", failure), failure)
    }

    @Test fun `cancelled deletion confirmation cannot invoke or acknowledge a sync capture reader`() = runTest {
        val failure = CancellationException("deletion checked commit cancelled during capture")
        assertFailedCaptureDoesNotRead(deleteWithFailedCommit("capture-cancel", failure), failure)
    }

    @Test fun `queued capture drains preceding deletion and its reader excludes the following edit`() = runTest {
        val remote = listOf(LocalPlaylist(id = 7, name = "offline"))
        val storage = RecordingStorage(Gson().toJson(remote))
        val mutations = SwitchableMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "queued-capture.json"),
            normalizePlaylists = { it }, storage = storage, syncMutationStore = mutations
        )
        val oldVersion = mutations.getSyncMutationVersion()
        mutations.failure = IOException("previous owner deletion confirmation failed")

        withContext(Dispatchers.IO) {
            var followingEdit: Deferred<Unit>? = null
            repository.playlistCommitMutex.lock()
            val queued = try {
                val capture = async(start = CoroutineStart.UNDISPATCHED) {
                    repository.withCommittedSyncSnapshot {
                        val edit = async(start = CoroutineStart.UNDISPATCHED) { repository.createPlaylist("following") }
                        followingEdit = edit
                        assertFalse(edit.isCompleted)
                        readPlaylistState(repository, storage, mutations)
                    }
                }
                assertFalse(capture.isCompleted)
                repository.publishLocked(emptyList(), syncMutation = LocalPlaylistSyncMutation(
                    deletedPlaylistIds = listOf(7), clearedPlaylistDeletionIds = listOf(7)
                ))
                assertTrue(repository.syncMutationPending.value)
                assertEquals(oldVersion + 1L, mutations.getSyncMutationVersion())
                mutations.failure = null
                capture
            } finally {
                repository.playlistCommitMutex.unlock()
            }

            val captured = queued.await()
            checkNotNull(followingEdit).await()
            assertTrue(captured.playlistIds.isEmpty())
            assertEquals(listOf(7L), captured.deletedIds)
            assertEquals(oldVersion + 2L, captured.version)
            assertNull(captured.pending)
            assertEquals("following", repository.playlists.value.single().name)
            assertFalse(repository.syncMutationPending.value)
        }
    }

    private suspend fun deleteWithFailedCommit(name: String, failure: Exception): PendingDeletion {
        val remote = listOf(LocalPlaylist(id = 7, name = "offline"))
        val storage = RecordingStorage(Gson().toJson(remote))
        val mutations = SwitchableMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "$name.json"),
            normalizePlaylists = { it }, storage = storage, syncMutationStore = mutations
        )
        val oldVersion = mutations.getSyncMutationVersion()
        assertEquals(0L, oldVersion)
        mutations.failure = failure

        val deleted = runCatching { repository.deletePlaylist(7) }

        if (failure is CancellationException) assertOriginalCause(failure, deleted.exceptionOrNull())
        else assertTrue(deleted.getOrThrow())
        assertTrue(repository.playlists.value.isEmpty())
        assertTrue(repository.initializationReadyFlow.value)
        assertTrue(repository.syncMutationPending.value)
        assertEquals(oldVersion + 1L, mutations.getSyncMutationVersion())
        assertTrue(mutations.applied.isEmpty())
        val durablePending = checkNotNull(storage.pendingSyncMutation)
        val persisted = Gson().fromJson(durablePending, LocalPlaylistSyncMutationOutbox::class.java)
        assertEquals(listOf(7L), persisted.mutations.flatMap { it.deletedPlaylistIds })
        assertEquals(listOf(7L), persisted.mutations.flatMap { it.clearedPlaylistDeletionIds })
        assertEquals("[]", storage.primary)
        return PendingDeletion(repository, storage, mutations, remote, oldVersion, durablePending, storage.commitCount)
    }

    private suspend fun assertPendingDeletionRefusesRemote(pending: PendingDeletion, failure: Exception) {
        val result = runCatching {
            pending.repository.applySyncedPlaylistsIfUnchanged(pending.remote, pending.oldVersion)
        }

        if (failure is CancellationException) assertOriginalCause(failure, result.exceptionOrNull())
        else {
            assertTrue("Unknown deletion commit must reject sync apply", result.isFailure || result.getOrNull() == false)
            result.exceptionOrNull()?.let { assertOriginalCause(failure, it) }
        }
        assertTrue(pending.repository.playlists.value.isEmpty())
        assertEquals("[]", pending.storage.primary)
        assertEquals(pending.domainCommitCount, pending.storage.commitCount)
        assertEquals(pending.durablePending, pending.storage.pendingSyncMutation)
        assertTrue(pending.repository.syncMutationPending.value)
        assertEquals(pending.oldVersion + 1L, pending.mutations.getSyncMutationVersion())
        assertTrue(pending.mutations.applied.isEmpty())
    }

    private suspend fun assertFailedCaptureDoesNotRead(pending: PendingDeletion, failure: Exception) {
        var readerCalled = false

        val result = runCatching {
            pending.repository.withCommittedSyncSnapshot {
                readerCalled = true
                readPlaylistState(pending.repository, pending.storage, pending.mutations)
            }
        }

        assertOriginalCause(failure, result.exceptionOrNull())
        assertFalse(readerCalled)
        assertEquals(pending.durablePending, pending.storage.pendingSyncMutation)
        assertEquals("[]", pending.storage.primary)
        assertEquals(pending.domainCommitCount, pending.storage.commitCount)
        assertTrue(pending.repository.playlists.value.isEmpty())
        assertTrue(pending.repository.syncMutationPending.value)
        assertEquals(pending.oldVersion + 1L, pending.mutations.getSyncMutationVersion())
        assertTrue(pending.mutations.applied.isEmpty())
    }

    private suspend fun assertRepairedDeletionRejectsOldEpoch(pending: PendingDeletion) {
        pending.mutations.failure = null

        val accepted = pending.repository.applySyncedPlaylistsIfUnchanged(pending.remote, pending.oldVersion)

        assertFalse("Deletion must advance the epoch before accepting the stale remote result", accepted)
        assertEquals(pending.oldVersion + 2L, pending.mutations.getSyncMutationVersion())
        assertEquals(listOf(7L), pending.mutations.applied.flatMap { it.deletedPlaylistIds })
        assertEquals(listOf(7L), pending.mutations.applied.flatMap { it.clearedPlaylistDeletionIds })
        assertTrue(pending.repository.playlists.value.isEmpty())
        assertEquals("[]", pending.storage.primary)
        assertEquals(pending.domainCommitCount, pending.storage.commitCount)
        assertNull(pending.storage.pendingSyncMutation)
        assertFalse(pending.repository.syncMutationPending.value)
        assertTrue(pending.repository.initializationReadyFlow.value)
    }

    private fun assertOriginalCause(original: Throwable, actual: Throwable?) {
        assertTrue("Cancellation and checked failure must preserve their original cause", generateSequence(checkNotNull(actual)) { it.cause }.any { it === original })
    }

    private fun readPlaylistState(repository: LocalPlaylistRepository, storage: RecordingStorage, mutations: SwitchableMutationStore) =
        CommittedPlaylistRead(
            repository.playlists.value.map { it.id }, mutations.applied.flatMap { it.deletedPlaylistIds },
            mutations.getSyncMutationVersion(), storage.pendingSyncMutation
        )

    private data class CommittedPlaylistRead(val playlistIds: List<Long>, val deletedIds: List<Long>, val version: Long, val pending: String?)

    private data class PendingDeletion(
        val repository: LocalPlaylistRepository,
        val storage: RecordingStorage,
        val mutations: SwitchableMutationStore,
        val remote: List<LocalPlaylist>,
        val oldVersion: Long,
        val durablePending: String,
        val domainCommitCount: Int
    )

    private class SwitchableMutationStore(private val delegate: RecordingSyncMutationStore = RecordingSyncMutationStore()) :
        LocalPlaylistSyncMutationStore by delegate {
        var failure: Exception? = null
        val applied: List<LocalPlaylistSyncMutation> get() = delegate.applied
        override fun applyAndMarkMutation(mutation: LocalPlaylistSyncMutation): Long {
            failure?.let { throw it }
            return delegate.applyAndMarkMutation(mutation)
        }
    }
}
