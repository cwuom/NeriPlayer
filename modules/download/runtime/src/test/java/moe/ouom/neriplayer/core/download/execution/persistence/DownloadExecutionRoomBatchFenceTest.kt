package moe.ouom.neriplayer.core.download.execution.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.DownloadBatchIdentity
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.OperationIdentity
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionRoomBatchFenceTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val context get() = fixture.context
    private val database get() = fixture.database

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `stable key network waits fence only batches that contain the key`() = runTest {
        val shared = testSong(1L)
        val other = testSong(2L)
        val withKey = fixture.createBatch(listOf(shared))
        val withoutKey = fixture.createBatch(listOf(other))

        assertEquals(0, DownloadExecutionRoomStore.markBatchesNetworkWaiting(context, listOf(" "), 3L, database))
        assertRejectsArgument {
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(context, listOf(shared.stableKey()), -1L, database)
        }

        assertEquals(
            1,
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(context, listOf(" ${shared.stableKey()} "), 3L, database)
        )
        assertEquals(DownloadBatchState.OPEN or DownloadBatchState.NETWORK_WAIT, fixture.batchStateBits(withKey))
        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(withoutKey))
        assertEquals(3L, fixture.batchDao.findBatch(withKey.batchId, withKey.generation)?.networkGeneration)
    }

    @Test
    fun `identity network waits honour the captured network generation`() = runTest {
        val identity = fixture.createBatch(listOf(testSong(1L)), networkGeneration = 4L)

        assertEquals(0, markWaiting(emptyList(), 5L, null))
        assertEquals(0, markWaiting(listOf(DownloadBatchIdentity("missing", 1L)), 5L, null))
        assertEquals(0, markWaiting(listOf(identity), 5L, expected = 3L))
        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(identity))

        assertEquals(1, markWaiting(listOf(identity, identity), 5L, expected = 4L))
        assertEquals(DownloadBatchState.OPEN or DownloadBatchState.NETWORK_WAIT, fixture.batchStateBits(identity))
        assertEquals(1, markWaiting(listOf(identity), 6L, expected = null))
        assertEquals(6L, fixture.batchDao.findBatch(identity.batchId, identity.generation)?.networkGeneration)
    }

    @Test
    fun `confirmed Wi-Fi releases waits up to the current network generation only`() = runTest {
        val older = fixture.createBatch(listOf(testSong(1L)))
        val newer = fixture.createBatch(listOf(testSong(2L)))
        markWaiting(listOf(older), 2L, null)
        markWaiting(listOf(newer), 9L, null)

        assertRejectsArgument {
            DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(context, -1L, database)
        }
        assertEquals(1, DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(context, 5L, database))

        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(older))
        assertEquals(DownloadBatchState.OPEN or DownloadBatchState.NETWORK_WAIT, fixture.batchStateBits(newer))
    }

    @Test
    fun `mobile data consent clears the wait and rewrites pending member requests`() = runTest {
        val pending = testSong(1L)
        val finished = testSong(2L)
        val identity = fixture.createBatch(listOf(pending, finished), networkGeneration = 1L)
        val (pendingRequest, finishedRequest) = fixture.enqueueBatchMembers(identity, listOf(pending, finished))
        DownloadExecutionRoomStore.markBatchMembersForOperation(
            context,
            finishedRequest.operationId,
            finished.stableKey(),
            null,
            DownloadBatchMemberTerminal.COMPLETED,
            database = database
        )
        markWaiting(listOf(identity), 2L, expected = 1L)

        assertEquals(0, allowMobile(emptyList(), 2L, 2L))
        assertRejectsArgument { allowMobile(listOf(identity), -1L, 2L) }
        assertEquals(0, allowMobile(listOf(identity), expected = 3L, current = 3L))
        assertTrue(fixture.read(pendingRequest.operationId)!!.requiresWifiNetwork)

        assertEquals(1, allowMobile(listOf(identity, identity), expected = 2L, current = 2L))

        assertEquals(
            DownloadBatchState.OPEN or DownloadBatchState.USER_MOBILE_ALLOWED,
            fixture.batchStateBits(identity)
        )
        assertFalse(fixture.read(pendingRequest.operationId)!!.requiresWifiNetwork)
        assertTrue(fixture.read(finishedRequest.operationId)!!.requiresWifiNetwork)
        assertEquals(false, DownloadExecutionRoomStore.cachedNetworkPolicy(pendingRequest.operationId))
    }

    @Test
    fun `batch clear captures pending members and finalizes to cancelled`() = runTest {
        val bound = testSong(1L)
        val unbound = testSong(2L)
        val done = testSong(3L)
        val identity = fixture.createBatch(listOf(bound, unbound, done), initiallyCompletedSongKeys = setOf(done.stableKey()))
        val (boundRequest) = fixture.enqueueBatchMembers(identity, listOf(bound))

        val empty = DownloadExecutionRoomStore.beginBatchClear(context, 0L, database)
        assertTrue(empty.identities.isEmpty() && empty.stableKeys.isEmpty())

        val capture = DownloadExecutionRoomStore.beginBatchClear(context, 5L, database, nowMs = 2_000L)

        assertEquals(listOf(identity), capture.identities)
        assertEquals(setOf(bound.stableKey(), unbound.stableKey()), capture.stableKeys)
        assertEquals(
            listOf(OperationIdentity(boundRequest.operationId, bound.stableKey(), createdAtMs = 1_000L)),
            capture.operationIdentities
        )
        assertTrue(fixture.batchStateBits(identity) and DownloadBatchState.CLEARING != 0)
        assertEquals(DownloadBatchMemberTerminal.CANCELLED, fixture.member(identity, unbound).terminalBits)
        assertEquals(DownloadBatchMemberTerminal.COMPLETED, fixture.member(identity, done).terminalBits)

        assertTrue(DownloadExecutionRoomStore.finalizeBatchClear(context, emptyList(), database))
        assertTrue(DownloadExecutionRoomStore.finalizeBatchClear(context, listOf(identity, identity), database))
        assertEquals(DownloadBatchState.CANCELLED, fixture.batchStateBits(identity))
        assertTrue(DownloadExecutionRoomStore.finalizeBatchClear(context, listOf(DownloadBatchIdentity("gone", 1L)), database))
    }

    @Test
    fun `cancelling all open batches cancels pending members`() = runTest {
        val song = testSong(1L)
        val identity = fixture.createBatch(listOf(song))

        assertEquals(1, DownloadExecutionRoomStore.markAllOpenBatchesCancelled(context, database))

        assertEquals(DownloadBatchState.CANCELLED, fixture.batchStateBits(identity))
        assertEquals(DownloadBatchMemberTerminal.CANCELLED, fixture.member(identity, song).terminalBits)
    }

    @Test
    fun `process restart recovery requeues orphaned and retryable operations`() = runTest {
        assertEquals(emptySet<String>(), DownloadExecutionRoomStore.requeueOrphanedRunningOperations(context, database))
        assertEquals(
            emptySet<String>(),
            DownloadExecutionRoomStore.rearmRetryableOperationsAfterProcessRestart(context, database)
        )
        assertEquals(emptySet<String>(), DownloadExecutionRoomStore.clearRetryDeadlinesForImmediateRecovery(context, database))

        val running = testSong(1L)
        val retryable = testSong(2L)
        fixture.upsert(request(running), "RUNNING")
        fixture.upsert(request(retryable), "RETRYABLE")
        fixture.rewrite("op-2") { it.copy(nextRetryAtMs = Long.MAX_VALUE) }

        assertEquals(
            setOf(running.stableKey()),
            DownloadExecutionRoomStore.requeueOrphanedRunningOperations(context, database)
        )
        assertEquals("RETRYABLE", fixture.state("op-1"))
        assertEquals("PROCESS_RESTART_RECOVERY", fixture.row("op-1").lastErrorCode)

        assertEquals(
            setOf(retryable.stableKey()),
            DownloadExecutionRoomStore.clearRetryDeadlinesForImmediateRecovery(context, database)
        )
        assertEquals(null, fixture.row("op-2").nextRetryAtMs)

        assertEquals(
            setOf(running.stableKey(), retryable.stableKey()),
            DownloadExecutionRoomStore.rearmRetryableOperationsAfterProcessRestart(context, database)
        )
        assertEquals("QUEUED", fixture.state("op-1"))
        assertEquals("QUEUED", fixture.state("op-2"))
    }

    @Test
    fun `explicit resume reopens user stops but never user cancellations`() = runTest {
        val stopped = testSong(1L)
        val cancelled = testSong(2L)
        fixture.upsert(request(stopped), "STOPPED")
        fixture.upsert(request(cancelled), "STOPPED")
        fixture.rewrite("op-1") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-2") { it.copy(stopRequestedByUser = true, lastErrorCode = "USER_CANCELLED") }

        assertFalse(DownloadExecutionRoomStore.prepareExplicitResume(context, "op-1", " ", database))
        assertFalse(DownloadExecutionRoomStore.prepareExplicitResume(context, "op-2", cancelled.stableKey(), database))
        assertTrue(DownloadExecutionRoomStore.prepareExplicitResume(context, "op-1", " ${stopped.stableKey()} ", database))
        assertEquals("RETRYABLE", fixture.state("op-1"))
        assertTrue(DownloadExecutionRoomStore.isExplicitResumePending(context, "op-1", database))

        assertFalse(DownloadExecutionRoomStore.restoreExplicitStop(context, "op-1", "", "USER_STOPPED", database))
        assertTrue(DownloadExecutionRoomStore.restoreExplicitStop(context, "op-1", stopped.stableKey(), "USER_STOPPED", database))
        val restored = fixture.row("op-1")
        assertEquals("STOPPED", restored.state)
        assertTrue(restored.stopRequestedByUser)
        assertEquals("USER_STOPPED", restored.lastErrorCode)
    }

    @Test
    fun `bulk explicit resume only touches user stopped rows for the requested keys`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val unrelated = testSong(3L)
        listOf(first, second, unrelated).forEach { song -> fixture.upsert(request(song), "STOPPED") }
        listOf("op-1", "op-3").forEach { id -> fixture.rewrite(id) { it.copy(stopRequestedByUser = true) } }

        assertEquals(0, DownloadExecutionRoomStore.prepareExplicitResumesForStableKeys(context, listOf(" "), database))
        assertEquals(
            1,
            DownloadExecutionRoomStore.prepareExplicitResumesForStableKeys(
                context,
                listOf(first.stableKey(), second.stableKey()),
                database
            )
        )
        assertEquals("RETRYABLE", fixture.state("op-1"))
        assertEquals("STOPPED", fixture.state("op-2"))
        assertEquals("STOPPED", fixture.state("op-3"))
    }

    @Test
    fun `process exit recovery requeues transfers and releases committed hosts`() = runTest {
        val queued = testSong(1L)
        val committing = testSong(2L)
        val stopped = testSong(3L)
        val automatic = testSong(4L)
        fixture.upsert(request(queued), "RUNNING")
        fixture.upsert(request(committing), "COMMITTING")
        fixture.upsert(request(stopped), "RUNNING")
        fixture.upsert(request(automatic, userInitiated = false), "RUNNING")
        fixture.rewrite("op-2") { it.copy(hostProcessToken = "old-process", hostAdmittedAtMs = 1L) }
        fixture.rewrite("op-3") { it.copy(stopRequestedByUser = true) }

        val entries = listOf(queued, committing, stopped, automatic, queued).map { song ->
            DownloadExecutionRoomStore.StateEntry(
                request = request(song, userInitiated = song != automatic),
                queueOrder = 0,
                createdAtMs = 0L
            )
        }
        val released = DownloadExecutionRoomStore.markUserRequestedProcessExitOperations(context, entries, database)

        assertEquals(setOf(queued.stableKey(), committing.stableKey()), released)
        assertEquals("RETRYABLE", fixture.state("op-1"))
        assertEquals("PROCESS_EXIT_RECOVERY", fixture.row("op-1").lastErrorCode)
        assertEquals("COMMITTING", fixture.state("op-2"))
        assertEquals(null, fixture.row("op-2").hostProcessToken)
        assertEquals("RUNNING", fixture.state("op-3"))
        assertEquals("RUNNING", fixture.state("op-4"))
        assertEquals(
            emptySet<String>(),
            DownloadExecutionRoomStore.markUserRequestedProcessExitOperations(context, entries.take(0), database)
        )
    }

    private suspend fun markWaiting(
        identities: List<DownloadBatchIdentity>,
        generation: Long,
        expected: Long?
    ): Int {
        return DownloadExecutionRoomStore.markBatchesNetworkWaiting(
            context = context,
            identities = identities,
            networkGeneration = generation,
            expectedNetworkGeneration = expected,
            database = database
        )
    }

    private suspend fun allowMobile(identities: List<DownloadBatchIdentity>, expected: Long, current: Long): Int {
        return DownloadExecutionRoomStore.allowBatchesMobileData(
            context = context,
            identities = identities,
            expectedNetworkGeneration = expected,
            networkGeneration = current,
            database = database
        )
    }
}
