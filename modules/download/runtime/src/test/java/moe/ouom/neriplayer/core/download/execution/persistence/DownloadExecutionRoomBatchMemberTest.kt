package moe.ouom.neriplayer.core.download.execution.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.BatchMemberBinding
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.BatchMemberMutation
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.DownloadBatchIdentity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.identity.stableKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionRoomBatchMemberTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val context get() = fixture.context
    private val database get() = fixture.database

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `progress updates only move the bound member forward and ignore other attempts`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val identity = fixture.createBatch(listOf(first, second))
        val (firstRequest) = fixture.enqueueBatchMembers(identity, listOf(first, second), attemptId = 5L)

        assertEquals(1, updateProgress(firstRequest.operationId, first.stableKey(), 5L, 400))
        assertEquals(400, fixture.member(identity, first).maxFractionMilli)

        assertEquals(1, updateProgress(firstRequest.operationId, first.stableKey(), null, 2_000))
        assertEquals(1000, fixture.member(identity, first).maxFractionMilli)
        assertEquals(1, updateProgress(firstRequest.operationId, first.stableKey(), 5L, 100))
        assertEquals(1000, fixture.member(identity, first).maxFractionMilli)

        assertEquals(0, updateProgress(firstRequest.operationId, first.stableKey(), 6L, 900))
        assertEquals(0, updateProgress(firstRequest.operationId, second.stableKey(), 5L, 900))
        assertEquals(0, updateProgress(firstRequest.operationId, "   ", 5L, 900))
        assertEquals(0, updateProgress(" ", first.stableKey(), 5L, 900))
        assertEquals(0, fixture.member(identity, second).maxFractionMilli)
    }

    @Test
    fun `terminal callbacks complete the batch once every member settles`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val identity = fixture.createBatch(listOf(first, second))
        val (firstRequest, secondRequest) = fixture.enqueueBatchMembers(identity, listOf(first, second))
        updateProgress(secondRequest.operationId, second.stableKey(), null, 300)

        assertEquals(
            1,
            markForOperation(firstRequest.operationId, first.stableKey(), null, DownloadBatchMemberTerminal.COMPLETED)
        )
        assertEquals(DownloadBatchMemberTerminal.COMPLETED, fixture.member(identity, first).terminalBits)
        assertEquals(1000, fixture.member(identity, first).maxFractionMilli)
        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(identity))

        assertEquals(
            1,
            markForOperation(
                secondRequest.operationId,
                second.stableKey(),
                null,
                DownloadBatchMemberTerminal.FAILED,
                fractionMilli = 100
            )
        )
        val failed = fixture.member(identity, second)
        assertEquals(DownloadBatchMemberTerminal.FAILED, failed.terminalBits)
        assertEquals(300, failed.maxFractionMilli)
        assertEquals(DownloadBatchState.COMPLETED, fixture.batchStateBits(identity))

        assertEquals(
            0,
            markForOperation(firstRequest.operationId, first.stableKey(), null, DownloadBatchMemberTerminal.CANCELLED)
        )
        assertEquals(0, markForOperation("", first.stableKey(), null, DownloadBatchMemberTerminal.COMPLETED))
        assertEquals(
            0,
            markForOperation(firstRequest.operationId, " ", null, DownloadBatchMemberTerminal.COMPLETED)
        )
    }

    @Test
    fun `terminal callbacks for a different attempt leave the member pending`() = runTest {
        val song = testSong(1L)
        val identity = fixture.createBatch(listOf(song))
        val (request) = fixture.enqueueBatchMembers(identity, listOf(song), attemptId = 3L)

        assertEquals(
            0,
            markForOperation(request.operationId, song.stableKey(), 4L, DownloadBatchMemberTerminal.COMPLETED)
        )
        assertEquals(DownloadBatchMemberTerminal.NONE, fixture.member(identity, song).terminalBits)

        assertEquals(
            1,
            markForOperation(request.operationId, song.stableKey(), 3L, DownloadBatchMemberTerminal.COMPLETED)
        )
        assertEquals(DownloadBatchState.COMPLETED, fixture.batchStateBits(identity))
    }

    @Test
    fun `identity based terminal marks report applied idempotent stale and missing outcomes`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val identity = fixture.createBatch(listOf(first, second))
        val (firstRequest, secondRequest) =
            fixture.enqueueBatchMembers(identity, listOf(first, second), attemptId = 9L)

        assertEquals(
            BatchMemberMutation.APPLIED,
            markTerminal(identity, first.stableKey(), firstRequest.operationId, 9L, DownloadBatchMemberTerminal.COMPLETED)
        )
        assertEquals(
            BatchMemberMutation.IDEMPOTENT,
            markTerminal(identity, first.stableKey(), firstRequest.operationId, 9L, DownloadBatchMemberTerminal.COMPLETED)
        )
        assertEquals(
            BatchMemberMutation.STALE,
            markTerminal(identity, first.stableKey(), firstRequest.operationId, 9L, DownloadBatchMemberTerminal.FAILED)
        )
        assertEquals(
            BatchMemberMutation.STALE,
            markTerminal(identity, second.stableKey(), secondRequest.operationId, 8L, DownloadBatchMemberTerminal.FAILED)
        )
        assertEquals(
            BatchMemberMutation.STALE,
            markTerminal(identity, "  ", secondRequest.operationId, 9L, DownloadBatchMemberTerminal.FAILED)
        )
        assertEquals(
            BatchMemberMutation.STALE,
            markTerminal(identity, second.stableKey(), " ", 9L, DownloadBatchMemberTerminal.FAILED)
        )
        assertEquals(
            BatchMemberMutation.MISSING,
            markTerminal(
                DownloadBatchIdentity("missing-batch", identity.generation),
                second.stableKey(),
                secondRequest.operationId,
                9L,
                DownloadBatchMemberTerminal.FAILED
            )
        )
        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(identity))

        assertEquals(
            BatchMemberMutation.APPLIED,
            markTerminal(identity, second.stableKey(), secondRequest.operationId, 9L, DownloadBatchMemberTerminal.FAILED)
        )
        assertEquals(DownloadBatchState.COMPLETED, fixture.batchStateBits(identity))
    }

    @Test
    fun `binding member operations requires matching operation identity and an existing batch`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val third = testSong(3L)
        val identity = fixture.createBatch(listOf(first, second, third))
        val firstRequest = request(first, batch = identity)
        val secondRequest = request(second, batch = identity)
        val unbatchedRequest = request(third)
        fixture.upsert(firstRequest, "QUEUED")
        fixture.upsert(secondRequest, "QUEUED")
        fixture.upsert(unbatchedRequest, "QUEUED")

        val bound = DownloadExecutionRoomStore.bindBatchMemberOperations(
            context = context,
            identity = identity,
            bindings = listOf(
                BatchMemberBinding(" ${first.stableKey()} ", firstRequest.operationId, 11L),
                BatchMemberBinding(first.stableKey(), "duplicate-key-op", 12L),
                BatchMemberBinding(second.stableKey(), firstRequest.operationId, 11L),
                BatchMemberBinding(third.stableKey(), unbatchedRequest.operationId, 0L),
                BatchMemberBinding("  ", secondRequest.operationId, 11L),
                BatchMemberBinding(second.stableKey(), "   ", 11L)
            ),
            database = database
        )

        assertEquals(1, bound)
        val firstMember = fixture.member(identity, first)
        assertEquals(firstRequest.operationId, firstMember.operationId)
        assertEquals(11L, firstMember.attemptId)
        assertNull(fixture.member(identity, second).operationId)
        assertNull(fixture.member(identity, third).operationId)

        assertEquals(
            0,
            DownloadExecutionRoomStore.bindBatchMemberOperations(
                context = context,
                identity = DownloadBatchIdentity(identity.batchId, identity.generation + 1L),
                bindings = listOf(BatchMemberBinding(second.stableKey(), secondRequest.operationId, null)),
                database = database
            )
        )
        assertEquals(
            0,
            DownloadExecutionRoomStore.bindBatchMemberOperations(
                context = context,
                identity = identity,
                bindings = listOf(BatchMemberBinding(" ", " ", null)),
                database = database
            )
        )
    }

    @Test
    fun `cancelling named members ignores blank keys and unknown batches`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val identity = fixture.createBatch(listOf(first, second))

        assertEquals(0, cancelMembers(identity, listOf(" ", "")))
        assertEquals(0, cancelMembers(DownloadBatchIdentity("unknown", 1L), listOf(first.stableKey())))

        assertEquals(1, cancelMembers(identity, listOf(" ${first.stableKey()} ", first.stableKey())))
        assertEquals(DownloadBatchMemberTerminal.CANCELLED, fixture.member(identity, first).terminalBits)
        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(identity))

        assertEquals(1, cancelMembers(identity, listOf(second.stableKey())))
        assertEquals(DownloadBatchState.COMPLETED, fixture.batchStateBits(identity))
    }

    @Test
    fun `initial completion marks only unbound members and settles the batch`() = runTest {
        val cached = testSong(1L)
        val queued = testSong(2L)
        val identity = fixture.createBatch(listOf(cached, queued))
        fixture.enqueueBatchMembers(identity, listOf(queued))

        assertEquals(0, markInitialCompleted(identity, listOf(" ")))
        assertEquals(1, markInitialCompleted(identity, listOf(cached.stableKey(), queued.stableKey())))

        val cachedMember = fixture.member(identity, cached)
        assertEquals(DownloadBatchMemberTerminal.COMPLETED, cachedMember.terminalBits)
        assertTrue(cachedMember.initiallyCompleted)
        assertEquals(DownloadBatchMemberTerminal.NONE, fixture.member(identity, queued).terminalBits)
        assertEquals(DownloadBatchState.OPEN, fixture.batchStateBits(identity))
    }

    @Test
    fun `preparing members for transfer clears initial completion only on open batches`() = runTest {
        val cached = testSong(1L)
        val other = testSong(2L)
        val identity = fixture.createBatch(
            songs = listOf(cached, other),
            initiallyCompletedSongKeys = setOf(cached.stableKey())
        )
        assertTrue(fixture.member(identity, cached).initiallyCompleted)

        assertEquals(0, prepareForTransfer(identity, listOf("  ")))
        assertEquals(0, prepareForTransfer(DownloadBatchIdentity(identity.batchId, 99L), listOf(cached.stableKey())))
        assertEquals(1, prepareForTransfer(identity, listOf(cached.stableKey(), cached.stableKey())))

        val reopened = fixture.member(identity, cached)
        assertFalse(reopened.initiallyCompleted)
        assertEquals(DownloadBatchMemberTerminal.NONE, reopened.terminalBits)

        DownloadExecutionRoomStore.markAllOpenBatchesCancelled(context, database = database)
        assertEquals(0, prepareForTransfer(identity, listOf(other.stableKey())))
    }

    @Test
    fun `a batch whose members all start completed is closed immediately`() = runTest {
        val song = testSong(1L)
        val identity = fixture.createBatch(listOf(song, song), initiallyCompletedSongKeys = setOf(song.stableKey()))

        assertEquals(DownloadBatchState.COMPLETED, fixture.batchStateBits(identity))
        assertEquals(1, fixture.batchDao.listMembers(identity.batchId).size)
        assertEquals(
            emptyList<DownloadBatchIdentity>(),
            DownloadExecutionRoomStore.findOpenBatchIdentitiesForStableKeys(context, listOf(song.stableKey()), database)
        )
    }

    @Test
    fun `open batch lookups match pending members and skip blank keys`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val identity = fixture.createBatch(listOf(first, second))

        assertEquals(
            listOf(identity),
            DownloadExecutionRoomStore.findOpenBatchIdentitiesForStableKeys(
                context,
                listOf(" ${first.stableKey()} ", second.stableKey()),
                database
            )
        )
        assertEquals(
            emptyList<DownloadBatchIdentity>(),
            DownloadExecutionRoomStore.findOpenBatchIdentitiesForStableKeys(context, listOf(" "), database)
        )
        assertEquals(
            setOf(first.stableKey(), second.stableKey()),
            DownloadExecutionRoomStore.findPendingStableKeysForOpenBatches(context, listOf(identity, identity), database)
        )
    }

    private suspend fun updateProgress(operationId: String, stableKey: String, attemptId: Long?, fraction: Int): Int {
        return DownloadExecutionRoomStore.updateBatchMembersForOperation(
            context = context,
            operationId = operationId,
            stableKey = stableKey,
            attemptId = attemptId,
            fractionMilli = fraction,
            database = database
        )
    }

    private suspend fun markForOperation(
        operationId: String,
        stableKey: String,
        attemptId: Long?,
        terminalBits: Int,
        fractionMilli: Int = 0
    ): Int {
        return DownloadExecutionRoomStore.markBatchMembersForOperation(
            context = context,
            operationId = operationId,
            stableKey = stableKey,
            attemptId = attemptId,
            terminalBits = terminalBits,
            fractionMilli = fractionMilli,
            database = database
        )
    }

    private suspend fun markTerminal(
        identity: DownloadBatchIdentity,
        stableKey: String,
        operationId: String,
        attemptId: Long?,
        terminalBits: Int
    ): BatchMemberMutation {
        return DownloadExecutionRoomStore.markBatchMemberTerminal(
            context = context,
            identity = identity,
            stableKey = stableKey,
            operationId = operationId,
            attemptId = attemptId,
            terminalBits = terminalBits,
            database = database
        )
    }

    private suspend fun cancelMembers(identity: DownloadBatchIdentity, keys: List<String>): Int {
        return DownloadExecutionRoomStore.markBatchMembersCancelled(context, identity, keys, database)
    }

    private suspend fun markInitialCompleted(identity: DownloadBatchIdentity, keys: List<String>): Int {
        return DownloadExecutionRoomStore.markInitialBatchMembersCompleted(context, identity, keys, database)
    }

    private suspend fun prepareForTransfer(identity: DownloadBatchIdentity, keys: List<String>): Int {
        return DownloadExecutionRoomStore.prepareBatchMembersForTransfer(context, identity, keys, database)
    }
}
