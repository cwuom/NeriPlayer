package moe.ouom.neriplayer.data.sync.store.state

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncDeletionRetryStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `recent play removal retries pending memory state durably`() = verifyRemovalRetry(
        seed = { it.addRecentPlayDeletions(listOf(SyncRecentPlayDeletion(songId = 7, album = "netease", deletedAt = 20))) },
        remove = { it.removeRecentPlayDeletion(SongIdentity(7, "netease", null)) },
        state = { it.getRecentPlayDeletions() }
    )

    @Test
    fun `usage removal retries pending memory state durably`() = verifyRemovalRetry(
        seed = { it.addPlaylistUsageDeletion("local:7", 20) },
        remove = { it.removePlaylistUsageDeletion("local:7") },
        state = { it.getPlaylistUsageDeletions() }
    )

    @Test
    fun `playlist clear retries both pending sections durably`() = verifyRemovalRetry(
        seed = { it.addDeletedPlaylistId(7) },
        remove = { it.clearDeletedPlaylistIds() },
        state = { it.getDeletedPlaylistIds() to it.getDeletedPlaylistTimestamps() }
    )

    @Test
    fun `playlist removal retries both pending sections durably`() = verifyRemovalRetry(
        seed = { it.addDeletedPlaylistId(7); it.addDeletedPlaylistId(8) },
        remove = { it.removeDeletedPlaylistIds(setOf(7)) },
        state = { it.getDeletedPlaylistIds() to it.getDeletedPlaylistTimestamps() }
    )

    @Test
    fun `legacy song removal retries while preserving observed remove tombstones`() = verifyRemovalRetry(
        seed = { it.addPlaylistSongDeletions(listOf(
            SyncPlaylistSongDeletion(playlistId = 8, songId = 7, album = "netease", deletedAt = 20),
            SyncPlaylistSongDeletion(
                playlistId = 8, songId = 9, album = "netease", deletedAt = 20,
                removedMembershipTokens = listOf(SyncCausalToken("device", 1))
            )
        )) },
        remove = { it.removePlaylistSongDeletions(8, listOf(SongIdentity(7, "netease", null), SongIdentity(9, "netease", null))) },
        state = { it.getPlaylistSongDeletions() }
    )

    @Test
    fun `all songs removal retries pending memory state durably`() = verifyRemovalRetry(
        seed = { it.addPlaylistSongDeletions(listOf(
            SyncPlaylistSongDeletion(playlistId = 8, songId = 7, album = "netease", deletedAt = 20),
            SyncPlaylistSongDeletion(playlistId = 9, songId = 9, album = "netease", deletedAt = 20)
        )) },
        remove = { it.removePlaylistSongDeletionsForPlaylist(8) },
        state = { it.getPlaylistSongDeletions() }
    )

    private fun verifyRemovalRetry(
        seed: (SecureTokenStorage) -> Unit,
        remove: (SecureTokenStorage) -> Unit,
        state: (SecureTokenStorage) -> Any
    ) {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        seed(store)
        val before = state(store)
        val versionBefore = store.getSyncMutationVersion()
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { remove(store) }
        val pending = state(store)
        val pendingFiles = directory.listFiles().orEmpty().map { it.name }.toSet()
        assertNotEquals(before, pending)
        assertEquals(versionBefore + 1, store.getSyncMutationVersion())
        val restartedBeforeRetry = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(before, state(restartedBeforeRetry))
        assertEquals(versionBefore, restartedBeforeRetry.getSyncMutationVersion())

        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { remove(store) }
        assertEquals(pending, state(store))
        assertEquals(versionBefore + 1, store.getSyncMutationVersion())
        assertEquals(pendingFiles, directory.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals(before, state(SecureTokenStorage(prefs.restart().preferences, directory)))

        remove(store)
        assertEquals(emptySet<String>(), prefs.commits.last())
        assertTrue(pendingFiles.containsAll(directory.listFiles().orEmpty().map { it.name }))
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(pending, state(restarted))
        assertEquals(versionBefore + 1, restarted.getSyncMutationVersion())
    }
}
