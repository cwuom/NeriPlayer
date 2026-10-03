package moe.ouom.neriplayer.data.local.playlist

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalPlaylistRepositoryReorderTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `confirmed reorder persists moves to first and last positions`() = runTest {
        val fixture = createReorderFixture()
        val repository = fixture.repository
        val original = repository.playlists.value.single().songs.map { it.identity() }

        assertTrue(
            repository.reorderSongs(PLAYLIST_ID, listOf(original[2], original[0], original[1]), original)
        )
        assertEquals(listOf(3L, 1L, 2L), repository.playlists.value.single().songs.map { it.id })
        assertEquals(listOf(3L, 1L, 2L), restorePlaylist(fixture).songs.map { it.id })

        val current = repository.playlists.value.single().songs.map { it.identity() }
        assertTrue(
            repository.reorderSongs(PLAYLIST_ID, listOf(current[1], current[2], current[0]), current)
        )
        assertEquals(listOf(1L, 2L, 3L), repository.playlists.value.single().songs.map { it.id })

        val restored = restorePlaylist(fixture)
        assertEquals(listOf(1L, 2L, 3L), restored.songs.map { it.id })
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, restored.songOrderVersion)
        assertTrue(restored.songs.zipWithNext().all { (first, second) -> first.addedAt > second.addedAt })
    }

    @Test
    fun `confirmed reorder rejects stale membership and order without persistence`() = runTest {
        val fixture = createReorderFixture()
        val repository = fixture.repository
        val original = repository.playlists.value.single().songs.map { it.identity() }
        repository.reorderSongs(PLAYLIST_ID, original.reversed())
        val reordered = repository.playlists.value
        val commitsBeforeReject = fixture.storage.commitCount
        val versionBeforeReject = fixture.syncStore.mutationVersion

        assertFalse(repository.reorderSongs(PLAYLIST_ID, original, original))
        assertEquals(reordered, repository.playlists.value)
        assertEquals(commitsBeforeReject, fixture.storage.commitCount)
        assertEquals(versionBeforeReject, fixture.syncStore.mutationVersion)

        val beforeAdd = repository.playlists.value.single().songs.map { it.identity() }
        repository.addPreparedSongsToPlaylist(PLAYLIST_ID, listOf(localSong(4)))
        val afterAdd = repository.playlists.value
        val commitsAfterAdd = fixture.storage.commitCount
        val versionAfterAdd = fixture.syncStore.mutationVersion

        assertFalse(repository.reorderSongs(PLAYLIST_ID, beforeAdd.reversed(), beforeAdd))
        assertEquals(afterAdd, repository.playlists.value)
        assertEquals(commitsAfterAdd, fixture.storage.commitCount)
        assertEquals(versionAfterAdd, fixture.syncStore.mutationVersion)
    }

    @Test
    fun `confirmed reorder rejects duplicate missing foreign and extra identities`() = runTest {
        val fixture = createReorderFixture()
        val originalState = fixture.repository.playlists.value
        val expected = originalState.single().songs.map { it.identity() }
        val foreign = localSong(4).identity()
        val invalidOrders = listOf(
            listOf(expected[0], expected[0], expected[2]),
            listOf(expected[0], expected[1]),
            listOf(expected[0], expected[1], foreign),
            expected + foreign
        )
        val originalPrimary = fixture.storage.primary
        val commitsBeforeReject = fixture.storage.commitCount
        val versionBeforeReject = fixture.syncStore.mutationVersion

        invalidOrders.forEach { invalidOrder ->
            assertFalse(fixture.repository.reorderSongs(PLAYLIST_ID, invalidOrder, expected))
            assertEquals(originalState, fixture.repository.playlists.value)
            assertEquals(originalPrimary, fixture.storage.primary)
            assertEquals(commitsBeforeReject, fixture.storage.commitCount)
            assertEquals(versionBeforeReject, fixture.syncStore.mutationVersion)
        }
    }

    @Test
    fun `confirmed reorder rejects a removed playlist without persistence`() = runTest {
        val fixture = createReorderFixture()
        val expected = fixture.repository.playlists.value.single().songs.map { it.identity() }
        assertTrue(fixture.repository.deletePlaylist(PLAYLIST_ID))
        val commitsBeforeReject = fixture.storage.commitCount
        val versionBeforeReject = fixture.syncStore.mutationVersion

        assertFalse(fixture.repository.reorderSongs(PLAYLIST_ID, expected.reversed(), expected))
        assertTrue(fixture.repository.playlists.value.isEmpty())
        assertEquals(commitsBeforeReject, fixture.storage.commitCount)
        assertEquals(versionBeforeReject, fixture.syncStore.mutationVersion)
    }

    @Test
    fun `confirmed reorder validates snapshot after acquiring the commit lock`() = runTest {
        val fixture = createReorderFixture()
        val repository = fixture.repository
        val playlist = repository.playlists.value.single()
        val expected = playlist.songs.map { it.identity() }
        repository.playlistCommitMutex.lock()
        val (pending, commitsBeforeReject, versionBeforeReject) = try {
            val reorder = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                repository.reorderSongs(PLAYLIST_ID, expected.reversed(), expected)
            }
            assertFalse(reorder.isCompleted)
            repository.publishLocked(listOf(playlist.copy(songs = playlist.songs.reversed().toMutableList())))
            Triple(reorder, fixture.storage.commitCount, fixture.syncStore.mutationVersion)
        } finally {
            repository.playlistCommitMutex.unlock()
        }

        assertFalse(pending.await())
        assertEquals(listOf(3L, 2L, 1L), repository.playlists.value.single().songs.map { it.id })
        assertEquals(commitsBeforeReject, fixture.storage.commitCount)
        assertEquals(versionBeforeReject, fixture.syncStore.mutationVersion)
    }

    @Test
    fun `undo restores the original order only from its committed insertion snapshot`() = runTest {
        val fixture = createReorderFixture()
        val repository = fixture.repository
        val originalOrder = repository.playlists.value.single().songs.map { it.identity() }
        val insertedOrder = listOf(originalOrder[2], originalOrder[0], originalOrder[1])

        assertTrue(repository.reorderSongs(PLAYLIST_ID, insertedOrder, originalOrder))
        assertEquals(listOf(3L, 1L, 2L), restorePlaylist(fixture).songs.map { it.id })
        assertTrue(repository.reorderSongs(PLAYLIST_ID, originalOrder, insertedOrder))

        assertEquals(listOf(1L, 2L, 3L), repository.playlists.value.single().songs.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), restorePlaylist(fixture).songs.map { it.id })
    }

    @Test
    fun `undo cannot overwrite a subsequent playlist reorder`() = runTest {
        val fixture = createReorderFixture()
        val repository = fixture.repository
        val originalOrder = repository.playlists.value.single().songs.map { it.identity() }
        val insertedOrder = listOf(originalOrder[2], originalOrder[0], originalOrder[1])
        assertTrue(repository.reorderSongs(PLAYLIST_ID, insertedOrder, originalOrder))
        repository.reorderSongs(PLAYLIST_ID, listOf(originalOrder[0], originalOrder[2], originalOrder[1]))
        val currentState = repository.playlists.value
        val currentPrimary = fixture.storage.primary
        val commitsBeforeUndo = fixture.storage.commitCount
        val versionBeforeUndo = fixture.syncStore.mutationVersion

        assertFalse(repository.reorderSongs(PLAYLIST_ID, originalOrder, insertedOrder))

        assertEquals(currentState, repository.playlists.value)
        assertEquals(currentPrimary, fixture.storage.primary)
        assertEquals(commitsBeforeUndo, fixture.storage.commitCount)
        assertEquals(versionBeforeUndo, fixture.syncStore.mutationVersion)
    }

    private suspend fun createReorderFixture(): ReorderFixture {
        val storage = RecordingStorage(primary = null)
        val syncStore = RecordingSyncMutationStore()
        val repository = LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "playlist_reorder.json"),
            storage = storage,
            syncMutationStore = syncStore
        )
        repository.updatePlaylists(
            listOf(
                LocalPlaylist(
                    id = PLAYLIST_ID,
                    name = "排序测试",
                    songs = (1..3).map(::localSong).toMutableList(),
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            )
        )
        return ReorderFixture(repository, storage, syncStore)
    }

    private fun restorePlaylist(fixture: ReorderFixture): LocalPlaylist {
        return LocalPlaylistRepository.createForTest(
            context = mockContext(),
            file = File(tempFolder.root, "restored_reorder.json"),
            storage = fixture.storage
        ).playlists.value.single()
    }

    private data class ReorderFixture(
        val repository: LocalPlaylistRepository,
        val storage: RecordingStorage,
        val syncStore: RecordingSyncMutationStore
    )

    private companion object {
        const val PLAYLIST_ID = 147L
    }
}
