package moe.ouom.neriplayer.core.download.execution.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.entity.DOWNLOAD_BATCH_POST_CORE_PENDING_FRACTION_MILLI
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionRoomReadStoreTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val context get() = fixture.context
    private val database get() = fixture.database
    private val reads = DownloadExecutionRoomReadStore

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `operation snapshots decode valid rows and invalidate malformed payloads`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED")
        fixture.upsert(request(testSong(2L)), "RUNNING")
        fixture.rewrite("op-2") { it.copy(sourceHintJson = "not-json") }

        assertEquals(emptyMap<String, Any>(), reads.readOperationSnapshots(context, listOf(" ", ""), database))
        val snapshots = reads.readOperationSnapshots(context, listOf(" op-1 ", "op-2", "missing", "op-1"), database)

        assertEquals(setOf("op-1"), snapshots.keys)
        assertEquals("QUEUED", snapshots.getValue("op-1").state)
        assertEquals(testSong(1L).stableKey(), snapshots.getValue("op-1").request.song.stableKey())
        assertEquals("INVALID", fixture.state("op-2"))
    }

    @Test
    fun `latest network policy per song prefers the newest live operation`() = runTest {
        val song = testSong(1L)
        val other = testSong(2L)
        fixture.upsert(request(song, operationId = "op-old", requiresWifiNetwork = true), "QUEUED", createdAtMs = 10L)
        fixture.upsert(request(song, operationId = "op-new", requiresWifiNetwork = false), "QUEUED", createdAtMs = 20L)
        fixture.upsert(request(other, operationId = "op-stopped", requiresWifiNetwork = false), "RETRYABLE", createdAtMs = 30L)
        fixture.rewrite("op-stopped") { it.copy(stopRequestedByUser = true) }
        val states = listOf("QUEUED", "RETRYABLE")

        assertEquals(emptyMap<String, Boolean>(), readPolicies(listOf(" "), states))
        assertEquals(emptyMap<String, Boolean>(), readPolicies(listOf(song.stableKey()), emptyList()))
        assertEquals(mapOf(song.stableKey() to false), readPolicies(listOf(song.stableKey(), other.stableKey()), states))
        assertEquals(
            mapOf(song.stableKey() to false, other.stableKey() to false),
            readPolicies(listOf(song.stableKey(), other.stableKey()), states, excludeStopped = false)
        )
    }

    @Test
    fun `network policies fall back to the persisted payload when the cache is cold`() = runTest {
        val wifiOnly = testSong(1L)
        val mobile = testSong(2L)
        fixture.upsert(request(wifiOnly, requiresWifiNetwork = true), "QUEUED")
        fixture.upsert(request(mobile, requiresWifiNetwork = false), "QUEUED")
        DownloadExecutionRoomStore.networkPolicyByOperationId.clear()

        assertEquals(
            mapOf(wifiOnly.stableKey() to true, mobile.stableKey() to false),
            reads.readLatestOperationNetworkPoliciesByStatesAnyLibrary(context, listOf("QUEUED"), database = database)
        )
        assertEquals(true, DownloadExecutionRoomStore.cachedNetworkPolicy("op-1"))
        assertEquals(false, DownloadExecutionRoomStore.cachedNetworkPolicy("op-2"))
        assertEquals(
            emptyMap<String, Boolean>(),
            reads.readLatestOperationNetworkPoliciesByStatesAnyLibrary(context, emptyList(), database = database)
        )
    }

    @Test
    fun `network policy ties are broken by update time and then operation id`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song, operationId = "op-a", requiresWifiNetwork = true), "QUEUED", createdAtMs = 10L)
        fixture.upsert(request(song, operationId = "op-b", requiresWifiNetwork = false), "QUEUED", createdAtMs = 10L)
        fixture.rewrite("op-a") { it.copy(updatedAtMs = 50L) }
        fixture.rewrite("op-b") { it.copy(updatedAtMs = 40L) }
        DownloadExecutionRoomStore.networkPolicyByOperationId.clear()

        assertEquals(mapOf(song.stableKey() to true), readPolicies(listOf(song.stableKey()), listOf("QUEUED")))

        fixture.rewrite("op-b") { it.copy(updatedAtMs = 50L) }
        DownloadExecutionRoomStore.networkPolicyByOperationId.clear()
        assertEquals(mapOf(song.stableKey() to false), readPolicies(listOf(song.stableKey()), listOf("QUEUED")))
    }

    @Test
    fun `pump pages stop at retry deadlines and expose a cursor only for full pages`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED", queueOrder = 1, createdAtMs = 1L)
        fixture.upsert(request(testSong(2L)), "QUEUED", queueOrder = 2, createdAtMs = 2L)
        fixture.upsert(request(testSong(3L)), "RETRYABLE", queueOrder = 3, createdAtMs = 3L)
        fixture.upsert(request(testSong(4L)), "QUEUED", queueOrder = 4, createdAtMs = 4L)
        fixture.rewrite("op-3") { it.copy(nextRetryAtMs = 9_000L) }

        val first = reads.listSchedulableForPumpPage(context, null, limit = 2, database = database, nowMs = 1_000L)
        assertEquals(listOf("op-1", "op-2"), first.requests.map { it.operationId })
        assertEquals("op-2", first.nextCursor?.operationId)
        assertEquals(1, first.nextCursor?.recoveryPriority)
        assertEquals(9_000L, first.nextRetryAtMs)

        val blocked = reads.listSchedulableForPumpPage(context, first.nextCursor, limit = 2, database = database, nowMs = 1_000L)
        assertEquals(emptyList<String>(), blocked.requests.map { it.operationId })
        assertNull(blocked.nextCursor)

        val ready = reads.listSchedulableForPumpPage(context, null, limit = 10, database = database, nowMs = 10_000L)
        assertEquals(listOf("op-1", "op-2", "op-3", "op-4"), ready.requests.map { it.operationId })
        assertNull(ready.nextCursor)
    }

    @Test
    fun `pump pages drop malformed rows and give retried work recovery priority`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED", queueOrder = 1, createdAtMs = 1L)
        fixture.upsert(request(testSong(2L)), "QUEUED", queueOrder = 2, createdAtMs = 2L)
        fixture.rewrite("op-1") { it.copy(retryCount = 2) }
        fixture.rewrite("op-2") { it.copy(sourceHintJson = "not-json") }

        val page = reads.listSchedulableForPumpPage(context, null, limit = 1, database = database, nowMs = 1_000L)
        assertEquals(listOf("op-1"), page.requests.map { it.operationId })
        assertEquals(0, page.nextCursor?.recoveryPriority)

        val rest = reads.listSchedulableForPumpPage(context, page.nextCursor, limit = 0, database = database, nowMs = 1_000L)
        assertTrue(rest.requests.isEmpty())
        assertEquals("INVALID", fixture.state("op-2"))
    }

    @Test
    fun `presence checks and counts reflect the requested states`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED")
        fixture.upsert(request(testSong(2L)), "RUNNING")

        assertFalse(reads.hasAnyByStatesAnyLibrary(context, emptyList(), database))
        assertFalse(reads.hasAnyByStatesAnyLibrary(context, listOf("COMPLETED"), database))
        assertTrue(reads.hasAnyByStatesAnyLibrary(context, listOf("RUNNING"), database))
        assertEquals(0, reads.countByStates(context, emptyList(), database))
        assertEquals(2, reads.countByStates(context, listOf("QUEUED", "RUNNING"), database))
    }

    @Test
    fun `single operation rehoming validates identity before moving libraries`() = runTest {
        val moved = testSong(1L)
        val local = testSong(2L)
        val stopped = testSong(3L)
        val malformed = testSong(4L)
        val mismatched = testSong(5L)
        listOf(moved, local, stopped, malformed, mismatched).forEach { song -> fixture.upsert(request(song), "QUEUED") }
        val currentLibrary = fixture.row("op-2").libraryId
        listOf("op-1", "op-3", "op-4", "op-5").forEach { id -> fixture.rewrite(id) { it.copy(libraryId = "old-root") } }
        fixture.rewrite("op-3") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-4") { it.copy(sourceHintJson = "not-json") }
        fixture.rewrite("op-5") { it.copy(stableKey = "spoofed|key|") }

        assertFalse(rehome("op-1", " "))
        assertFalse(rehome(" ", moved.stableKey()))
        assertFalse(rehome("op-1", moved.stableKey(), states = emptyList()))
        assertFalse(rehome("missing", moved.stableKey()))
        assertFalse(rehome("op-1", moved.stableKey(), states = listOf("RUNNING")))
        assertFalse(rehome("op-3", stopped.stableKey()))
        assertFalse(rehome("op-4", malformed.stableKey()))
        assertEquals("INVALID", fixture.state("op-4"))
        assertFalse(rehome("op-5", "spoofed|key|"))
        assertEquals("INVALID", fixture.state("op-5"))

        assertTrue(rehome("op-2", local.stableKey()))
        assertTrue(rehome("op-1", moved.stableKey()))
        assertEquals(currentLibrary, fixture.row("op-1").libraryId)
        assertEquals("old-root", fixture.row("op-3").libraryId)
    }

    @Test
    fun `state listings skip user stops when asked and sort by queue order`() = runTest {
        fixture.upsert(request(testSong(1L)), "QUEUED", queueOrder = 3)
        fixture.upsert(request(testSong(2L)), "QUEUED", queueOrder = 1)
        fixture.upsert(request(testSong(3L)), "QUEUED", queueOrder = 2)
        fixture.upsert(request(testSong(4L)), "QUEUED", queueOrder = 4)
        fixture.rewrite("op-3") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-4") { it.copy(libraryId = "old-root", sourceHintJson = "not-json") }

        assertEquals(emptyList<Any>(), reads.listByStates(context, emptyList(), database = database))
        assertEquals(listOf("op-2", "op-3", "op-1"), reads.listByState(context, "QUEUED", database).map { it.request.operationId })
        assertEquals(
            listOf("op-2", "op-1"),
            reads.listByStates(context, listOf("QUEUED"), excludeUserStoppedOperations = true, database = database)
                .map { it.request.operationId }
        )
        assertEquals("QUEUED", fixture.state("op-4"))
        assertEquals(
            listOf("op-2", "op-1"),
            reads.listByStatesAnyLibrary(context, listOf("QUEUED"), excludeUserStoppedOperations = true, database = database)
                .map { it.request.operationId }
        )
        assertEquals("INVALID", fixture.state("op-4"))
        assertEquals(emptyList<Any>(), reads.listByStatesAnyLibrary(context, emptyList(), database = database))
    }

    @Test
    fun `progress restore hides retryable members of completed batches but keeps failures visible`() = runTest {
        val standalone = testSong(1L)
        val retryable = testSong(2L)
        val failed = testSong(3L)
        val open = testSong(4L)
        val retryableBatch = fixture.createBatch(listOf(retryable))
        val failedBatch = fixture.createBatch(listOf(failed))
        val openBatch = fixture.createBatch(listOf(open))
        fixture.upsert(request(standalone), "RUNNING", queueOrder = 1)
        fixture.enqueueBatchMembers(retryableBatch, listOf(retryable), state = "RETRYABLE")
        fixture.enqueueBatchMembers(failedBatch, listOf(failed), state = "INVALID")
        fixture.enqueueBatchMembers(openBatch, listOf(open), state = "QUEUED")
        listOf(retryable, failed).forEach { song ->
            DownloadExecutionRoomStore.markBatchMembersForOperation(
                context, "op-${song.id}", song.stableKey(), null, DownloadBatchMemberTerminal.COMPLETED, database = database
            )
        }
        fixture.rewrite("op-1") { it.copy(bytesWritten = 64L, totalBytes = 0L) }
        assertTrue(fixture.batchStateBits(retryableBatch) and DownloadBatchState.COMPLETED != 0)

        val entries = reads.listProgressEntries(context, database).associateBy { it.request.operationId }
        assertEquals(setOf("op-1", "op-3", "op-4"), entries.keys)
        assertEquals(64L, entries.getValue("op-1").bytesWritten)
        assertNull(entries.getValue("op-1").totalBytes)
        assertNull(entries.getValue("op-1").batchStateBits)
        assertEquals(fixture.batchStateBits(failedBatch), entries.getValue("op-3").batchStateBits)
        assertEquals(DownloadBatchState.OPEN, entries.getValue("op-4").batchStateBits)

        fixture.rewrite("op-4") { it.copy(libraryId = "old-root") }
        assertEquals(setOf("op-1", "op-3"), reads.listProgressEntries(context, database).map { it.request.operationId }.toSet())
        assertEquals(
            setOf("op-1", "op-3", "op-4"),
            reads.listProgressEntriesAnyLibrary(context, database).map { it.request.operationId }.toSet()
        )
    }

    @Test
    fun `progress checkpoints are accepted only for the current attempt`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song, attemptId = 5L), "RUNNING")

        assertFalse(reads.checkpointProgress(context, "op-1", " ", 5L, 10L, 100L, database))
        assertFalse(reads.checkpointProgress(context, "op-1", song.stableKey(), null, 10L, 100L, database))
        assertFalse(reads.checkpointProgress(context, "missing", song.stableKey(), 5L, 10L, 100L, database))
        assertFalse(reads.checkpointProgress(context, "op-1", song.stableKey(), 6L, 10L, 100L, database))
        assertTrue(reads.checkpointProgress(context, "op-1", song.stableKey(), 5L, 10L, 100L, database))
        assertTrue(reads.checkpointProgress(context, "op-1", song.stableKey(), 5L, 4L, 0L, database))

        val checkpoint = reads.readProgressCheckpoint(context, "op-1", song.stableKey(), 5L, database)
        assertEquals(10L, checkpoint?.bytesWritten)
        assertEquals(100L, checkpoint?.totalBytes)
        assertNull(reads.readProgressCheckpoint(context, "op-1", song.stableKey(), 6L, database))
        assertNull(reads.readProgressCheckpoint(context, "op-1", " ", 5L, database))
        assertNull(reads.readProgressCheckpoint(context, "op-1", song.stableKey(), 0L, database))
    }

    @Test
    fun `waiting storage promotion and identity reads ignore blank ids`() = runTest {
        fixture.upsert(request(testSong(1L)), WAITING_STORAGE_MUTATION_OPERATION_STATE, createdAtMs = 7L)
        fixture.upsert(request(testSong(2L)), "QUEUED")

        assertEquals(0, reads.promoteWaitingStorageMutations(context, listOf(" "), database))
        assertEquals(1, reads.promoteWaitingStorageMutations(context, listOf("op-1", "op-2"), database))
        assertEquals("QUEUED", fixture.state("op-1"))

        assertEquals(emptyMap<String, Any>(), reads.readOperationIdentities(context, listOf(""), database))
        assertEquals(7L, reads.readOperationIdentities(context, listOf("op-1", "op-1"), database).getValue("op-1").createdAtMs)
    }

    @Test
    fun `post core selection prefers degraded work and honours network and retry gates`() = runTest {
        fixture.upsert(request(testSong(1L)), "DEGRADED_COMPLETE", queueOrder = 5)
        fixture.upsert(request(testSong(2L)), "CORE_COMMITTED", queueOrder = 2)
        fixture.upsert(request(testSong(3L)), "ASSETS_ENRICHING", queueOrder = 1)
        fixture.upsert(request(testSong(4L), requiresWifiNetwork = false), "CORE_COMMITTED", queueOrder = 3)
        fixture.upsert(request(testSong(5L)), "ASSETS_ENRICHING", queueOrder = 4)
        fixture.rewrite("op-5") { it.copy(nextRetryAtMs = 9_000L) }

        assertEquals(emptyList<String>(), select(capacity = 0, allowWifi = true))
        assertEquals(listOf("op-1", "op-3", "op-2", "op-4"), select(capacity = 8, allowWifi = true))
        assertEquals(listOf("op-1", "op-3"), select(capacity = 2, allowWifi = true))
        assertEquals(listOf("op-4"), select(capacity = 8, allowWifi = false))
        assertEquals(
            listOf("op-2", "op-4"),
            select(capacity = 8, allowWifi = true, excluded = setOf("op-1"), executing = setOf("op-3"))
        )
    }

    @Test
    fun `post core entries skip stopped rows and invalidate malformed payloads`() = runTest {
        fixture.upsert(request(testSong(1L)), "CORE_COMMITTED", queueOrder = 1)
        fixture.upsert(request(testSong(2L)), "ASSETS_ENRICHING")
        fixture.upsert(request(testSong(3L)), "DEGRADED_COMPLETE")
        fixture.upsert(request(testSong(4L)), "QUEUED")
        fixture.rewrite("op-2") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-3") { it.copy(sourceHintJson = "not-json") }

        assertEquals(emptyList<Any>(), PostCoreRecoveryReadStore.entries(context, emptyList(), database))
        val entries = PostCoreRecoveryReadStore.entries(context, listOf("op-1", "op-2", "op-3", "op-4", "op-1"), database)

        assertEquals(listOf("op-1"), entries.map { it.request.operationId })
        assertEquals("CORE_COMMITTED", entries.single().state)
        assertEquals("INVALID", fixture.state("op-3"))
    }

    @Test
    fun `execution ownership requires a live operation without competitors`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song, operationId = "op-running"), "RUNNING")

        assertFalse(DownloadExecutionRoomStore.isExecutionOwned(context, "op-running", " ", database))
        assertTrue(DownloadExecutionRoomStore.isExecutionOwned(context, "op-running", " ${song.stableKey()} ", database))

        fixture.upsert(request(song, operationId = "op-replacement"), "QUEUED")
        assertFalse(DownloadExecutionRoomStore.isExecutionOwned(context, "op-running", song.stableKey(), database))
    }

    @Test
    fun `premature batch completions of post core work are reopened`() = runTest {
        val song = testSong(1L)
        val identity = fixture.createBatch(listOf(song))
        val (request) = fixture.enqueueBatchMembers(identity, listOf(song))
        DownloadExecutionRoomStore.markBatchMembersForOperation(
            context, request.operationId, song.stableKey(), null, DownloadBatchMemberTerminal.COMPLETED, database = database
        )
        assertEquals(DownloadBatchState.COMPLETED, fixture.batchStateBits(identity))

        assertEquals(0, DownloadExecutionRoomStore.repairPrematurePostCoreBatchCompletions(context, listOf(" "), database))
        assertEquals(
            1,
            DownloadExecutionRoomStore.repairPrematurePostCoreBatchCompletions(context, listOf(request.operationId), database)
        )

        val member = fixture.member(identity, song)
        assertEquals(DownloadBatchMemberTerminal.NONE, member.terminalBits)
        assertEquals(DOWNLOAD_BATCH_POST_CORE_PENDING_FRACTION_MILLI, member.maxFractionMilli)
        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(identity))
    }

    private suspend fun readPolicies(
        keys: List<String>,
        states: List<String>,
        excludeStopped: Boolean = true
    ): Map<String, Boolean> {
        return reads.readLatestOperationNetworkPoliciesForStableKeys(context, keys, states, excludeStopped, database)
    }

    private suspend fun rehome(
        operationId: String,
        stableKey: String,
        states: List<String> = DownloadExecutionRoomStore.ACTIVE_OPERATION_STATES
    ): Boolean {
        return reads.rehomeOperationToCurrentLibrary(context, operationId, stableKey, states, database)
    }

    private suspend fun select(
        capacity: Int,
        allowWifi: Boolean,
        excluded: Set<String> = emptySet(),
        executing: Set<String> = emptySet()
    ): List<String> {
        return PostCoreRecoveryReadStore.select(
            database = database,
            capacity = capacity,
            excluded = excluded,
            allowWifi = allowWifi,
            isExecuting = { id -> id in executing },
            nowMs = 1_000L
        ).map { it.operationId }
    }
}
