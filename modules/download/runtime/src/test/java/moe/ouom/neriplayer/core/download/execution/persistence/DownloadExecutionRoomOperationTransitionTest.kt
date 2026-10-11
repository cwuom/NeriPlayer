package moe.ouom.neriplayer.core.download.execution.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.CancellationBoundary
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionRoomOperationTransitionTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val context get() = fixture.context
    private val database get() = fixture.database
    private val store = DownloadExecutionRoomStore

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `already downloaded completion closes queued work and its batch member`() = runTest {
        val song = testSong(1L)
        val identity = fixture.createBatch(listOf(song))
        val (request) = fixture.enqueueBatchMembers(identity, listOf(song), attemptId = 2L)
        fixture.rewrite(request.operationId) { it.copy(hostProcessToken = "host", hostAdmittedAtMs = 1L) }

        assertFalse(markAlreadyDownloaded(" ", song.stableKey()))
        assertFalse(markAlreadyDownloaded(request.operationId, " "))
        assertFalse(markAlreadyDownloaded("missing", song.stableKey()))
        assertFalse(markAlreadyDownloaded(request.operationId, testSong(2L).stableKey()))
        assertFalse(markAlreadyDownloaded(request.operationId, song.stableKey(), expectedAttemptId = 3L))

        assertTrue(markAlreadyDownloaded(request.operationId, song.stableKey(), expectedAttemptId = 2L))

        val row = fixture.row(request.operationId)
        assertEquals("COMPLETED", row.state)
        assertEquals("DOWNLOAD_ALREADY_PRESENT", row.lastErrorCode)
        assertNull(row.hostProcessToken)
        assertEquals(DownloadBatchMemberTerminal.COMPLETED, fixture.member(identity, song).terminalBits)
        assertEquals(DownloadBatchState.COMPLETED, fixture.batchStateBits(identity))

        assertTrue(markAlreadyDownloaded(request.operationId, song.stableKey()))
        assertFalse(markAlreadyDownloaded(request.operationId, song.stableKey(), expectedAttemptId = 7L))
    }

    @Test
    fun `already downloaded completion refuses stopped committed and malformed operations`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED")
        fixture.upsert(request(testSong(2L)), "COMMITTING")
        fixture.upsert(request(testSong(3L)), "RUNNING")
        fixture.rewrite("op-1") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-3") { it.copy(sourceHintJson = "not-json") }

        assertFalse(markAlreadyDownloaded("op-1", testSong(1L).stableKey()))
        assertFalse(markAlreadyDownloaded("op-2", testSong(2L).stableKey()))
        assertEquals("COMMITTING", fixture.state("op-2"))
        assertFalse(markAlreadyDownloaded("op-3", testSong(3L).stableKey()))
        assertEquals("INVALID", fixture.state("op-3"))
    }

    @Test
    fun `core publication recovery reopens only finished operations`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song), "COMPLETED")
        fixture.upsert(request(testSong(2L)), "QUEUED")

        assertFalse(store.reopenCorePublicationRecovery(context, "op-1", " ", "PENDING", database))
        assertFalse(store.reopenCorePublicationRecovery(context, " ", song.stableKey(), "PENDING", database))
        assertFalse(store.reopenCorePublicationRecovery(context, "missing", song.stableKey(), "PENDING", database))
        assertFalse(store.reopenCorePublicationRecovery(context, "op-2", testSong(2L).stableKey(), "PENDING", database))
        assertTrue(store.reopenCorePublicationRecovery(context, "op-1", song.stableKey(), "PENDING", database))

        assertEquals("DEGRADED_COMPLETE", fixture.state("op-1"))
        assertEquals("PENDING", fixture.row("op-1").lastErrorCode)
    }

    @Test
    fun `missing post core artifacts reopen a fresh transfer for the same attempt`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song, attemptId = 4L), "CORE_COMMITTED")
        fixture.upsert(request(testSong(2L)), "QUEUED")

        assertFalse(reopenMissing(" ", song.stableKey(), 4L))
        assertFalse(reopenMissing("op-1", "", 4L))
        assertFalse(reopenMissing("missing", song.stableKey(), 4L))
        assertFalse(reopenMissing("op-2", testSong(2L).stableKey(), null))
        assertFalse(reopenMissing("op-1", song.stableKey(), 5L))
        assertTrue(reopenMissing("op-1", song.stableKey(), 4L))

        assertEquals("RUNNING", fixture.state("op-1"))
    }

    @Test
    fun `schedule rejection backs off reusable work once`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song), "QUEUED")
        fixture.upsert(request(testSong(2L)), "COMPLETED")

        assertFalse(store.markScheduleRejectedRetryable(context, "op-1", " ", "HOST_FULL", database))
        assertFalse(store.markScheduleRejectedRetryable(context, "missing", song.stableKey(), "HOST_FULL", database))
        assertFalse(store.markScheduleRejectedRetryable(context, "op-2", testSong(2L).stableKey(), "HOST_FULL", database))
        assertTrue(store.markScheduleRejectedRetryable(context, "op-1", song.stableKey(), "HOST_FULL", database, nowMs = 1_000L))

        val retryable = fixture.row("op-1")
        assertEquals("RETRYABLE", retryable.state)
        assertEquals("HOST_FULL", retryable.lastErrorCode)
        assertEquals(1, retryable.retryCount)
        assertTrue((retryable.nextRetryAtMs ?: 0L) > 1_000L)

        assertTrue(store.markScheduleRejectedRetryable(context, "op-1", song.stableKey(), "HOST_FULL", database))
        assertEquals(1, fixture.row("op-1").retryCount)
    }

    @Test
    fun `waiting storage mutations are promoted back into the queue`() = runTest {
        val song = testSong(1L)
        val malformed = testSong(2L)
        fixture.upsert(request(song), WAITING_STORAGE_MUTATION_OPERATION_STATE)
        fixture.upsert(request(malformed), WAITING_STORAGE_MUTATION_OPERATION_STATE)
        fixture.upsert(request(testSong(3L)), "QUEUED")
        fixture.rewrite("op-2") { it.copy(sourceHintJson = "not-json") }

        assertFalse(store.promoteWaitingStorageMutation(context, "op-1", " ", database))
        assertFalse(store.promoteWaitingStorageMutation(context, "missing", song.stableKey(), database))
        assertFalse(store.promoteWaitingStorageMutation(context, "op-3", testSong(3L).stableKey(), database))
        assertFalse(store.promoteWaitingStorageMutation(context, "op-2", malformed.stableKey(), database))
        assertEquals("INVALID", fixture.state("op-2"))

        assertTrue(store.promoteWaitingStorageMutation(context, "op-1", song.stableKey(), database))
        assertEquals("QUEUED", fixture.state("op-1"))
    }

    @Test
    fun `staging preparation persists the preserve flag once`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song), "RUNNING")

        assertFalse(store.markStagingPrepared(context, "op-1", " ", database))
        assertFalse(store.markStagingPrepared(context, "missing", song.stableKey(), database))
        assertFalse(store.markStagingPrepared(context, "op-1", testSong(2L).stableKey(), database))
        assertFalse(fixture.read("op-1")!!.preserveStaging)

        assertTrue(store.markStagingPrepared(context, "op-1", song.stableKey(), database))
        assertTrue(fixture.read("op-1")!!.preserveStaging)
        val updatedAt = fixture.row("op-1").updatedAtMs
        assertTrue(store.markStagingPrepared(context, "op-1", song.stableKey(), database))
        assertEquals(updatedAt, fixture.row("op-1").updatedAtMs)
    }

    @Test
    fun `user initiated promotion upgrades automatic requests without resetting them`() = runTest {
        val automatic = testSong(1L)
        val manual = testSong(2L)
        val malformed = testSong(3L)
        fixture.upsert(request(automatic, userInitiated = false), "RETRYABLE")
        fixture.upsert(request(manual), "RUNNING")
        fixture.upsert(request(malformed, userInitiated = false), "QUEUED")
        fixture.upsert(request(testSong(4L)), "COMPLETED")
        fixture.rewrite("op-3") { it.copy(sourceHintJson = "not-json") }

        assertNull(store.promoteUserInitiatedOperation(context, " ", automatic.stableKey(), database))
        assertNull(store.promoteUserInitiatedOperation(context, "op-1", " ", database))
        assertNull(store.promoteUserInitiatedOperation(context, "missing", automatic.stableKey(), database))
        assertNull(store.promoteUserInitiatedOperation(context, "op-4", testSong(4L).stableKey(), database))
        assertNull(store.promoteUserInitiatedOperation(context, "op-3", malformed.stableKey(), database))
        assertEquals("INVALID", fixture.state("op-3"))

        val promoted = store.promoteUserInitiatedOperation(context, "op-1", automatic.stableKey(), database)
        assertTrue(promoted!!.userInitiated)
        assertTrue(fixture.read("op-1")!!.userInitiated)
        assertEquals("RETRYABLE", fixture.state("op-1"))
        assertEquals("op-2", store.promoteUserInitiatedOperation(context, "op-2", manual.stableKey(), database)?.operationId)
    }

    @Test
    fun `attempt ids are written once for legacy rows`() = runTest {
        val legacy = testSong(1L)
        val attempted = testSong(2L)
        fixture.upsert(request(legacy), "QUEUED")
        fixture.upsert(request(attempted, attemptId = 8L), "QUEUED")

        assertFalse(store.ensureAttemptId(context, "op-1", " ", 3L, database))
        assertFalse(store.ensureAttemptId(context, " ", legacy.stableKey(), 3L, database))
        assertFalse(store.ensureAttemptId(context, "op-1", legacy.stableKey(), 0L, database))
        assertFalse(store.ensureAttemptId(context, "missing", legacy.stableKey(), 3L, database))
        assertFalse(store.ensureAttemptId(context, "op-1", attempted.stableKey(), 3L, database))

        assertTrue(store.ensureAttemptId(context, "op-1", legacy.stableKey(), 3L, database))
        assertEquals(3L, fixture.read("op-1")!!.attemptId)
        assertTrue(store.ensureAttemptId(context, "op-2", attempted.stableKey(), 8L, database))
        assertFalse(store.ensureAttemptId(context, "op-2", attempted.stableKey(), 9L, database))
        assertEquals(8L, fixture.read("op-2")!!.attemptId)
    }

    @Test
    fun `core commit marking is idempotent once committed`() = runTest {
        fixture.upsert(request(testSong(1L)), "COMMITTING")
        fixture.upsert(request(testSong(2L)), "QUEUED")

        assertFalse(store.markCoreCommitted(context, " ", database))
        assertFalse(store.markCoreCommitted(context, "missing", database))
        assertFalse(store.markCoreCommitted(context, "op-2", database))
        assertTrue(store.markCoreCommitted(context, "op-1", database))
        assertEquals("CORE_COMMITTED", fixture.state("op-1"))
        assertTrue(store.markCoreCommitted(context, "op-1", database))
    }

    @Test
    fun `explicit cancellation picks the transfer stopped or commit boundary path`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED")
        fixture.upsert(request(testSong(2L)), "STOPPED")
        fixture.upsert(request(testSong(3L)), "CORE_COMMITTED")
        fixture.upsert(request(testSong(4L)), "COMPLETED")
        fixture.rewrite("op-2") { it.copy(stopRequestedByUser = true) }

        assertTrue(store.requestCancel(context, "op-1", database, 10L))
        assertEquals("CANCEL_REQUESTED", fixture.state("op-1"))
        assertTrue(store.requestCancel(context, "op-2", database, 10L))
        assertEquals("CANCEL_REQUESTED", fixture.state("op-2"))
        assertTrue(store.requestCancel(context, "op-3", database, 10L))
        assertEquals("CORE_COMMITTED", fixture.state("op-3"))
        assertTrue(fixture.row("op-3").stopRequestedByUser)
        assertFalse(store.requestCancel(context, "op-4", database, 10L))
    }

    @Test
    fun `stable key cancellation boundaries cancel only rows created before the cutoff`() = runTest {
        val song = testSong(1L)
        val other = testSong(2L)
        fixture.upsert(request(song, operationId = "op-old"), "RUNNING", createdAtMs = 100L)
        fixture.upsert(request(song, operationId = "op-commit"), "ASSETS_ENRICHING", createdAtMs = 150L)
        fixture.upsert(request(song, operationId = "op-excluded"), "QUEUED", createdAtMs = 120L)
        fixture.upsert(request(song, operationId = "op-new"), "QUEUED", createdAtMs = 900L)
        fixture.upsert(request(other, operationId = "op-other"), "QUEUED", createdAtMs = 100L)

        assertEquals(
            emptySet<String>(),
            store.requestCancelForStableKeysBefore(context, listOf(CancellationBoundary(" ", 500L)), database = database)
        )
        val cancelled = store.requestCancelForStableKeysBefore(
            context = context,
            boundaries = listOf(CancellationBoundary(song.stableKey(), 200L), CancellationBoundary(song.stableKey(), 500L)),
            excludedOperationIds = listOf(" op-excluded "),
            database = database
        )

        assertEquals(setOf("op-old", "op-commit"), cancelled)
        assertEquals("CANCEL_REQUESTED", fixture.state("op-old"))
        assertTrue(fixture.row("op-commit").stopRequestedByUser)
        assertEquals("QUEUED", fixture.state("op-excluded"))
        assertEquals("QUEUED", fixture.state("op-new"))
        assertEquals("QUEUED", fixture.state("op-other"))
    }

    @Test
    fun `host admission hands out bounded slots to the head of the pump queue`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED", queueOrder = 1)
        fixture.upsert(request(testSong(2L)), "QUEUED", queueOrder = 2)
        fixture.upsert(request(testSong(3L)), "RUNNING", queueOrder = 3)
        fixture.upsert(request(testSong(4L)), "RUNNING", queueOrder = 4)
        fixture.upsert(request(testSong(5L)), "COMPLETED", queueOrder = 5)
        fixture.rewrite("op-4") { it.copy(stopRequestedByUser = true) }

        assertFalse(store.tryAcquireHostAdmission(context, "op-1", capacity = 0, nowMs = 1_000L, database = database))
        assertFalse(store.tryAcquireHostAdmission(context, " ", capacity = 2, nowMs = 1_000L, database = database))
        assertFalse(store.tryAcquireHostAdmission(context, "missing", capacity = 2, nowMs = 1_000L, database = database))
        assertFalse(store.tryAcquireHostAdmission(context, "op-2", capacity = 2, nowMs = 1_000L, database = database))
        assertFalse(store.tryAcquireHostAdmission(context, "op-4", capacity = 2, nowMs = 1_000L, database = database))
        assertFalse(store.tryAcquireHostAdmission(context, "op-5", capacity = 2, nowMs = 1_000L, database = database))

        assertTrue(store.tryAcquireHostAdmission(context, "op-1", capacity = 2, nowMs = 1_000L, database = database))
        assertTrue(store.tryAcquireHostAdmission(context, "op-1", capacity = 2, nowMs = 1_000L, database = database))
        assertTrue(store.tryAcquireHostAdmission(context, "op-3", capacity = 2, nowMs = 1_000L, database = database))
        assertEquals(2, store.currentHostAdmissionCount(context, nowMs = 1_000L, database = database))
        assertFalse(store.tryAcquireHostAdmission(context, "op-2", capacity = 2, nowMs = 1_000L, database = database))

        store.releaseHostAdmissions(context, listOf(" ", ""), database)
        assertEquals(2, store.currentHostAdmissionCount(context, nowMs = 1_000L, database = database))
        store.releaseHostAdmissions(context, listOf("op-1", "op-3", "op-1"), database)
        assertEquals(0, store.currentHostAdmissionCount(context, nowMs = 1_000L, database = database))
        assertNull(fixture.row("op-1").hostProcessToken)
    }

    @Test
    fun `retry backoff keeps pump admission closed until the deadline`() = runTest {
        fixture.upsert(request(testSong(1L)), "RETRYABLE")
        fixture.rewrite("op-1") { it.copy(nextRetryAtMs = 5_000L) }

        assertFalse(store.tryAcquireHostAdmission(context, "op-1", capacity = 1, nowMs = 1_000L, database = database))
        assertTrue(store.tryAcquireHostAdmission(context, "op-1", capacity = 1, nowMs = 6_000L, database = database))
        assertNotNull(fixture.row("op-1").hostProcessToken)
    }

    @Test
    fun `fully cleared operations are purged unless they retain a recovery stop`() = runTest {
        fixture.upsert(request(testSong(1L)), "CANCELLED")
        fixture.upsert(request(testSong(2L)), "CORE_COMMITTED")
        fixture.upsert(request(testSong(3L)), "QUEUED")

        assertEquals(0, store.purgeFullyClearedOperations(context, listOf(" "), database))
        assertEquals(1, store.purgeFullyClearedOperations(context, listOf("op-1", "op-2"), database))

        assertNull(fixture.state("op-1"))
        val retained = fixture.row("op-2")
        assertTrue(retained.stopRequestedByUser)
        assertEquals("USER_CANCELLED", retained.lastErrorCode)
        assertEquals("QUEUED", fixture.state("op-3"))
    }

    private suspend fun markAlreadyDownloaded(
        operationId: String,
        stableKey: String,
        expectedAttemptId: Long? = null
    ): Boolean {
        return store.markAlreadyDownloadedCompleted(
            context = context,
            operationId = operationId,
            stableKey = stableKey,
            expectedAttemptId = expectedAttemptId,
            database = database
        )
    }

    private suspend fun reopenMissing(operationId: String, stableKey: String, attemptId: Long?): Boolean {
        return store.reopenMissingPostCoreArtifactForFreshTransfer(
            context = context,
            operationId = operationId,
            stableKey = stableKey,
            expectedAttemptId = attemptId,
            errorCode = "MISSING_CORE_AUDIO",
            database = database
        )
    }
}
