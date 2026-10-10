package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPlaylistMembershipStampTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `inserted local songs go newest source first and get consecutive membership stamps`() {
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(syncStore)
        val older = localSong(1).copy(sourceModifiedAtMs = 1_700_000_000_000L)
        val newer = localSong(2).copy(sourceModifiedAtMs = 1_700_000_500_000L)
        val firstCounter = syncStore.nextCounter

        val stamped = repository.stampSongsForPlaylistInsert(listOf(older, newer), addedAt = 5_000L)

        assertEquals(listOf(2L, 1L), stamped.map { it.id })
        assertEquals(listOf(5_000L, 4_999L), stamped.map { it.addedAt })
        assertEquals(listOf<Long?>(5_000L, 4_999L), stamped.map { it.membershipAddedAtMs })
        assertEquals(tokens(firstCounter, count = 2), stamped.map { it.syncMembershipTokens })
    }

    @Test
    fun `remote songs keep their order and may keep their scanned creation time`() {
        val repository = repository(RecordingSyncMutationStore())
        val created = remoteNeteaseSong(id = 11L).copy(logicalCreatedAtMs = 1_234L)
        val undated = remoteNeteaseSong(id = 12L)

        val preserved = repository.stampSongsForPlaylistInsert(
            listOf(created, undated),
            addedAt = 9_000L,
            preserveScannedSourceAddedAt = true
        )
        val stamped = repository.stampSongsForPlaylistInsert(listOf(created), addedAt = 9_000L)

        assertEquals(listOf(11L, 12L), preserved.map { it.id })
        assertEquals(listOf(1_234L, 8_999L), preserved.map { it.addedAt })
        assertEquals(listOf<Long?>(9_000L, 8_999L), preserved.map { it.membershipAddedAtMs })
        assertEquals(9_000L, stamped.single().addedAt)
    }

    @Test
    fun `restored songs only swap in fresh membership tokens`() {
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(syncStore)
        val stale = listOf(SyncCausalToken(deviceId = "old-device", counter = 9L))
        val songs = listOf(localSong(1), localSong(2)).map { it.copy(syncMembershipTokens = stale) }
        val firstCounter = syncStore.nextCounter

        val renewed = repository.renewSongsForPlaylistRestore(songs)

        assertEquals(tokens(firstCounter, count = 2), renewed.map { it.syncMembershipTokens })
        assertEquals(songs.map { it.copy(syncMembershipTokens = null) }, renewed.map { it.copy(syncMembershipTokens = null) })
    }

    @Test
    fun `empty batches allocate no membership tokens`() {
        val syncStore = RecordingSyncMutationStore()
        val repository = repository(syncStore)
        val allocatedBefore = syncStore.allocatedTokenCount

        assertEquals(emptyList<SongItem>(), repository.stampSongsForPlaylistInsert(emptyList(), addedAt = 1L))
        assertEquals(emptyList<SongItem>(), repository.renewSongsForPlaylistRestore(emptyList()))
        assertEquals(allocatedBefore, syncStore.allocatedTokenCount)
    }

    @Test
    fun `a short membership token allocation is rejected`() {
        val repository = repository(object : LocalPlaylistSyncMutationStore by RecordingSyncMutationStore() {
            override fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> = emptyList()
        })
        val songs = listOf(localSong(1), localSong(2))

        val insertFailure = assertThrows(IllegalStateException::class.java) {
            repository.stampSongsForPlaylistInsert(songs, addedAt = 1L)
        }
        val restoreFailure = assertThrows(IllegalStateException::class.java) {
            repository.renewSongsForPlaylistRestore(songs)
        }

        assertEquals("Expected 2 sync membership tokens, got 0", insertFailure.message)
        assertEquals("Expected 2 sync membership tokens, got 0", restoreFailure.message)
    }

    private fun repository(syncStore: LocalPlaylistSyncMutationStore) = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "membership_stamps.json"),
        storage = RecordingStorage(primary = null),
        syncMutationStore = syncStore
    )

    private fun tokens(firstCounter: Long, count: Int) = List(count) { index ->
        listOf(SyncCausalToken(deviceId = "test-device", counter = firstCounter + index))
    }
}
