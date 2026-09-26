package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.worker.PostCoreDownloadRecoveryWorker
import moe.ouom.neriplayer.core.download.manager.runtime.recoverPostCoreDownloadOperation
import moe.ouom.neriplayer.core.download.manager.runtime.PostCoreDownloadRecoveryResult
import moe.ouom.neriplayer.core.download.manager.runtime.recoverPostCoreDownloadsForWorkerImpl
import moe.ouom.neriplayer.core.download.manager.runtime.resumePostCoreDownloadsAfterProgressRestore
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadPostCoreNetworkRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun restoredNetworkClearsPausedPostCoreOperation() = runBlocking {
        assertNotNull(context.currentDownloadNetworkTypeOrNull())
        withRequest { request ->
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(setOf(request.song.stableKey()))
            GlobalDownloadManager.recoverPostCoreDownloadOperation(
                context, request.song, request.operationId, request.attemptId,
                requireNotNull(GlobalDownloadManager.downloadAdmissionGate.openTicketOrNull())
            )
            assertFalse(AudioDownloadManager.isDownloadPausedForNetworkPolicy(request.song.stableKey()))
        }
    }

    @Test
    fun startupRecoveryTakesOverWorkerStillWaitingForSystemScheduling() = runBlocking {
        assertNotNull(context.currentDownloadNetworkTypeOrNull())
        val workManager = WorkManager.getInstance(context)
        val coordinator = PostCoreDownloadRecoveryWorker.scheduleCoordinator
        workManager.cancelUniqueWork("download_post_core_recovery").result.get()
        coordinator.invalidate()
        GlobalDownloadManager.startupProgressRestoreReady.complete(Unit)
        try {
            withRequest { request ->
                assertTrue(PostCoreDownloadRecoveryWorker.schedule(context, initialDelayMs = 86_400_000L))
                AudioDownloadManager.pauseDownloadsForNetworkPolicy(setOf(request.song.stableKey()))
                val parent = requireNotNull(GlobalDownloadManager.scope.coroutineContext[Job])
                val existingJobs = parent.children.toSet()
                try {
                    GlobalDownloadManager.resumePostCoreDownloadsAfterProgressRestore(
                        context, requireNotNull(GlobalDownloadManager.downloadAdmissionGate.openTicketOrNull())
                    )
                    withTimeout(5_000L) {
                        while (AudioDownloadManager.isDownloadPausedForNetworkPolicy(request.song.stableKey())) {
                            delay(20L)
                        }
                    }
                } finally {
                    coordinator.invalidate()
                    // 只等待这次唤醒创建的协程，防止夹具清理与迟到的 artifact 写入竞争
                    parent.children.filter { it !in existingJobs }.toList().forEach { it.cancelAndJoin() }
                }
            }
        } finally {
            coordinator.invalidateAndSubmitCancellation {
                workManager.cancelUniqueWork("download_post_core_recovery")
            }.result.get()
        }
    }

    @Test
    fun staleAdmissionTicketCannotReenterRecoveryUsingCurrentTicket() = runBlocking {
        withRequest { request ->
            val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            val before = requireNotNull(dao.find(request.operationId))
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(setOf(request.song.stableKey()))
            val current = requireNotNull(GlobalDownloadManager.downloadAdmissionGate.openTicketOrNull())
            assertEquals(
                PostCoreDownloadRecoveryResult.BLOCKED,
                GlobalDownloadManager.recoverPostCoreDownloadsForWorker(
                    context, expectedAdmissionTicket = current - 1L
                )
            )
            assertEquals(before, dao.find(request.operationId))
            assertTrue(AudioDownloadManager.isDownloadPausedForNetworkPolicy(request.song.stableKey()))
        }
    }

    @Test
    fun userStoppedPostCoreOperationCannotClearNetworkPause() = runBlocking {
        withRequest { request ->
            val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            dao.upsert(requireNotNull(dao.find(request.operationId)).copy(stopRequestedByUser = true))
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(setOf(request.song.stableKey()))
            GlobalDownloadManager.recoverPostCoreDownloadOperation(
                context, request.song, request.operationId, request.attemptId,
                requireNotNull(GlobalDownloadManager.downloadAdmissionGate.openTicketOrNull())
            )
            assertTrue(AudioDownloadManager.isDownloadPausedForNetworkPolicy(request.song.stableKey()))
        }
    }

    @Test
    fun staleAttemptCannotClearNewerNetworkPause() = runBlocking {
        withRequest { request ->
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(setOf(request.song.stableKey()))
            GlobalDownloadManager.recoverPostCoreDownloadOperation(
                context, request.song, request.operationId, 123L,
                requireNotNull(GlobalDownloadManager.downloadAdmissionGate.openTicketOrNull())
            )
            assertTrue(AudioDownloadManager.isDownloadPausedForNetworkPolicy(request.song.stableKey()))
        }
    }

    @Test
    fun offlineRecoveryKeepsNetworkPause() = runBlocking {
        val offline = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        withRequest { request ->
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(setOf(request.song.stableKey()))
            GlobalDownloadManager.recoverPostCoreDownloadOperation(
                offline, request.song, request.operationId, request.attemptId,
                requireNotNull(GlobalDownloadManager.downloadAdmissionGate.openTicketOrNull())
            )
            assertTrue(AudioDownloadManager.isDownloadPausedForNetworkPolicy(request.song.stableKey()))
        }
    }

    @Test
    fun spareEnrichmentCapacityRecoversOldWorkWhileAnotherSongIsStillActive() = runBlocking {
        assertNotNull(context.currentDownloadNetworkTypeOrNull())
        withRequest { old ->
            withRequest { active ->
                val release = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                val coordinator = GlobalDownloadManager.assetEnrichmentCoordinator
                coordinator.enqueue(active.operationId) {
                    entered.complete(Unit)
                    release.await()
                }
                entered.await()
                assertTrue(coordinator.availableCapacity() > 0)
                val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
                val before = requireNotNull(dao.find(old.operationId))
                val recovery = async { GlobalDownloadManager.recoverPostCoreDownloadsForWorkerImpl(context) }
                try {
                    withTimeout(5_000L) {
                        while (dao.find(old.operationId) == before) delay(20L)
                    }
                    assertFalse(release.isCompleted)
                } finally {
                    recovery.cancel()
                    release.complete(Unit)
                    recovery.join()
                    assertTrue(coordinator.awaitCompletion(setOf(active.operationId), 5_000L))
                }
            }
        }
    }

    private suspend fun withRequest(block: suspend (DownloadExecutionRequest) -> Unit) {
        val id = UUID.randomUUID().toString()
        val request = DownloadExecutionRequest(
            operationId = id,
            song = SongItem(id.hashCode().toLong(), "recovery-$id", "test", "netease", 0, 1000, null),
            requiresWifiNetwork = false
        )
        DownloadExecutionRoomStore.upsert(context, request, "CORE_COMMITTED")
        try {
            block(request)
        } finally {
            AudioDownloadManager.clearNetworkPolicyPause(setOf(request.song.stableKey()))
            GlobalDownloadManager.taskStore.removeDownloadTask(request.song.stableKey())
            DownloadExecutionRoomStore.delete(context, id)
            val dao = NeriUserDataDatabase.getInstance(context).managedDownloadArtifactDao()
            dao.findAllByStableKey(request.song.stableKey()).forEach { artifact ->
                dao.deleteIfUnchanged(artifact.rootKey, artifact.stableKey, artifact.state,
                    artifact.leaseId, artifact.updatedAtMs)
            }
        }
    }
}
