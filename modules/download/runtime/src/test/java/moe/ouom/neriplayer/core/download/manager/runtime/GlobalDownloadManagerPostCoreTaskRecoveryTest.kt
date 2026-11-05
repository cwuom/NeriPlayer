package moe.ouom.neriplayer.core.download.manager.runtime

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomFixture
import moe.ouom.neriplayer.core.download.execution.persistence.request
import moe.ouom.neriplayer.core.download.execution.persistence.testSong
import moe.ouom.neriplayer.core.download.host.DownloadEnvironment
import moe.ouom.neriplayer.core.download.host.DownloadHostBindings
import moe.ouom.neriplayer.core.download.host.DownloadHostRegistry
import moe.ouom.neriplayer.core.download.host.DownloadHosts
import moe.ouom.neriplayer.core.download.host.DownloadCredentials
import moe.ouom.neriplayer.core.download.host.DownloadLyricServices
import moe.ouom.neriplayer.core.download.host.DownloadSourceServices
import moe.ouom.neriplayer.core.download.host.DownloadedPlaybackHost
import moe.ouom.neriplayer.core.download.manager.batch.markSongCancelled
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.download.DownloadStage
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GlobalDownloadManagerPostCoreTaskRecoveryTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val manager = GlobalDownloadManager
    private val song = testSong(637L)
    private val operationId = "post-core-task-recovery"
    private val databaseField = NeriUserDataDatabase::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    private val registry = DownloadHosts::class.java.getDeclaredField("registry")
        .apply { isAccessible = true }.get(null)
    private val installed = DownloadHostRegistry::class.java.getDeclaredField("installed")
        .apply { isAccessible = true }
    private var previousDatabase: NeriUserDataDatabase? = null
    private var previousHosts: DownloadHostBindings? = null
    private var previousJobs = emptySet<Job>()

    @Before
    fun installFixture() {
        previousDatabase = databaseField.get(null) as NeriUserDataDatabase?
        previousHosts = installed.get(registry) as DownloadHostBindings?
        previousJobs = checkNotNull(manager.scope.coroutineContext[Job]).children.toSet()
        databaseField.set(null, fixture.database)
        val environment = mock(DownloadEnvironment::class.java)
        `when`(environment.applicationContext).thenReturn(fixture.context)
        `when`(environment.instrumentationTestActive).thenReturn(true)
        DownloadHosts.install(DownloadHostBindings(environment, mock(DownloadSourceServices::class.java),
            mock(DownloadLyricServices::class.java), mock(DownloadCredentials::class.java),
            mock(DownloadedPlaybackHost::class.java)))
    }

    @After
    fun restoreFixture() = runBlocking<Unit> {
        try {
            manager.assetEnrichmentCoordinator.cancelAndJoin(setOf(operationId), "test cleanup", 5_000L)
            checkNotNull(manager.scope.coroutineContext[Job]).children.filterNot { it in previousJobs }
                .toList().forEach { it.cancelAndJoin() }
            manager.taskStore.removeDownloadTask(song.stableKey())
            manager.clearSongCancelled(song.stableKey())
            AudioDownloadManager.clearNetworkPolicyPause(setOf(song.stableKey()))
        } finally {
            installed.set(registry, previousHosts)
            databaseField.set(null, previousDatabase)
            fixture.close()
        }
    }

    @Test
    fun `reused active enrichment restores a missing task with its durable attempt`() = runBlocking {
        withActiveEnrichment {
            recover()

            val task = manager.taskStore.findTask(song.stableKey())
            assertEquals(71L, task?.attemptId)
            assertEquals(DownloadStatus.QUEUED, task?.status)
            assertEquals(operationId, task?.progress?.operationId)
            assertEquals(DownloadStage.ASSETS_ENRICHING, task?.progress?.stage)
        }
    }

    @Test
    fun `ordinary download click restores progress while its recovery worker is still pending`() = runBlocking {
        fixture.upsert(request(song, operationId = operationId, attemptId = 71L), "ASSETS_ENRICHING")
        fixture.rewrite(operationId) { it.copy(nextRetryAtMs = Long.MAX_VALUE, bytesWritten = 512L, totalBytes = 1024L) }

        manager.scheduleUserDownload(fixture.context, song, skipTrafficRiskPrompt = true)

        withTimeout(5_000L) {
            while (manager.taskStore.findTask(song.stableKey())?.progress == null) delay(10L)
        }
        val task = manager.taskStore.findTask(song.stableKey())
        assertEquals(71L, task?.attemptId)
        assertEquals(DownloadStage.WAITING_RETRY, task?.progress?.stage)
        assertEquals(operationId, task?.progress?.operationId)
        assertEquals(512L, task?.progress?.bytesRead)
        assertEquals(1024L, task?.progress?.totalBytes)
        assertEquals(Long.MAX_VALUE, fixture.row(operationId).nextRetryAtMs)
    }

    @Test
    fun `worker recovery restores a waiting card without consuming a future retry deadline`() = runBlocking {
        fixture.upsert(request(song, operationId = operationId, attemptId = 71L), "DEGRADED_COMPLETE")
        fixture.rewrite(operationId) { it.copy(nextRetryAtMs = Long.MAX_VALUE) }

        recover()

        val task = manager.taskStore.findTask(song.stableKey())
        assertEquals(71L, task?.attemptId)
        assertEquals(DownloadStage.WAITING_RETRY, task?.progress?.stage)
        assertEquals(Long.MAX_VALUE, fixture.row(operationId).nextRetryAtMs)
        assertEquals(0, fixture.row(operationId).retryCount)
    }

    @Test
    fun `legacy post core operation persists its restored progress attempt`() = runBlocking {
        withActiveEnrichment(attemptId = null) {
            manager.recoverPostCoreDownloadOperation(fixture.context, song, operationId, null,
                requireNotNull(manager.downloadAdmissionGate.openTicketOrNull()))

            val attemptId = requireNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
            assertTrue(attemptId > 0L)
            assertEquals(attemptId, fixture.read(operationId)?.attemptId)
        }
    }

    @Test
    fun `old post core recovery cannot replace a newer failed task`() = runBlocking {
        manager.taskStore.ensureDownloadTasks(listOf(song), status = DownloadStatus.FAILED,
            durableAttemptIds = mapOf(song.stableKey() to 72L))
        val newer = manager.taskStore.findTask(song.stableKey())
        withActiveEnrichment {
            recover()
            assertEquals(newer, manager.taskStore.findTask(song.stableKey()))
        }
    }

    @Test
    fun `cancellation during post core task restoration cannot recreate the removed task`() = runBlocking {
        fixture.upsert(request(song, operationId = operationId, attemptId = 71L), "ASSETS_ENRICHING")
        manager.taskStore.ensureDownloadTasks(listOf(song), status = DownloadStatus.FAILED,
            durableAttemptIds = mapOf(song.stableKey() to 71L))
        val cancellingContext = onNetworkRead {
            manager.markSongCancelled(song.stableKey())
            manager.taskStore.removeDownloadTask(song.stableKey(), 71L)
        }

        manager.restorePostCoreDownloadTask(cancellingContext, requireNotNull(fixture.read(operationId)),
            requireNotNull(manager.downloadAdmissionGate.openTicketOrNull()))

        assertNull(manager.taskStore.findTask(song.stableKey()))
    }

    @Test
    fun `replacement during post core task restoration keeps the newer failed attempt`() = runBlocking {
        fixture.upsert(request(song, operationId = operationId, attemptId = 71L), "ASSETS_ENRICHING")
        manager.taskStore.ensureDownloadTasks(listOf(song), status = DownloadStatus.FAILED,
            durableAttemptIds = mapOf(song.stableKey() to 71L))
        val replacingContext = onNetworkRead {
            manager.taskStore.ensureDownloadTasks(listOf(song), status = DownloadStatus.FAILED,
                durableAttemptIds = mapOf(song.stableKey() to 72L))
        }

        manager.restorePostCoreDownloadTask(replacingContext, requireNotNull(fixture.read(operationId)),
            requireNotNull(manager.downloadAdmissionGate.openTicketOrNull()))

        val task = manager.taskStore.findTask(song.stableKey())
        assertEquals(72L, task?.attemptId)
        assertEquals(DownloadStatus.FAILED, task?.status)
        assertNull(task?.progress)
    }

    @Test
    fun `completion during post core task restoration preserves the completed task`() = runBlocking {
        fixture.upsert(request(song, operationId = operationId, attemptId = 71L), "ASSETS_ENRICHING")
        manager.taskStore.ensureDownloadTasks(listOf(song), status = DownloadStatus.FAILED,
            durableAttemptIds = mapOf(song.stableKey() to 71L))
        val finalizingContext = onNetworkRead {
            runBlocking { fixture.rewrite(operationId) { it.copy(state = "FINALIZED") } }
            manager.taskStore.updateTaskStatus(song.stableKey(), DownloadStatus.COMPLETED, 71L)
        }

        manager.restorePostCoreDownloadTask(finalizingContext, requireNotNull(fixture.read(operationId)),
            requireNotNull(manager.downloadAdmissionGate.openTicketOrNull()))

        assertEquals(DownloadStatus.COMPLETED, manager.taskStore.findTask(song.stableKey())?.status)
        assertEquals("FINALIZED", fixture.row(operationId).state)
    }

    @Test
    fun `completion during post core task restoration cannot recreate an already removed task`() = runBlocking {
        fixture.upsert(request(song, operationId = operationId, attemptId = 71L), "ASSETS_ENRICHING")
        manager.taskStore.ensureDownloadTasks(listOf(song), status = DownloadStatus.FAILED,
            durableAttemptIds = mapOf(song.stableKey() to 71L))
        val finalizingContext = onNetworkRead {
            runBlocking { fixture.rewrite(operationId) { it.copy(state = "FINALIZED") } }
            manager.taskStore.removeDownloadTask(song.stableKey(), 71L)
        }

        manager.restorePostCoreDownloadTask(finalizingContext, requireNotNull(fixture.read(operationId)),
            requireNotNull(manager.downloadAdmissionGate.openTicketOrNull()))

        assertNull(manager.taskStore.findTask(song.stableKey()))
        assertEquals("FINALIZED", fixture.row(operationId).state)
    }

    @Test
    fun `post core recovery does not revive a cancelled task`() = runBlocking {
        manager.taskStore.ensureDownloadTasks(listOf(song), status = DownloadStatus.CANCELLED,
            durableAttemptIds = mapOf(song.stableKey() to 71L))
        val cancelled = manager.taskStore.findTask(song.stableKey())
        withActiveEnrichment {
            recover()
            assertEquals(cancelled, manager.taskStore.findTask(song.stableKey()))
        }
    }

    @Test
    fun `stopped post core operation cannot recreate its missing task`() = runBlocking {
        withActiveEnrichment {
            fixture.rewrite(operationId) { it.copy(stopRequestedByUser = true) }
            recover()
            assertNull(manager.taskStore.findTask(song.stableKey()))
        }
    }

    private suspend fun withActiveEnrichment(attemptId: Long? = 71L, block: suspend () -> Unit) {
        fixture.upsert(request(song, operationId = operationId, attemptId = attemptId), "ASSETS_ENRICHING")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        manager.assetEnrichmentCoordinator.enqueue(operationId) {
            entered.complete(Unit)
            release.await()
        }
        entered.await()
        try {
            block()
        } finally {
            release.complete(Unit)
            manager.assetEnrichmentCoordinator.awaitCompletion(setOf(operationId), 5_000L)
        }
    }

    private suspend fun recover() {
        manager.recoverPostCoreDownloadOperation(fixture.context, song, operationId, 71L,
            requireNotNull(manager.downloadAdmissionGate.openTicketOrNull()))
    }

    private fun onNetworkRead(action: () -> Unit): Context = object : ContextWrapper(fixture.context) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name != CONNECTIVITY_SERVICE) return super.getSystemService(name)
            action()
            return null
        }
    }
}
