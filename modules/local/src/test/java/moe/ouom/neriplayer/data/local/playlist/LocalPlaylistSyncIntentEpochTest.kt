package moe.ouom.neriplayer.data.local.playlist

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistSyncIntentEpochTest : LocalPlaylistRepositoryTestSupport() {
    @Test fun `failed deletion confirmation invalidates captured epoch before each committed local edit`() = runTest {
        assertFailedConfirmationAdvancesIntent(IOException("deletion confirmation unavailable"))
    }

    @Test fun `cancelled deletion confirmation still invalidates captured epoch for each committed local edit`() = runTest {
        assertFailedConfirmationAdvancesIntent(CancellationException("deletion confirmation cancelled"))
    }

    @Test fun `checked intent IO failure rejects deletion before changing primary UI or outbox`() = runTest {
        assertFailedIntentDoesNotCommit(IOException("intent epoch commit unavailable"))
    }

    @Test fun `checked intent cancellation rejects deletion before changing primary UI or outbox`() = runTest {
        assertFailedIntentDoesNotCommit(CancellationException("intent epoch commit cancelled"))
    }

    @Test fun `successful user deletion has one intent advance followed by one atomic confirmation advance`() = runTest {
        val fixture = fixture()
        val oldVersion = fixture.mutations.getSyncMutationVersion()

        assertTrue(fixture.repository.deletePlaylist(7))

        assertEquals(listOf(8L), fixture.repository.playlists.value.map { it.id })
        assertPrimaryMatchesUi(fixture)
        assertNull(fixture.storage.backing.pendingSyncMutation)
        assertFalse(fixture.repository.syncMutationPending.value)
        assertEquals(listOf(7L), fixture.mutations.applied.flatMap { it.deletedPlaylistIds })
        assertEquals(oldVersion + 2L, fixture.mutations.getSyncMutationVersion())
        assertEquals(listOf("intent", "outbox", "domain", "confirmation", "clear"), fixture.events)
    }

    @Test fun `retrying committed pending deletion only adds its atomic confirmation advance`() = runTest {
        val fixture = fixture()
        val oldVersion = fixture.mutations.getSyncMutationVersion()
        fixture.mutations.applyFailure = IOException("deletion confirmation unavailable")
        assertTrue(fixture.repository.deletePlaylist(7))
        assertTrue(fixture.repository.syncMutationPending.value)
        val committedPrimary = fixture.storage.backing.primary
        val committedCount = fixture.storage.backing.commitCount
        fixture.mutations.applyFailure = null
        fixture.events.clear()

        val capturedVersion = fixture.repository.withCommittedSyncSnapshot {
            assertEquals(listOf(8L), fixture.repository.playlists.value.map { it.id })
            assertEquals(listOf(7L), fixture.mutations.applied.flatMap { it.deletedPlaylistIds })
            fixture.mutations.getSyncMutationVersion()
        }

        assertEquals(oldVersion + 2L, capturedVersion)
        assertEquals(listOf("confirmation", "clear"), fixture.events)
        assertEquals(committedPrimary, fixture.storage.backing.primary)
        assertEquals(committedCount, fixture.storage.backing.commitCount)
        assertNull(fixture.storage.backing.pendingSyncMutation)
        assertFalse(fixture.repository.syncMutationPending.value)
    }

    private suspend fun assertFailedConfirmationAdvancesIntent(failure: Exception) {
        val fixture = fixture()
        val capturedVersion = fixture.repository.withCommittedSyncSnapshot {
            assertEquals(listOf(7L, 8L), fixture.repository.playlists.value.map { it.id })
            fixture.mutations.getSyncMutationVersion()
        }
        assertEquals(0L, capturedVersion)
        val initialCommitCount = fixture.storage.backing.commitCount
        fixture.mutations.applyFailure = failure

        val deleted = runCatching { fixture.repository.deletePlaylist(7) }

        if (failure is CancellationException) assertOriginalCause(failure, deleted.exceptionOrNull())
        else assertTrue(deleted.getOrThrow())
        assertCommittedPendingDeletion(fixture, initialCommitCount + 1, 1)
        assertEquals(capturedVersion + 1L, fixture.mutations.getSyncMutationVersion())
        assertFalse("A completed capture must be stale during the subsequent remote request", fixture.mutations.getSyncMutationVersion() == capturedVersion)
        assertEquals(listOf("intent", "outbox", "domain", "confirmation"), fixture.events)

        listOf("renamed", "again").forEachIndexed { index, name ->
            fixture.events.clear()
            val renamed = runCatching { fixture.repository.renamePlaylist(8, name) }
            if (failure is CancellationException) assertOriginalCause(failure, renamed.exceptionOrNull())
            else renamed.getOrThrow()

            assertEquals(name, fixture.repository.playlists.value.single().name)
            assertCommittedPendingDeletion(fixture, initialCommitCount + index + 2, index + 2)
            assertEquals(capturedVersion + index + 2L, fixture.mutations.getSyncMutationVersion())
            assertEquals(listOf("intent", "outbox", "domain", "confirmation"), fixture.events)
        }
    }

    private suspend fun assertFailedIntentDoesNotCommit(failure: Exception) {
        val fixture = fixture()
        val oldUi = fixture.repository.playlists.value
        val oldPrimary = fixture.storage.backing.primary
        val oldPending = fixture.storage.backing.pendingSyncMutation
        val oldVersion = fixture.mutations.getSyncMutationVersion()
        val oldCommitCount = fixture.storage.backing.commitCount
        val oldOutboxWrites = fixture.storage.outboxWrites
        val oldOutboxClears = fixture.storage.outboxClears
        fixture.mutations.markFailure = failure

        val deleted = runCatching { fixture.repository.deletePlaylist(7) }

        assertTrue("Checked intent failure must reject the user deletion", deleted.isFailure)
        assertOriginalCause(failure, deleted.exceptionOrNull())
        assertEquals(oldUi, fixture.repository.playlists.value)
        assertEquals(oldPrimary, fixture.storage.backing.primary)
        assertEquals(oldPending, fixture.storage.backing.pendingSyncMutation)
        assertEquals(oldCommitCount, fixture.storage.backing.commitCount)
        assertEquals(oldOutboxWrites, fixture.storage.outboxWrites)
        assertEquals(oldOutboxClears, fixture.storage.outboxClears)
        assertEquals(oldVersion, fixture.mutations.getSyncMutationVersion())
        assertTrue(fixture.mutations.applied.isEmpty())
        assertFalse(fixture.repository.syncMutationPending.value)
        assertTrue(fixture.repository.initializationReadyFlow.value)
        assertEquals(listOf("intent"), fixture.events)
    }

    private fun assertCommittedPendingDeletion(fixture: Fixture, commitCount: Int, pendingCount: Int) {
        assertEquals(listOf(8L), fixture.repository.playlists.value.map { it.id })
        assertPrimaryMatchesUi(fixture)
        assertEquals(commitCount, fixture.storage.backing.commitCount)
        assertTrue(fixture.repository.initializationReadyFlow.value)
        assertTrue(fixture.repository.syncMutationPending.value)
        assertTrue(fixture.mutations.applied.isEmpty())
        val pending = Gson().fromJson(checkNotNull(fixture.storage.backing.pendingSyncMutation), LocalPlaylistSyncMutationOutbox::class.java)
        assertEquals(pendingCount, pending.mutations.size)
        assertEquals(listOf(7L), pending.mutations.flatMap { it.deletedPlaylistIds })
        assertEquals(listOf(7L), pending.mutations.flatMap { it.clearedPlaylistDeletionIds })
        assertEquals(LocalPlaylistRoomStore.domainDigest(fixture.repository.playlists.value), pending.mutations.last().expectedPrimaryDigest)
    }

    private fun assertPrimaryMatchesUi(fixture: Fixture) {
        val primary = Gson().fromJson(checkNotNull(fixture.storage.backing.primary), Array<LocalPlaylist>::class.java).toList()
        assertEquals(fixture.repository.playlists.value, primary)
    }

    private fun assertOriginalCause(original: Throwable, actual: Throwable?) {
        assertTrue("Failure must preserve the original checked error or cancellation", generateSequence(checkNotNull(actual)) { it.cause }.any { it === original })
    }

    private fun fixture(): Fixture {
        val events = mutableListOf<String>()
        val initial = listOf(LocalPlaylist(id = 7, name = "offline"), LocalPlaylist(id = 8, name = "remaining"))
        val storage = OrderedStorage(RecordingStorage(Gson().toJson(initial)), events)
        val mutations = IntentMutationStore(events)
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(), file = File(tempFolder.root, "intent.json"),
            normalizePlaylists = { it }, storage = storage, syncMutationStore = mutations
        )
        assertTrue(repository.initializationReadyFlow.value)
        assertEquals(initial, repository.playlists.value)
        assertEquals(0L, mutations.getSyncMutationVersion())
        events.clear()
        return Fixture(repository, storage, mutations, events)
    }

    private data class Fixture(
        val repository: LocalPlaylistRepository,
        val storage: OrderedStorage,
        val mutations: IntentMutationStore,
        val events: MutableList<String>
    )

    private class OrderedStorage(val backing: RecordingStorage, private val events: MutableList<String>) : LocalPlaylistStorage by backing {
        var outboxWrites = 0
            private set
        var outboxClears = 0
            private set

        override fun commit(text: String, rotateBackup: Boolean, replaceBackupWithCommittedPrimary: Boolean) {
            events += "domain"
            backing.commit(text, rotateBackup, replaceBackupWithCommittedPrimary)
        }

        override fun writePendingSyncMutation(text: String) {
            events += "outbox"
            outboxWrites++
            backing.writePendingSyncMutation(text)
        }

        override fun clearPendingSyncMutation() {
            events += "clear"
            outboxClears++
            backing.clearPendingSyncMutation()
        }
    }

    private class IntentMutationStore(
        private val events: MutableList<String>,
        private val delegate: RecordingSyncMutationStore = RecordingSyncMutationStore()
    ) : LocalPlaylistSyncMutationStore by delegate {
        var markFailure: Exception? = null
        var applyFailure: Exception? = null
        val applied: List<LocalPlaylistSyncMutation> get() = delegate.applied

        override fun markSyncMutation(): Long {
            events += "intent"
            markFailure?.let { throw it }
            return delegate.markSyncMutation()
        }

        override fun applyAndMarkMutation(mutation: LocalPlaylistSyncMutation): Long {
            events += "confirmation"
            applyFailure?.let { throw it }
            return delegate.applyAndMarkMutation(mutation)
        }
    }
}
