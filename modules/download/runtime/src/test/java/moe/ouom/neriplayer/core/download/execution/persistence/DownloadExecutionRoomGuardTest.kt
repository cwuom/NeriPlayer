package moe.ouom.neriplayer.core.download.execution.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.BatchMemberMutation
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionRoomGuardTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val context get() = fixture.context
    private val database get() = fixture.database
    private val store = DownloadExecutionRoomStore

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `terminal member updates reject invalid bits and stale identities`() = runTest {
        val song = testSong(1L)
        val identity = fixture.createBatch(listOf(song))
        val (request) = fixture.enqueueBatchMembers(identity, listOf(song))
        val key = song.stableKey()

        assertRejectsArgument { markTerminal(identity, key, request.operationId, terminalBits = 3) }
        assertRejectsArgument { markTerminal(identity, key, request.operationId, DownloadBatchMemberTerminal.NONE) }
        assertEquals(BatchMemberMutation.STALE, markTerminal(identity, " ", request.operationId))
        assertEquals(BatchMemberMutation.STALE, markTerminal(identity, key, "bad/id"))
        assertEquals(DownloadBatchMemberTerminal.NONE, fixture.member(identity, song).terminalBits)
    }

    @Test
    fun `operation member updates reject invalid bits and ignore unusable identities`() = runTest {
        val song = testSong(1L)
        val identity = fixture.createBatch(listOf(song))
        val (request) = fixture.enqueueBatchMembers(identity, listOf(song))
        val key = song.stableKey()

        assertRejectsArgument { markForOperation(request.operationId, key, terminalBits = 3) }
        assertRejectsArgument { markForOperation(request.operationId, key, DownloadBatchMemberTerminal.NONE) }
        assertEquals(0, markForOperation("..", key))
        assertEquals(0, markForOperation(request.operationId, ""))
        assertEquals(DownloadBatchMemberTerminal.NONE, fixture.member(identity, song).terminalBits)
    }

    @Test
    fun `batch snapshots require at least one song`() = runTest {
        assertRejectsArgument {
            store.createBatchSnapshot(context, songs = emptyList(), nowMs = 1L, database = database)
        }
    }

    @Test
    fun `cancellation identities are paged past a full page`() = runTest {
        val songs = (1L..256L).map { id -> testSong(id) }
        songs.forEach { song -> fixture.upsert(request(song, operationId = "op-%03d".format(song.id)), "QUEUED") }

        val identities = DownloadExecutionRoomCancellationStore.listCancellationIdentitiesAnyLibrary(context, database)

        assertEquals(256, identities.size)
        assertEquals("op-001", identities.first().operationId)
        assertEquals("op-256", identities.last().operationId)
    }

    @Test
    fun `newer network policy wins regardless of scan order`() = runTest {
        val song = testSong(1L)
        val key = song.stableKey()
        fixture.upsert(request(song, operationId = "op-a", requiresWifiNetwork = true), "QUEUED", createdAtMs = 10L)
        fixture.upsert(request(song, operationId = "op-b", requiresWifiNetwork = false), "QUEUED", createdAtMs = 20L)
        fixture.rewrite("op-a") { it.copy(updatedAtMs = 100L) }
        fixture.rewrite("op-b") { it.copy(updatedAtMs = 50L) }

        assertEquals(
            mapOf(key to false),
            DownloadExecutionRoomReadStore.readLatestOperationNetworkPoliciesForStableKeys(
                context, listOf(key), listOf("QUEUED"), database = database
            )
        )

        fixture.rewrite("op-b") { it.copy(createdAtMs = 10L, updatedAtMs = 200L) }
        assertEquals(
            mapOf(key to false),
            DownloadExecutionRoomReadStore.readLatestOperationNetworkPoliciesByStatesAnyLibrary(
                context, listOf("QUEUED"), database = database
            )
        )
    }

    private suspend fun markTerminal(
        identity: DownloadExecutionRoomStore.DownloadBatchIdentity,
        stableKey: String,
        operationId: String,
        terminalBits: Int = DownloadBatchMemberTerminal.COMPLETED
    ): BatchMemberMutation {
        return store.markBatchMemberTerminal(
            context, identity, stableKey, operationId, null, terminalBits, database = database, nowMs = 2_000L
        )
    }

    private suspend fun markForOperation(
        operationId: String,
        stableKey: String,
        terminalBits: Int = DownloadBatchMemberTerminal.COMPLETED
    ): Int {
        return store.markBatchMembersForOperation(
            context, operationId, stableKey, null, terminalBits, database = database, nowMs = 2_000L
        )
    }
}
