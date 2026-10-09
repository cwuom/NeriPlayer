package moe.ouom.neriplayer.core.download.execution.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.CoreCommitJournalRecovery.Outcome
import moe.ouom.neriplayer.data.identity.stableKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionRoomCoreCommitJournalTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val context get() = fixture.context
    private val database get() = fixture.database
    private val store = DownloadExecutionRoomStore
    private val songA = testSong(1L)
    private val songB = testSong(2L)

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `core commit journal reports missing blocked and already committed operations`() = runTest {
        fixture.upsert(request(songA, operationId = "op-done", attemptId = 5L), "CORE_COMMITTED")
        fixture.upsert(request(songB, operationId = "op-stopped"), "STOPPED")

        assertEquals(Outcome.BLOCKED, reconcile(" ").outcome)
        assertEquals(Outcome.MISSING, reconcile("op-x").outcome)
        assertEquals(Outcome.BLOCKED, reconcile("op-done", stableKey = songB.stableKey()).outcome)
        assertEquals(Outcome.BLOCKED, reconcile("op-done", expectedAttemptId = 9L).outcome)
        val committed = reconcile("op-done", stableKey = songA.stableKey(), expectedAttemptId = 5L)
        assertEquals(Outcome.COMMITTED, committed.outcome)
        assertEquals("CORE_COMMITTED", committed.state)
        assertEquals(Outcome.BLOCKED, reconcile("op-stopped").outcome)
    }

    @Test
    fun `core commit journal prepares or commits committing operations by metadata durability`() = runTest {
        fixture.upsert(request(songA, operationId = "op-a"), "COMMITTING")

        val prepared = reconcile("op-a")
        val committed = reconcile("op-a", coreMetadataDurable = true)

        assertEquals(Outcome.PREPARED, prepared.outcome)
        assertEquals(Outcome.COMMITTED, committed.outcome)
        assertEquals("CORE_COMMITTED", fixture.state("op-a"))
    }

    @Test
    fun `core commit journal moves retryable work back to committing before committing it`() = runTest {
        fixture.upsert(request(songA, operationId = "op-a"), "RETRYABLE")
        fixture.upsert(request(songB, operationId = "op-b"), WAITING_STORAGE_MUTATION_OPERATION_STATE)

        val prepared = reconcile("op-a")
        val committed = reconcile("op-b", coreMetadataDurable = true)

        assertEquals(Outcome.PREPARED, prepared.outcome)
        assertEquals("COMMITTING", fixture.state("op-a"))
        assertEquals("CORE_COMMIT_RECOVERY", fixture.row("op-a").lastErrorCode)
        assertEquals(Outcome.COMMITTED, committed.outcome)
        assertEquals("CORE_COMMITTED", fixture.state("op-b"))
    }

    @Test
    fun `core commit journal blocks queued work user stops and malformed payloads`() = runTest {
        fixture.upsert(request(songA, operationId = "op-queued"), "QUEUED")
        fixture.upsert(request(songB, operationId = "op-stop"), "RETRYABLE")
        fixture.upsert(request(testSong(3L), operationId = "op-bad"), "QUEUED")
        fixture.rewrite("op-stop") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-bad") { it.copy(sourceHintJson = "{broken") }

        assertEquals(Outcome.BLOCKED, reconcile("op-queued").outcome)
        val stopped = reconcile("op-stop")
        assertEquals(Outcome.BLOCKED, stopped.outcome)
        assertTrue(stopped.stopRequestedByUser)
        assertEquals(Outcome.BLOCKED, reconcile("op-bad").outcome)
        assertEquals("INVALID", fixture.state("op-bad"))
    }

    private suspend fun reconcile(
        operationId: String,
        stableKey: String? = null,
        expectedAttemptId: Long? = null,
        coreMetadataDurable: Boolean = false
    ) = store.reconcileCoreCommitJournal(
        context = context,
        operationId = operationId,
        stableKey = stableKey,
        expectedAttemptId = expectedAttemptId,
        coreMetadataDurable = coreMetadataDurable,
        database = database
    )
}
