package moe.ouom.neriplayer.data.sync.store.state

import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.engine.TestSyncMergeHost
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncPlaylistDeletionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `remote deletion remains in a reopened snapshot when switching to an older provider`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        val merger = SyncDataMerger(TestSyncMergeHost()) { 50L }
        val oldProvider = SyncData(playlists = listOf(playlist(7, 10)))
        val deletedProvider = SyncData(playlists = listOf(playlist(7, 20, deleted = true)))
        val first = merger.merge(oldProvider, deletedProvider, 1).mergedData

        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, first.playlists))
        val reopened = SecureTokenStorage(prefs.preferences, directory)
        val second = merger.merge(deletedSnapshot(reopened), oldProvider, 1).mergedData

        assertTrue(second.playlists.single().isDeleted)
        assertEquals(20L, second.playlists.single().modifiedAt)
        assertTrue(reopened.setPlaylistDeletionStateIfMutationVersion(0, second.playlists))
        assertEquals(second.playlists, merger.merge(deletedSnapshot(reopened), oldProvider, 1).mergedData.playlists)
        assertEquals(0L, reopened.getSyncMutationVersion())

        val restoredProvider = SyncData(playlists = listOf(playlist(7, 21)))
        val restored = merger.merge(deletedSnapshot(reopened), restoredProvider, 1).mergedData
        assertFalse(restored.playlists.single().isDeleted)
        assertTrue(reopened.setPlaylistDeletionStateIfMutationVersion(0, restored.playlists))
        assertTrue(SecureTokenStorage(prefs.preferences, directory).getDeletedPlaylistIds().isEmpty())
        assertFalse(merger.merge(restored, deletedProvider, 1).mergedData.playlists.single().isDeleted)
    }

    @Test
    fun `guarded tombstones retain maxima omissions and ties but allow later active recovery`() {
        val prefs = MemorySyncPreferences()
        val store = SecureTokenStorage(prefs.preferences, temporary.newFolder())
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 20, true), playlist(8, 30, true))))
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 10, true), playlist(8, 30))))
        assertEquals(mapOf(7L to 20L, 8L to 30L), store.getDeletedPlaylistTimestamps())
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 21))))
        assertEquals(mapOf(8L to 30L), store.getDeletedPlaylistTimestamps())
        val version = store.markSyncMutation()
        val commits = prefs.commits.size
        assertFalse(store.setPlaylistDeletionStateIfMutationVersion(version - 1, listOf(playlist(8, 31))))
        assertEquals(commits, prefs.commits.size)
        assertEquals(mapOf(8L to 30L), store.getDeletedPlaylistTimestamps())
        assertEquals(version, store.getSyncMutationVersion())
    }

    @Test
    fun `remote timestamps are preserved exactly instead of replaced by the local clock`() {
        val prefs = MemorySyncPreferences()
        val store = SecureTokenStorage(prefs.preferences, temporary.newFolder())
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 0, true))))
        assertEquals(mapOf(7L to 0L), store.getDeletedPlaylistTimestamps())
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 0))))
        assertEquals(mapOf(7L to 0L), store.getDeletedPlaylistTimestamps())
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 1))))
        assertTrue(store.getDeletedPlaylistIds().isEmpty())
    }

    @Test
    fun `failed container recovery retains tombstone across provider switch and successful recovery permits a new deletion`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        val merger = SyncDataMerger(TestSyncMergeHost()) { 50L }
        val deleted = playlist(7, 20, true)
        val restored = playlist(7, 30)
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(deleted), false))
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(restored), false))

        val reopenedAfterContainerFailure = SecureTokenStorage(prefs.preferences, directory)
        val oldProvider = SyncData(playlists = listOf(playlist(7, 10)))
        val retried = merger.merge(deletedSnapshot(reopenedAfterContainerFailure), oldProvider, 1).mergedData
        assertTrue(retried.playlists.single().isDeleted)
        assertEquals(20L, retried.playlists.single().modifiedAt)

        assertTrue(reopenedAfterContainerFailure.setPlaylistDeletionStateIfMutationVersion(0, listOf(restored), true))
        assertTrue(SecureTokenStorage(prefs.preferences, directory).getDeletedPlaylistIds().isEmpty())
        val activeSnapshot = SyncData(playlists = listOf(restored))
        assertFalse(merger.merge(activeSnapshot, oldProvider, 1).mergedData.playlists.single().isDeleted)

        reopenedAfterContainerFailure.addDeletedPlaylistId(7)
        val nextDeletion = SecureTokenStorage(prefs.preferences, directory).getDeletedPlaylistTimestamps().getValue(7)
        assertTrue(nextDeletion > restored.modifiedAt)
        assertTrue(merger.merge(deletedSnapshot(reopenedAfterContainerFailure), activeSnapshot, 1).mergedData.playlists.single().isDeleted)
        assertEquals(1L, store.getSyncMutationVersion())
    }

    @Test
    fun `failed guarded marker commit cannot publish either playlist deletion section`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 20, true))))
        val ids = prefs.values[KEY_DELETED_PLAYLIST_IDS]
        val timestamps = prefs.values[KEY_DELETED_PLAYLIST_TIMESTAMPS]
        prefs.failNextCommit = true

        assertThrows(IllegalStateException::class.java) {
            store.setPlaylistDeletionStateIfMutationVersion(0, listOf(playlist(7, 21), playlist(8, 30, true)))
        }

        assertNotEquals(ids, prefs.values[KEY_DELETED_PLAYLIST_IDS])
        assertNotEquals(timestamps, prefs.values[KEY_DELETED_PLAYLIST_TIMESTAMPS])
        assertEquals(ids, prefs.durableValues[KEY_DELETED_PLAYLIST_IDS])
        assertEquals(timestamps, prefs.durableValues[KEY_DELETED_PLAYLIST_TIMESTAMPS])
        assertEquals(mapOf(8L to 30L), store.getDeletedPlaylistTimestamps())
        assertEquals(mapOf(7L to 20L), SecureTokenStorage(prefs.restart().preferences, directory).getDeletedPlaylistTimestamps())
        assertEquals(0L, store.getSyncMutationVersion())
    }

    @Test
    fun `failed second playlist section reclaims the first unpublished section on every retry`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        var attempts = 0
        val store = SecureTokenStorage(prefs.preferences, directory) {
            attempts++
            if (attempts % 2 == 0) throw IOException("timestamp generation directory sync failed")
        }
        repeat(20) {
            assertThrows(IllegalStateException::class.java) { store.addDeletedPlaylistId(7) }
            assertTrue(prefs.values.isEmpty())
            assertTrue(prefs.durableValues.isEmpty())
        }

        assertEquals(40, attempts)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.getDeletedPlaylistIds().isEmpty())
        assertTrue(store.getDeletedPlaylistTimestamps().isEmpty())
        assertEquals(0L, store.getSyncMutationVersion())
    }

    @Test
    fun `failed playlist mutation preparation cannot orphan earlier song tombstone generations`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val songDeletion = SyncPlaylistSongDeletion(playlistId = 7, songId = 9, album = "netease", deletedAt = 20)
        var attempts = 0
        val store = SecureTokenStorage(prefs.preferences, directory) {
            attempts++
            if (attempts % 2 == 0) throw IOException("playlist ids directory sync failed")
        }
        repeat(20) {
            assertThrows(IllegalStateException::class.java) {
                store.applyPlaylistSyncMutation(listOf(songDeletion), emptyList(), listOf(7), emptyList(), emptySet())
            }
            assertTrue(prefs.values.isEmpty())
            assertTrue(prefs.durableValues.isEmpty())
        }

        assertEquals(40, attempts)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(store.getPlaylistSongDeletions().isEmpty())
        assertTrue(store.getDeletedPlaylistIds().isEmpty())
        assertEquals(0L, store.getSyncMutationVersion())
    }

    @Test
    fun `same guarded playlist retry confirms both pending sections without rewriting generations`() {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        val deletions = listOf(playlist(7, 20, true), playlist(8, 30, true))
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.setPlaylistDeletionStateIfMutationVersion(0, deletions) }
        val pendingIds = prefs.values[KEY_DELETED_PLAYLIST_IDS]
        val pendingTimestamps = prefs.values[KEY_DELETED_PLAYLIST_TIMESTAMPS]
        val pendingFiles = directory.listFiles().orEmpty().map { it.name }.toSet()
        assertTrue(SecureTokenStorage(prefs.restart().preferences, directory).getDeletedPlaylistIds().isEmpty())

        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.setPlaylistDeletionStateIfMutationVersion(0, deletions) }
        assertTrue(SecureTokenStorage(prefs.restart().preferences, directory).getDeletedPlaylistIds().isEmpty())
        assertEquals(pendingFiles, directory.listFiles().orEmpty().map { it.name }.toSet())

        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, deletions))
        assertEquals(emptySet<String>(), prefs.commits.last())
        assertEquals(pendingIds, prefs.durableValues[KEY_DELETED_PLAYLIST_IDS])
        assertEquals(pendingTimestamps, prefs.durableValues[KEY_DELETED_PLAYLIST_TIMESTAMPS])
        assertEquals(pendingFiles, directory.listFiles().orEmpty().map { it.name }.toSet())
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(mapOf(7L to 20L, 8L to 30L), restarted.getDeletedPlaylistTimestamps())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    @Test
    fun `playlist ids use checked generations including legal CSV backup and mutation writer`() {
        val prefs = MemorySyncPreferences(mapOf(KEY_DELETED_PLAYLIST_IDS to "7,8,7", KEY_DELETED_PLAYLIST_TIMESTAMPS to "{\"7\":20,\"8\":30,\"99\":40}"))
        val directory = temporary.newFolder()
        val store = SecureTokenStorage(prefs.preferences, directory)
        assertEquals(mapOf(7L to 20L, 8L to 30L), store.getDeletedPlaylistTimestamps())
        assertTrue(store.setPlaylistDeletionStateIfMutationVersion(0, (9L..5_001L).map { playlist(it, 50, true) }))
        val marker = prefs.values[KEY_DELETED_PLAYLIST_IDS] as String
        val backup = prefs.values["${KEY_DELETED_PLAYLIST_IDS}_previous_generation"] as String
        assertTrue(marker.startsWith("@file-v1:"))
        assertEquals("[7,8]", File(directory, backup.split(':')[1]).readText())
        assertEquals(4_995, SecureTokenStorage(prefs.preferences, directory).getDeletedPlaylistIds().size)
        store.applyPlaylistSyncMutation(emptyList(), emptyList(), listOf(6_000), emptyList(), setOf(7))
        val latest = prefs.values[KEY_DELETED_PLAYLIST_IDS] as String
        assertTrue(latest.startsWith("@file-v1:"))
        assertTrue(File(directory, marker.split(':')[1]).isFile)
        assertTrue(File(directory, latest.split(':')[1]).isFile)
        assertTrue(6_000L in SecureTokenStorage(prefs.preferences, directory).getDeletedPlaylistIds())
        assertEquals(1L, store.getSyncMutationVersion())
    }

    @Test
    fun `missing or corrupted playlist ID generations reject reads and writes`() {
        for (remove in listOf(false, true)) {
            val prefs = MemorySyncPreferences()
            val directory = temporary.newFolder()
            val store = SecureTokenStorage(prefs.preferences, directory)
            store.addDeletedPlaylistId(7)
            val marker = prefs.values[KEY_DELETED_PLAYLIST_IDS] as String
            val file = File(directory, marker.split(':')[1])
            if (remove) assertTrue(file.delete()) else file.writeText("[]")
            assertThrows(IllegalStateException::class.java) { store.getDeletedPlaylistIds() }
            assertThrows(IllegalStateException::class.java) { store.setPlaylistDeletionStateIfMutationVersion(1, listOf(playlist(7, 40))) }
            assertEquals(marker, prefs.values[KEY_DELETED_PLAYLIST_IDS])
        }
    }

    private fun deletedSnapshot(store: SecureTokenStorage): SyncData = SyncData(
        playlists = store.getDeletedPlaylistTimestamps().map { (id, timestamp) ->
            playlist(id, timestamp, true).copy(name = "", createdAt = 0)
        }
    )

    private fun playlist(id: Long, timestamp: Long, deleted: Boolean = false) = SyncPlaylist(
        id = id, name = "playlist $id", songs = emptyList(), createdAt = 1,
        modifiedAt = timestamp, isDeleted = deleted
    )
}
