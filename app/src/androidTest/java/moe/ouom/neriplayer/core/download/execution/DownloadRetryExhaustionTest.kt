package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import moe.ouom.neriplayer.core.download.manager.recovery.resetInvalidCoreDownloadForTransfer
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadOperationEntryPoint
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadRetryExhaustionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun invalidCoreTransferAndFinalizationShareBudgetAcrossReopen() = runBlocking {
        val name = "invalid-core-budget-${UUID.randomUUID()}"
        var db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        try {
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(song()), database = db)
            val request = DownloadExecutionRequest(operationId = "invalid-core-budget", song = song(),
                attemptId = 18L, batchId = batch.batchId, batchGeneration = batch.generation)
            DownloadExecutionRoomStore.upsert(context, request, "RUNNING", queueOrder = 4, database = db)
            DownloadExecutionRoomStore.attachBatchIdentity(context, batch, listOf(request), database = db)
            repeat(3) { index ->
                assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "RUNNING", database = db))
                assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "COMMITTING", database = db))
                assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "CORE_COMMITTED", database = db))
                val header = requireNotNull(db.downloadOperationDao().find(request.operationId))
                db.managedDownloadArtifactDao().upsert(ManagedDownloadArtifactEntity(
                    rootKey = header.libraryId, stableKey = song().stableKey(), artifactId = "artifact",
                    state = "CORE_COMMITTED", leaseId = request.artifactLeaseId,
                    audioReference = "content://test/invalid-$index.mp3", audioName = "invalid-$index.mp3"
                ))
                val result = requireNotNull(resetInvalidCoreDownloadForTransfer(
                    context, song(), request.operationId, "content://test/invalid-$index.mp3",
                    request.artifactLeaseId, "CORE_AUDIO_DURATION_MISMATCH", db
                ))
                assertEquals(index == 2, result.retryExhausted)
                assertEquals(4, db.downloadOperationDao().find(request.operationId)?.queueOrder)
                db.close()
                db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
                // 进程恢复和迟到 payload 刷新不能重新赋予预算或复活失败任务
                DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
                DownloadExecutionRoomStore.requeueOrphanedRunningOperations(context, database = db)
                assertEquals(if (index == 2) "INVALID" else "RETRYABLE",
                    db.downloadOperationDao().find(request.operationId)?.state)
                if (index < 2) assertEquals(index + 1, db.downloadOperationDao().find(request.operationId)?.retryCount)
            }
            assertEquals(DownloadBatchMemberTerminal.FAILED,
                db.downloadBatchDao().listMembers(batch.batchId).single().terminalBits)
            assertTrue(DownloadExecutionRoomReadStore.listSchedulableForPumpPage(
                context, null, 10, db, nowMs = Long.MAX_VALUE).requests.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun executionHostStopsRequestingSystemRetriesAfterDurableFailure() = runBlocking {
        val db = NeriUserDataDatabase.getInstance(context)
        for ((code, limit) in listOf("DOWNLOAD_SOURCE_MISSING" to 3, "DOWNLOAD_NO_PROGRESS" to 6,
                "DOWNLOAD_HOST_FAILURE:IOException" to 6)) {
            val request = DownloadExecutionRequest(operationId = "host-budget-${UUID.randomUUID()}",
                song = song(), attemptId = 30L, requiresWifiNetwork = false)
            var calls = 0
            try {
                DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
                val host = DefaultDownloadExecutionHost(
                    entryPoint = DownloadOperationEntryPoint { _, active ->
                        calls++
                        if (code.startsWith("DOWNLOAD_HOST_FAILURE:")) throw java.io.IOException("fixture failure")
                        if (code == "DOWNLOAD_SOURCE_MISSING") {
                            assertTrue(DownloadExecutionRoomStore.updateState(context, active.operationId,
                                "RETRYABLE", code, database = db, nowMs = 0L, expectedAttemptId = 30L))
                        }
                        DownloadExecutionResult.Retry
                    }, sdkInt = 28
                )
                repeat(limit) { index ->
                    val result = host.execute(context, request.operationId)
                    assertEquals(index + 1, calls)
                    if (index == limit - 1) {
                        assertEquals(DownloadExecutionResult.MissingOperation, result)
                    } else if (code.startsWith("DOWNLOAD_HOST_FAILURE:")) {
                        assertTrue(result is DownloadExecutionResult.Failed)
                    } else {
                        assertEquals(DownloadExecutionResult.Retry, result)
                    }
                    if (index < limit - 1) {
                        val row = requireNotNull(db.downloadOperationDao().find(request.operationId))
                        assertEquals(index + 1, row.retryCount)
                        assertTrue(requireNotNull(row.nextRetryAtMs) > 0L)
                        // 推进持久时钟至下一次到期，无需等待真实退避时间
                        db.downloadOperationDao().upsert(row.copy(nextRetryAtMs = 0L))
                    }
                }
                assertEquals("INVALID", db.downloadOperationDao().find(request.operationId)?.state)
                assertEquals(DownloadExecutionResult.MissingOperation, host.execute(context, request.operationId))
                assertEquals(limit, calls)
            } finally {
                DownloadExecutionRoomStore.delete(context, request.operationId)
            }
        }
    }

    @Test
    fun lateAutomaticQueueRefreshCannotMintANewBudgetAfterFailureButExplicitRetryCan() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val queue = DownloadRecoveryRoomStore(context, db)
            val request = DownloadExecutionRequest(operationId = "failed-owner", song = song(), attemptId = 9L)
            DownloadExecutionRoomStore.upsert(context, request, "INVALID", database = db)
            repeat(10) {
                assertTrue(queue.upsertPendingDownloadQueue(listOf(song()), userInitiated = false).isEmpty())
            }
            val newId = queue.upsertPendingDownloadQueue(listOf(song()), userInitiated = true).single()
            assertFalse(newId == request.operationId)
            assertEquals(0, db.downloadOperationDao().find(newId)?.retryCount)
            assertEquals(listOf(newId), queue.upsertPendingDownloadQueue(listOf(song()), userInitiated = false))
            assertEquals("INVALID", db.downloadOperationDao().find(request.operationId)?.state)
        } finally {
            db.close()
        }
    }

    @Test
    fun failureBudgetAndDeadlineSurviveRefreshAndRestartThenSettleBatchAtomically() = runBlocking {
        val databaseName = "download-retry-${UUID.randomUUID()}"
        var db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, databaseName).build()
        try {
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(song()), database = db)
            val request = DownloadExecutionRequest(
                operationId = "invalid-payload", song = song(), attemptId = 7L,
                batchId = batch.batchId, batchGeneration = batch.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "RUNNING", queueOrder = 4, database = db)
            DownloadExecutionRoomStore.attachBatchIdentity(context, batch, listOf(request), database = db)
            repeat(3) { attempt ->
                assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "RUNNING",
                    database = db, expectedAttemptId = 7L))
                assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "RETRYABLE",
                    errorCode = "DOWNLOAD_INTEGRITY_DURATION_MISMATCH", database = db,
                    nowMs = 1000L + attempt * 10000L, expectedAttemptId = 7L))
                val failed = requireNotNull(db.downloadOperationDao().find(request.operationId))
                if (attempt < 2) {
                    assertEquals(attempt + 1, failed.retryCount)
                    assertTrue(requireNotNull(failed.nextRetryAtMs) > 1000L + attempt * 10000L)
                    // 同一代 payload 刷新以及迟到的宿主回调不能清空退避或重复计数
                    DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
                    assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "RETRYABLE",
                        errorCode = "HOST_FAILED", database = db, expectedAttemptId = 7L))
                    val refreshed = requireNotNull(db.downloadOperationDao().find(request.operationId))
                    assertEquals(failed.retryCount, refreshed.retryCount)
                    assertEquals(failed.nextRetryAtMs, refreshed.nextRetryAtMs)
                    assertEquals(4, refreshed.queueOrder)
                } else {
                    assertEquals("INVALID", failed.state)
                    assertEquals("DOWNLOAD_INTEGRITY_DURATION_MISMATCH_RETRY_EXHAUSTED", failed.lastErrorCode)
                    assertNull(failed.nextRetryAtMs)
                    assertEquals(DownloadBatchMemberTerminal.FAILED,
                        db.downloadBatchDao().listMembers(batch.batchId).single().terminalBits)
                }
                db.close()
                db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, databaseName).build()
            }
            assertFalse(DownloadExecutionRoomStore.updateState(context, request.operationId, "RETRYABLE",
                errorCode = "HOST_FAILED", database = db, expectedAttemptId = 7L))
            DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
            DownloadExecutionRoomStore.requeueOrphanedRunningOperations(context, database = db)
            assertEquals("INVALID", db.downloadOperationDao().find(request.operationId)?.state)
            assertTrue(DownloadExecutionRoomReadStore.listSchedulableForPumpPage(
                context, null, 10, db, nowMs = Long.MAX_VALUE
            ).requests.isEmpty())
            val manualRetry = request.copy(operationId = "explicit-new-attempt", attemptId = 8L,
                batchId = null, batchGeneration = null, userInitiated = true)
            DownloadExecutionRoomStore.upsert(context, manualRetry, "QUEUED", queueOrder = 4, database = db)
            assertEquals(listOf(manualRetry.operationId),
                DownloadExecutionRoomReadStore.listSchedulableForPumpPage(
                    context, null, 10, db, nowMs = Long.MAX_VALUE
                ).requests.map { it.operationId })
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun networkWaitDoesNotUseFailureBudgetAndStaleAttemptCannotFailCurrentOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val request = DownloadExecutionRequest(operationId = "network", song = song(), attemptId = 9L)
            DownloadExecutionRoomStore.upsert(context, request, "RUNNING", database = db)
            repeat(12) {
                assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "RUNNING", database = db))
                assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "RETRYABLE",
                    errorCode = "NETWORK_UNAVAILABLE", database = db, expectedAttemptId = 9L))
                assertEquals(0, db.downloadOperationDao().find(request.operationId)?.retryCount)
            }
            assertTrue(DownloadExecutionRoomStore.updateState(context, request.operationId, "RUNNING", database = db))
            DownloadExecutionRoomStore.upsert(context, request.copy(attemptId = 8L), "QUEUED", database = db)
            DownloadExecutionRoomStore.upsert(context, request.copy(attemptId = null), "QUEUED", database = db)
            assertEquals(9L, DownloadExecutionRoomStore.read(context, request.operationId, db)?.attemptId)
            assertFalse(DownloadExecutionRoomStore.updateState(context, request.operationId, "RETRYABLE",
                errorCode = "DOWNLOAD_INTEGRITY_CHECKSUM_MISMATCH", database = db, expectedAttemptId = 8L))
            assertEquals("RUNNING", db.downloadOperationDao().find(request.operationId)?.state)
            assertEquals(0, db.downloadOperationDao().find(request.operationId)?.retryCount)
        } finally {
            db.close()
        }
    }

    private fun song() = SongItem(id = 1897084202L, name = "终结之焰", artist = "鹿乃",
        album = "netease", albumId = 0L, durationMs = 200869L, coverUrl = null)

    @Test
    fun sourceTransportAndUnexpectedHostFailuresAllHavePersistentLimits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            for ((code, limit) in listOf("DOWNLOAD_SOURCE_MISSING" to 3, "DOWNLOAD_FAILED" to 6,
                    "DOWNLOAD_TRANSIENT_FAILURE" to 8, "DOWNLOAD_HOST_FAILURE:IOException" to 6,
                    "DOWNLOAD_STORAGE_UNAVAILABLE" to 6)) {
                val request = DownloadExecutionRequest(operationId = code, song = song(), attemptId = 10L)
                DownloadExecutionRoomStore.upsert(context, request, "RUNNING", database = db)
                repeat(limit) { index ->
                    assertTrue(DownloadExecutionRoomStore.updateState(context, code, "RUNNING", database = db))
                    assertTrue(DownloadExecutionRoomStore.updateState(context, code, "RETRYABLE",
                        errorCode = code, database = db, expectedAttemptId = 10L))
                    assertEquals(if (index + 1 == limit) "INVALID" else "RETRYABLE",
                        db.downloadOperationDao().find(code)?.state)
                }
                assertFalse(DownloadExecutionRoomStore.updateState(context, code, "RUNNING", database = db))
            }
        } finally {
            db.close()
        }
    }
}
