package moe.ouom.neriplayer.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRemoteSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncSessionTest {
    private val local = MemoryStore()
    private val backend = MemoryBackend()
    private val session = SyncSession(
        local = local,
        merger = SyncDataMerger(Messages, nowMs = { 500L }),
        noChangeMessage = "unchanged",
        initialUploadMessage = "initial",
        inProgressError = { IllegalStateException("busy") },
        nowMs = { 900L }
    )

    @Test
    fun `initial upload persists remote version and sync time`() = runTest {
        assertEquals("initial", session.execute { backend }.getOrThrow().message)
        assertEquals(listOf(7L), backend.data!!.playlists.map { it.id })
        assertEquals(2, backend.savedVersion)
        assertEquals(900L, backend.savedTime)
        assertTrue(local.remoteChanged)
        assertEquals(0, backend.followUps)
    }

    @Test
    fun `unchanged existing remote avoids uploading timestamp only differences`() = runTest {
        backend.data = local.data.copy(deviceId = "remote", lastModified = 1234L)
        backend.firstSync = false
        assertEquals("unchanged", session.execute { backend }.getOrThrow().message)
        assertEquals(1, backend.version)
        assertFalse(local.remoteChanged)
        assertEquals(900L, backend.savedTime)
    }

    @Test
    fun `changed remote is applied even when upload is unnecessary`() = runTest {
        backend.data = local.data.copy()
        backend.firstSync = false
        backend.changed = true
        assertEquals("merged", session.execute { backend }.getOrThrow().message)
        assertEquals(1, backend.version)
        assertTrue(local.remoteChanged)
    }

    @Test
    fun `legacy format is uploaded even if metadata is unchanged`() = runTest {
        backend.data = local.data.copy()
        backend.firstSync = false
        backend.migration = true
        session.execute { backend }.getOrThrow()
        assertEquals(2, backend.version)
        assertEquals(900L, backend.savedTime)
    }

    @Test
    fun `legacy playlist order migration requires an upload`() = runTest {
        backend.data = local.data.copy(playlists = listOf(SyncPlaylist(id = 7L, name = "local")))
        backend.firstSync = false
        session.execute { backend }.getOrThrow()
        assertEquals(2, backend.version)
        assertEquals(1, backend.data!!.playlists.single().songOrderVersion)
    }

    @Test
    fun `local edit during fetch prevents stale upload and schedules a follow up`() = runTest {
        backend.onFetch = { local.epoch++ }
        assertEquals("unchanged", session.execute { backend }.getOrThrow().message)
        assertEquals(1, backend.version)
        assertEquals(0, local.applied)
        assertEquals(1, backend.followUps)
        assertEquals(null, backend.savedVersion)
        assertEquals(null, backend.savedTime)
    }

    @Test
    fun `local edit during upload preserves current local state`() = runTest {
        backend.onUpload = { local.epoch++; local.data = SyncData(deviceId = "edited") }
        session.execute { backend }.getOrThrow()
        assertEquals("edited", local.data.deviceId)
        assertEquals(0, local.applied)
        assertEquals(2, backend.savedVersion)
        assertEquals(null, backend.savedTime)
        assertEquals(1, backend.followUps)
    }

    @Test
    fun `rejected local apply keeps sync time pending`() = runTest {
        local.acceptApply = false
        session.execute { backend }.getOrThrow()
        assertEquals(1, local.applied)
        assertEquals(2, backend.savedVersion)
        assertEquals(null, backend.savedTime)
        assertEquals(1, backend.followUps)
    }

    @Test
    fun `local edit after apply schedules another sync`() = runTest {
        local.onApply = { local.epoch++ }
        session.execute { backend }.getOrThrow()
        assertEquals(null, backend.savedTime)
        assertEquals(1, backend.followUps)
    }

    @Test
    fun `conflict refetch merges latest remote before retrying`() = runTest {
        backend.onUpload = {
            backend.onUpload = {}
            backend.data = SyncData(playlists = listOf(SyncPlaylist(id = 8L, name = "remote")))
            backend.version = 3
            throw Conflict()
        }
        assertEquals("merged", session.execute { backend }.getOrThrow().message)
        assertEquals(setOf(7L, 8L), backend.data!!.playlists.map { it.id }.toSet())
        assertEquals(4, backend.savedVersion)
        assertTrue(local.remoteChanged)
    }

    @Test
    fun `download error is reported without local writes`() = runTest {
        val error = IOException("invalid remote")
        backend.fetchError = error
        assertSame(error, session.execute { backend }.exceptionOrNull())
        assertSame(error, backend.lastError)
        assertEquals(0, local.applied)
        assertEquals(null, backend.savedVersion)
    }

    @Test
    fun `upload error is reported without local writes`() = runTest {
        val error = IOException("offline")
        backend.onUpload = { throw error }
        assertSame(error, session.execute { backend }.exceptionOrNull())
        assertSame(error, backend.lastError)
        assertEquals(0, local.applied)
    }

    @Test
    fun `uninitialized local data never opens backend`() = runTest {
        local.initialized = false
        var opened = false
        assertTrue(session.execute { opened = true; backend }.isFailure)
        assertFalse(opened)
    }

    @Test
    fun `busy coordinator prevents overlapping backend work`() = runTest {
        assertTrue(SyncCoordinator.tryLock())
        try {
            assertEquals("busy", session.execute { backend }.exceptionOrNull()?.message)
            assertEquals(null, backend.data)
        } finally {
            SyncCoordinator.unlock()
        }
    }

    @Test
    fun `backend configuration failure releases coordinator`() = runTest {
        val error = IllegalStateException("missing config")
        assertSame(error, session.execute<Int> { throw error }.exceptionOrNull())
        assertTrue(session.execute { backend }.isSuccess)
    }

    @Test
    fun `cancellation propagates and releases coordinator`() = runTest {
        backend.onFetch = { throw CancellationException("cancelled") }
        try {
            session.execute { backend }
            throw AssertionError("cancellation was swallowed")
        } catch (error: CancellationException) {
            assertEquals("cancelled", error.message)
        }
        assertEquals(0, local.applied)
        assertEquals(null, backend.lastError)
        backend.onFetch = {}
        assertTrue(session.execute { backend }.isSuccess)
    }

    private class MemoryStore : SyncLocalDataStore {
        var initialized = true
        var epoch = 10L
        var data = SyncData(playlists = listOf(SyncPlaylist(id = 7L, name = "local", songOrderVersion = 1)))
        var remoteChanged = false
        var applied = 0
        var acceptApply = true
        var onApply: () -> Unit = {}
        override suspend fun awaitInitialized() = initialized
        override fun mutationVersion() = epoch
        override fun snapshot() = data
        override suspend fun apply(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
            assertEquals(10L, expectedMutationVersion)
            applied++
            this.remoteChanged = remoteChanged
            onApply()
            if (acceptApply) this.data = data
            return acceptApply
        }
    }

    private class Conflict : IOException()

    private class MemoryBackend : SyncBackend<Int> {
        var data: SyncData? = null
        var version = 1
        var firstSync = true
        var changed = false
        var migration = false
        var savedVersion: Int? = null
        var savedTime: Long? = null
        var followUps = 0
        var lastError: Throwable? = null
        var fetchError: Throwable? = null
        var onFetch: () -> Unit = {}
        var onUpload: () -> Unit = {}
        override val isFirstSync get() = firstSync
        override val lastSyncTime = 0L
        override val mutationConflictMessage = "local edited"
        override suspend fun fetch(): Result<SyncRemoteSnapshot<Int>> {
            onFetch()
            fetchError?.let { return Result.failure(it) }
            return Result.success(SyncRemoteSnapshot(data, version, migration))
        }
        override suspend fun refetch(version: Int) = Result.success(SyncRemoteSnapshot(data, this.version))
        override suspend fun upload(data: SyncData, version: Int): Result<Int> {
            return try {
                onUpload()
                this.data = data
                this.version++
                Result.success(this.version)
            } catch (error: IOException) {
                Result.failure(error)
            }
        }
        override fun remoteChanged(version: Int) = changed
        override fun isConflict(error: Throwable?) = error is Conflict
        override fun saveRemoteVersion(version: Int) { savedVersion = version }
        override fun saveSyncTime(timestamp: Long) { savedTime = timestamp }
        override fun scheduleFollowUp() { followUps++ }
        override fun onFailure(error: Throwable) { lastError = error }
    }

    private object Messages : SyncMergeHost {
        override val favoritesPlaylistId = -1001L
        override val mergeSuccessMessage = "merged"
        override val initialUploadMessage = "initial"
        override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? = null
        override fun localRenameMessage(name: String) = name
        override fun remoteRenameMessage(name: String) = name
    }
}
