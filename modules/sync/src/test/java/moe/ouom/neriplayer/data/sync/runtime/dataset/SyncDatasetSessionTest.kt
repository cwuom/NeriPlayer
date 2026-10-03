package moe.ouom.neriplayer.data.sync.runtime.dataset

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.*
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.engine.TestSyncMergeHost
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.runtime.SyncSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SyncDatasetSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun casRefetchStagesNewRemoteReusesFrozenLocalAndClosesAllThreeGenerations() = runBlocking {
        val fixture = Fixture()
        fixture.backend.seed(data("remote", 2))
        fixture.backend.beforeFirstUpload = { fixture.backend.seed(data("remote", 2, 3)); throw Conflict() }

        fixture.session.execute { fixture.backend }.getOrThrow()

        assertEquals(listOf(1L, 2L, 3L), fixture.local.data.playbackStats.map { it.id })
        assertEquals(1, fixture.local.captures)
        assertEquals(2, fixture.backend.uploads)
        assertEquals(2, fixture.backend.fetches)
        assertTrue(fixture.local.remoteChanged)
        assertNotNull(fixture.backend.savedVersion)
        assertTrue(fixture.staging.listFiles().orEmpty().isEmpty())

        fixture.session.execute { fixture.backend }.getOrThrow()

        assertTrue(fixture.local.matchedCapture)
        assertEquals(2, fixture.backend.uploads)
        assertTrue(fixture.staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationAfterMergeClosesEveryDatasetAndReleasesTheCoordinator() = runBlocking {
        val fixture = Fixture()
        fixture.backend.seed(data("remote", 2))
        val cancellation = CancellationException("upload canceled")
        fixture.backend.beforeFirstUpload = { throw cancellation }

        assertSame(cancellation, runCatching { fixture.session.execute { fixture.backend } }.exceptionOrNull())
        assertEquals(0, fixture.local.applies)
        assertNull(fixture.backend.savedVersion)
        assertTrue(fixture.staging.listFiles().orEmpty().isEmpty())
        fixture.backend.beforeFirstUpload = {}
        fixture.session.execute { fixture.backend }.getOrThrow()
        assertEquals(listOf(1L, 2L), fixture.local.data.playbackStats.map { it.id })
    }

    @Test fun failedLocalRevisionCheckDoesNotAcknowledgeThePublishedRemote() = runBlocking {
        val fixture = Fixture()
        fixture.backend.seed(data("remote", 2))
        fixture.local.accept = false
        fixture.session.execute { fixture.backend }.getOrThrow()
        assertNull(fixture.backend.savedVersion)
        assertNull(fixture.backend.savedTime)
        assertEquals(100L, fixture.backend.completedTime)
        assertEquals(1, fixture.backend.followUps)
        assertEquals(listOf(1L), fixture.local.data.playbackStats.map { it.id })
        assertTrue(fixture.staging.listFiles().orEmpty().isEmpty())

        fixture.local.accept = true
        fixture.session.execute { fixture.backend }.getOrThrow()
        assertEquals(listOf(1L, 2L), fixture.local.data.playbackStats.map { it.id })
        assertNotNull(fixture.backend.savedVersion)
    }

    private fun data(device: String, vararg ids: Int) = SyncData(deviceId = device,
        playbackStats = ids.reversed().map { SyncTrackStat(identityKey = "track-$it", id = it.toLong(), totalListenMs = 10L, playCount = 1, firstPlayedAt = 1, lastPlayedAt = 2) },
        playbackStatBuckets = ids.map { SyncPlaybackStatBucket(identityKey = "track-$it", dayStartAt = 1, id = it.toLong(), totalListenMs = 10L, playCount = 1, firstPlayedAt = 1, lastPlayedAt = 2) })

    private inner class Fixture {
        val staging: File = temporary.newFolder()
        val datasets = FileSyncPlaybackDatasetStore(staging)
        val local = Local(data("local", 1), datasets)
        val backend = ArchiveBackend(SyncArchiveRepository(temporary.newFolder()), datasets)
        val session = SyncSession(local, SyncDataMerger(TestSyncMergeHost()) { 10L }, datasets,
            "unchanged", "initial", { IllegalStateException("busy") }, { 100L }, deferredMessage = "pending")
    }

    private class Local(var data: SyncData, private val datasets: SyncPlaybackDatasetStore) : SyncLocalDataStore {
        var captures = 0
        var applies = 0
        var accept = true
        var remoteChanged = false
        var matchedCapture = false
        override suspend fun awaitInitialized() = true
        override fun mutationVersion() = 1L
        override suspend fun snapshot(): SyncDataset {
            captures++
            val imported = datasets.fromLegacy(data)
            return SyncDataset(imported.data, imported.playback, 41L)
        }
        override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
            assertEquals(1L, expectedMutationVersion)
            assertEquals(41L, dataset.capturedPlaybackRevision)
            this.remoteChanged = remoteChanged
            matchedCapture = dataset.playbackMatchesCaptured
            applies++
            if (accept) data = dataset.readForTest()
            return accept
        }
    }

    private class Conflict : IOException("compare and swap failed")
    private class ArchiveBackend(private val archive: SyncArchiveRepository, private val datasets: SyncPlaybackDatasetStore) : SyncBackend<Int> {
        private var manifest: ByteArray? = null
        private val objects = HashMap<String, ByteArray>()
        private var version = 0
        var fetches = 0
        var uploads = 0
        var beforeFirstUpload: suspend () -> Unit = {}
        var savedVersion: Int? = null
        var savedTime: Long? = null
        var completedTime: Long? = null
        var followUps = 0
        override val isFirstSync = false
        override val lastSyncTime = 0L
        override val mutationConflictMessage = "local mutation"
        fun seed(data: SyncData) {
            archive.prepare(data).use { prepared ->
                for (obj in prepared.objects) objects[obj.path] = obj.content
                manifest = prepared.content
                version++
            }
        }
        override suspend fun fetch(): Result<SyncDatasetRemoteSnapshot<Int>> {
            fetches++
            val content = manifest ?: return Result.success(SyncDatasetRemoteSnapshot(null, version))
            return archive.readDataset(content, datasets, { it }, { it }) { Result.success(objects.getValue(it)) }
                .map { SyncDatasetRemoteSnapshot(it, version) }
        }
        override suspend fun refetch(version: Int) = fetch()
        override suspend fun upload(data: SyncDataset, version: Int): Result<Int> {
            uploads++
            if (uploads == 1) {
                try { beforeFirstUpload() } catch (error: IOException) { return Result.failure(error) }
            }
            if (version != this.version) return Result.failure(Conflict())
            archive.prepareCancellable(data).use { prepared ->
                for (obj in prepared.objects) objects[obj.path] = obj.content
                manifest = prepared.content
                this.version++
            }
            return Result.success(this.version)
        }
        override fun remoteChanged(version: Int) = savedVersion?.let { it != version } ?: false
        override fun isConflict(error: Throwable?) = error is Conflict
        override fun saveRemoteVersion(version: Int) { savedVersion = version }
        override fun saveSyncTime(timestamp: Long) { savedTime = timestamp }
        override fun saveCompletedSyncTime(timestamp: Long): Boolean { completedTime = timestamp; return true }
        override fun scheduleFollowUp() { followUps++ }
        override fun onFailure(error: Throwable) = Unit
    }
}
