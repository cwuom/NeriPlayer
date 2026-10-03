package moe.ouom.neriplayer.data.sync.store.state

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.runtime.dataset.readForTest
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.engine.TestSyncMergeHost
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.runtime.SyncSession
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncDeletionCommitSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `failed deletion barrier leaves acknowledgement pending and same remote retries durably`() = runTest {
        val prefs = MemorySyncPreferences()
        val directory = temporary.newFolder()
        val storage = SecureTokenStorage(prefs.preferences, directory)
        val remote = SyncData(
            lyricOverrides = listOf(SyncSong(id = 7, album = "netease", lyricSyncEdited = false, lyricSyncRevision = 21)),
            playlists = listOf(SyncPlaylist(id = 8, name = "deleted", createdAt = 1, modifiedAt = 20, isDeleted = true)),
            recentPlayDeletions = listOf(SyncRecentPlayDeletion(songId = 7, album = "netease", deletedAt = 30)),
            playlistSongDeletions = listOf(SyncPlaylistSongDeletion(playlistId = 9, songId = 7, album = "netease", deletedAt = 30))
        )
        val datasets = FileSyncPlaybackDatasetStore(temporary.newFolder())
        val local = DurableStore(storage, prefs, datasets)
        val backend = PendingBackend(remote, datasets)
        val session = SyncSession(
            local, SyncDataMerger(TestSyncMergeHost()) { 50L }, datasets,
            "unchanged", "initial", { IllegalStateException("busy") }, { 900L }, deferredMessage = "pending"
        )

        assertTrue(session.execute { backend }.isFailure)
        assertNull(backend.savedVersion)
        assertNull(backend.savedTime)
        assertEquals(remote.recentPlayDeletions, storage.getRecentPlayDeletions())
        val restartedBeforeRetry = SecureTokenStorage(prefs.restart().preferences, directory)
        assertTrue(restartedBeforeRetry.getRecentPlayDeletions().isEmpty())
        assertTrue(restartedBeforeRetry.getPlaylistSongDeletions().isEmpty())
        assertEquals(21L, restartedBeforeRetry.getLyricOverrides().single().lyricSyncRevision)
        assertEquals(mapOf(8L to 20L), restartedBeforeRetry.getDeletedPlaylistTimestamps())

        session.execute { backend }.getOrThrow()

        assertEquals(listOf(true, true), local.remoteChanges)
        assertEquals(2, backend.savedVersion)
        assertEquals(900L, backend.savedTime)
        val restarted = SecureTokenStorage(prefs.restart().preferences, directory)
        assertEquals(remote.recentPlayDeletions, restarted.getRecentPlayDeletions())
        assertEquals(remote.playlistSongDeletions, restarted.getPlaylistSongDeletions())
        assertEquals(21L, restarted.getLyricOverrides().single().lyricSyncRevision)
        assertEquals(mapOf(8L to 20L), restarted.getDeletedPlaylistTimestamps())
        assertEquals(0L, restarted.getSyncMutationVersion())
    }

    private class DurableStore(
        private val storage: SecureTokenStorage,
        private val prefs: MemorySyncPreferences,
        private val datasets: SyncPlaybackDatasetStore
    ) : SyncLocalDataStore {
        private var failFirstBarrier = true
        val remoteChanges = mutableListOf<Boolean>()

        override suspend fun awaitInitialized() = true
        override fun mutationVersion() = storage.getSyncMutationVersion()
        override suspend fun snapshot() = datasets.fromLegacy(SyncData(
            lyricOverrides = storage.getLyricOverrides(),
            recentPlayDeletions = storage.getRecentPlayDeletions(),
            playlistSongDeletions = storage.getPlaylistSongDeletions(),
            playlists = storage.getDeletedPlaylistTimestamps().map { (id, timestamp) ->
                SyncPlaylist(id = id, name = "deleted", createdAt = 1, modifiedAt = timestamp, isDeleted = true)
            }
        ))

        override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
            val data = dataset.readForTest()
            remoteChanges += remoteChanged
            if (!storage.setLyricOverridesIfMutationVersion(expectedMutationVersion, data.lyricOverrides)) return false
            if (!storage.setPlaylistDeletionStateIfMutationVersion(expectedMutationVersion, data.playlists, false)) return false
            if (failFirstBarrier) {
                failFirstBarrier = false
                prefs.failNextCommit = true
            }
            return storage.setDeletionStateIfMutationVersion(
                expectedMutationVersion, data.recentPlayDeletions, data.playlistSongDeletions
            )
        }
    }

    private class PendingBackend(private var data: SyncData, private val datasets: SyncPlaybackDatasetStore) : SyncBackend<Int> {
        var savedVersion: Int? = null
        var savedTime: Long? = null
        override val isFirstSync = false
        override val lastSyncTime = 100L
        override val mutationConflictMessage = "mutation conflict"
        override suspend fun fetch() = Result.success(SyncDatasetRemoteSnapshot(datasets.fromLegacy(data), 2))
        override suspend fun refetch(version: Int) = fetch()
        override suspend fun upload(data: SyncDataset, version: Int): Result<Int> {
            this.data = data.readForTest()
            return Result.success(version)
        }
        override fun remoteChanged(version: Int) = savedVersion != version
        override fun isConflict(error: Throwable?) = false
        override fun saveRemoteVersion(version: Int) { savedVersion = version }
        override fun saveSyncTime(timestamp: Long) { savedTime = timestamp }
        override fun saveCompletedSyncTime(timestamp: Long) = true
        override fun scheduleFollowUp() = Unit
        override fun onFailure(error: Throwable) = Unit
    }
}
