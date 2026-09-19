package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.manager.admission.mutateWifiBoundNetworkPolicyIfStillRequired
import moe.ouom.neriplayer.core.download.manager.facade.continueDownloadsOnMobileDataAndWake
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadMobileContinuationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun batchConfirmationWakesPersistentPumpWithoutGlobalMobileOverride() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("continuationNetwork")
        if (phase == "offline") assertNull(context.currentDownloadNetworkTypeOrNull())
        if (phase == "mobile") assertEquals(TrafficNetworkType.MOBILE, context.currentDownloadNetworkTypeOrNull())
        val workManager = WorkManager.getInstance(context)
        requireNotNull(ForegroundDownloadWorker.cancelAllOwned(context))
            .result.get(10, TimeUnit.SECONDS)
        val beforeWork = workManager.getWorkInfosForUniqueWork(ForegroundDownloadWorker.PUMP_WORK_NAME)
            .get().associateBy { it.id }
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        val previousOverride = GlobalDownloadManager.mobileDataDownloadOverrideAllowed
        try {
            val track = song(1L)
            val identity = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            val request = DownloadExecutionRequest(
                operationId = UUID.randomUUID().toString(), song = track,
                batchId = identity.batchId, batchGeneration = identity.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "RETRYABLE", queueOrder = 9, database = db)
            DownloadExecutionRoomStore.attachBatchIdentity(context, identity, listOf(request), database = db)
            val dao = db.downloadOperationDao()
            val before = dao.find(request.operationId)!!
            dao.upsert(before.copy(bytesWritten = 512L, nextRetryAtMs = Long.MAX_VALUE))
            val generation = AudioDownloadManager.currentDownloadNetworkGeneration()
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context, listOf(identity), generation, expectedNetworkGeneration = null, database = db
            )
            val unrelated = DownloadExecutionRequest(operationId = UUID.randomUUID().toString(), song = song(2L))
            DownloadExecutionRoomStore.upsert(context, unrelated, "QUEUED", queueOrder = 1, database = db)
            GlobalDownloadManager.mobileDataDownloadOverrideAllowed = false
            val oldPolicyEpoch = GlobalDownloadManager.wifiBoundNetworkPolicyEpoch.get()
            val confirmation = GlobalDownloadManager.MobileDataDownloadInterruptionRequest(
                id = 1L, networkType = TrafficNetworkType.MOBILE, taskCount = 1,
                batchIdentities = listOf(GlobalDownloadManager.MobileDataDownloadBatchIdentity(identity.batchId, identity.generation)),
                networkGeneration = generation
            )

            assertTrue(GlobalDownloadManager.continueDownloadsOnMobileDataAndWake(context, confirmation, db))
            val persistentPump = withTimeout(10_000L) {
                var selected: WorkInfo? = null
                while (selected == null) {
                    val currentWork = workManager.getWorkInfosForUniqueWork(
                        ForegroundDownloadWorker.PUMP_WORK_NAME
                    ).get()
                    selected = currentWork.firstOrNull { work ->
                        work.id !in beforeWork &&
                            work.state != WorkInfo.State.CANCELLED &&
                            work.state != WorkInfo.State.FAILED
                    } ?: currentWork.firstOrNull { work ->
                        // 离线确认可以接管启动恢复已经登记的同一个持久任务
                        beforeWork[work.id]?.state?.isFinished == false && !work.state.isFinished
                    }
                    if (selected == null) delay(25L)
                }
                selected
            }
            val previousPump = beforeWork[persistentPump.id]
            Log.i(
                "DownloadMobileContinuationTest",
                "persistent pump receipt: kind=${if (previousPump == null) "new" else "reused"}, " +
                    "id=${persistentPump.id}, before=${previousPump?.state}, after=${persistentPump.state}"
            )
            assertFalse(GlobalDownloadManager.mobileDataDownloadOverrideAllowed)
            assertFalse(DownloadExecutionRoomStore.read(context, request.operationId, db)!!.requiresWifiNetwork)
            assertTrue(DownloadExecutionRoomStore.read(context, unrelated.operationId, db)!!.requiresWifiNetwork)
            val resumed = dao.find(request.operationId)!!
            assertEquals(before.queueOrder, resumed.queueOrder)
            assertEquals(before.createdAtMs, resumed.createdAtMs)
            assertEquals(512L, resumed.bytesWritten)
            assertNull(resumed.nextRetryAtMs)
            val page = DownloadExecutionRoomReadStore.listSchedulableForPumpPage(context, null, 10, db)
            assertEquals(listOf(request.operationId, unrelated.operationId), page.requests.map { it.operationId })
            assertFalse(GlobalDownloadManager.mutateWifiBoundNetworkPolicyIfStillRequired(context, oldPolicyEpoch) {
                throw AssertionError("stale network snapshot changed resumed tasks")
            })
        } finally {
            GlobalDownloadManager.mobileDataDownloadOverrideAllowed = previousOverride
            try {
                requireNotNull(ForegroundDownloadWorker.cancelAllOwned(context))
                    .result.get(10, TimeUnit.SECONDS)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun completedOrStaleBatchInDialogCannotBlockRemainingBatchOrReviveCancelledWork() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val identities = (1L..3L).map { id ->
                DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(song(id)), database = db)
            }
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context, identities, 7L, expectedNetworkGeneration = null, database = db
            )
            val dao = db.downloadBatchDao()
            val completed = identities[0]
            val cancelled = identities[1]
            dao.updateStateBitsCAS(completed.batchId, completed.generation, DownloadBatchState.COMPLETED, 1L)
            dao.markCancelled(cancelled.batchId, cancelled.generation, 1L)
            // 模拟确认协程读到网络代次后，另一次连接变化先完成落盘
            DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(context, 9L, database = db)
            assertEquals(1, DownloadExecutionRoomStore.allowBatchesMobileData(
                context, identities + identities[2].copy(generation = identities[2].generation + 1),
                expectedNetworkGeneration = 7L, networkGeneration = 8L, database = db
            ))
            assertEquals(DownloadBatchState.COMPLETED, dao.findBatch(completed.batchId, completed.generation)!!.stateBits)
            assertTrue(dao.findBatch(cancelled.batchId, cancelled.generation)!!.stateBits and DownloadBatchState.CANCELLED != 0)
            assertTrue(dao.findBatch(identities[2].batchId, identities[2].generation)!!.stateBits and DownloadBatchState.USER_MOBILE_ALLOWED != 0)
            assertEquals(9L, dao.findBatch(identities[2].batchId, identities[2].generation)!!.networkGeneration)
        } finally {
            db.close()
        }
    }

    private fun song(id: Long) = SongItem(
        id = id, name = "continuation-$id", artist = "test", album = "netease",
        albumId = 0, durationMs = 1000, coverUrl = null
    )
}
