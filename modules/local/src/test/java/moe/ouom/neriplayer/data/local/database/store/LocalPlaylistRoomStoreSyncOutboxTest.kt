package moe.ouom.neriplayer.data.local.database.store

import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxStatus
import moe.ouom.neriplayer.data.local.database.entity.SyncReplicaCheckpointEntity
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutation
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutationOutbox
import moe.ouom.neriplayer.data.local.playlist.PlaylistSongDeletionRemoval
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn

class LocalPlaylistRoomStoreSyncOutboxTest {
    private val room = InlineTransactionDatabase()
    private val sync = InMemorySyncOutboxDao()
    private val store = LocalPlaylistRoomStore(room.database)

    init {
        doReturn(sync).`when`(room.database).syncMetadataDao()
    }

    @Test
    fun `pending mutations round trip through the outbox in sequence order`() = runTest {
        assertNull(store.readPendingSyncMutationOutbox())
        val outbox = LocalPlaylistSyncMutationOutbox(listOf(songDeletion, playlistRestore))

        store.writePendingSyncMutationOutbox(outbox, now = 9)
        sync.pageCursors.clear()

        assertEquals(outbox, store.readPendingSyncMutationOutbox())
        assertEquals(listOf(0L, 2L), sync.pageCursors)
        val rows = sync.rows()
        assertEquals(listOf(1L, 2L), rows.map { it.sequence })
        assertTrue(
            rows.all {
                it.payloadVersion == 1 && it.status == SyncOutboxStatus.PENDING &&
                    it.expectedDomainRevision == 0L && it.createdAt == 9L && it.updatedAt == 9L
            }
        )
        assertTrue(rows[0].operationId.startsWith("playlist-mutation-") && rows[0].operationId.endsWith("-0"))
        assertTrue(rows[1].operationId.startsWith("playlist-mutation-") && rows[1].operationId.endsWith("-1"))
    }

    @Test
    fun `rewriting or clearing the outbox only replaces pending rows`() = runTest {
        sync.insertOutbox(delivered)

        store.writePendingSyncMutationOutbox(LocalPlaylistSyncMutationOutbox(listOf(songDeletion)), now = 2)
        store.writePendingSyncMutationOutbox(LocalPlaylistSyncMutationOutbox(listOf(playlistRestore)), now = 3)

        assertEquals(LocalPlaylistSyncMutationOutbox(listOf(playlistRestore)), store.readPendingSyncMutationOutbox())
        assertEquals(listOf(SyncOutboxStatus.DELIVERED, SyncOutboxStatus.PENDING), sync.rows().map { it.status })

        store.clearPendingSyncMutationOutbox()

        assertNull(store.readPendingSyncMutationOutbox())
        assertEquals(listOf(delivered.copy(sequence = 1)), sync.rows())
    }

    @Test
    fun `a pending row with an unknown payload version fails the read`() = runTest {
        store.writePendingSyncMutationOutbox(LocalPlaylistSyncMutationOutbox(listOf(songDeletion)), now = 2)
        sync.insertOutbox(
            SyncOutboxEntity(
                operationId = "future",
                expectedDomainRevision = 0,
                payloadVersion = 2,
                mutationPayloadJson = "{}",
                createdAt = 4
            )
        )
        room.transactionLog.clear()

        val failure = expectFailure<IOException> { store.readPendingSyncMutationOutbox() }

        assertEquals("Unsupported playlist sync outbox payload version", failure.message)
        assertEquals(listOf("begin", "end"), room.transactionLog)
    }

    private companion object {
        val songDeletion = LocalPlaylistSyncMutation(
            expectedPrimaryDigest = "a".repeat(64),
            addedSongDeletions = listOf(
                SyncPlaylistSongDeletion(
                    playlistId = 1,
                    songId = 11,
                    album = "Album",
                    deletedAt = 40,
                    deviceId = "phone",
                    removedMembershipTokens = listOf(SyncCausalToken("phone", 2))
                )
            ),
            removedSongDeletions = listOf(
                PlaylistSongDeletionRemoval(2, listOf(SongIdentity(12, "Album", "https://music.example/12")))
            ),
            deletedPlaylistIds = listOf(3)
        )
        val playlistRestore = LocalPlaylistSyncMutation(
            expectedPrimaryDigest = "b".repeat(64),
            clearedPlaylistDeletionIds = listOf(4),
            restoredPlaylistIds = listOf(3)
        )
        val delivered = SyncOutboxEntity(
            operationId = "done",
            expectedDomainRevision = 0,
            payloadVersion = 1,
            mutationPayloadJson = "{}",
            status = SyncOutboxStatus.DELIVERED,
            createdAt = 1
        )
    }
}

/** Outbox table with autoincrement sequences; migration metadata and checkpoints are out of scope. */
private class InMemorySyncOutboxDao : SyncMetadataDao {
    private val outbox = sortedMapOf<Long, SyncOutboxEntity>()
    private var lastSequence = 0L
    val pageCursors = mutableListOf<Long>()

    fun rows(): List<SyncOutboxEntity> = outbox.values.toList()

    override suspend fun insertOutbox(entry: SyncOutboxEntity): Long {
        lastSequence += 1
        outbox[lastSequence] = entry.copy(sequence = lastSequence)
        return lastSequence
    }

    override suspend fun getOutboxPage(statuses: List<String>, afterSequence: Long, limit: Int): List<SyncOutboxEntity> {
        pageCursors.add(afterSequence)
        return outbox.values.filter { it.status in statuses && it.sequence > afterSequence }.take(limit)
    }

    override suspend fun deleteOutboxByStatus(status: String) {
        outbox.values.removeAll { it.status == status }
    }

    override suspend fun upsertMigrationMetadata(metadata: MigrationMetadataEntity): Unit = unsupported()

    override suspend fun getMigrationMetadata(key: String): MigrationMetadataEntity? = unsupported()

    override suspend fun deleteMigrationMetadata(keys: List<String>): Unit = unsupported()

    override suspend fun getOutbox(statuses: List<String>, limit: Int): List<SyncOutboxEntity> = unsupported()

    override suspend fun updateOutbox(
        sequence: Long,
        status: String,
        attemptCount: Int,
        lastErrorType: String?,
        updatedAt: Long
    ): Unit = unsupported()

    override suspend fun upsertCheckpoint(checkpoint: SyncReplicaCheckpointEntity): Unit = unsupported()

    override suspend fun getCheckpoint(transportId: String): SyncReplicaCheckpointEntity? = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Outbox tests only exercise the pending mutation queue")
}
