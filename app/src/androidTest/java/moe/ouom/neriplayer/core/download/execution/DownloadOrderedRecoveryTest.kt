package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testing.awaitProcessDeathAtSeedCheckpoint
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.manager.recovery.resetInvalidCoreDownloadForTransfer
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadOrderedRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun processDeathRecoversOriginalQueueAndResumePolicy() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        val name = "ordered-process-death-test"
        if (phase != "recover") context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        try {
            if (phase != "recover") {
                seed(db, "in-flight", 4, "RUNNING", bytes = 555, error = "seed-pid:${Process.myPid()}")
                val request = DownloadExecutionRoomStore.read(context, "in-flight", db)!!
                DownloadExecutionRoomStore.upsert(
                    context, request.copy(requiresWifiNetwork = false), "RUNNING", database = db
                )
                seed(db, "committing", 5, "COMMITTING", bytes = 3971909)
                val committing = DownloadExecutionRoomStore.read(context, "committing", db)!!
                DownloadExecutionRoomStore.upsert(
                    context, committing.copy(requiresWifiNetwork = false, preserveStaging = true),
                    "COMMITTING", database = db
                )
                seed(db, "new-after-restart", 6)
            }
            if (phase == "seed") {
                awaitProcessDeathAtSeedCheckpoint()
                return@runBlocking
            }
            val before = db.downloadOperationDao().find("in-flight")!!
            val commitBefore = db.downloadOperationDao().find("committing")!!
            val commitPayload = DownloadExecutionRoomStore.read(context, "committing", db)!!
            if (phase == "recover") {
                assertFalse(before.lastErrorCode == "seed-pid:${Process.myPid()}")
            }
            DownloadExecutionRoomStore.requeueOrphanedRunningOperations(context, database = db)
            val recovered = db.downloadOperationDao().find("in-flight")!!
            assertEquals("RETRYABLE", recovered.state)
            assertEquals(before.queueOrder, recovered.queueOrder)
            assertEquals(before.createdAtMs, recovered.createdAtMs)
            assertEquals(555L, recovered.bytesWritten)
            assertFalse(DownloadExecutionRoomStore.read(context, "in-flight", db)!!.requiresWifiNetwork)
            val commitRecovered = db.downloadOperationDao().find("committing")!!
            assertEquals("RETRYABLE", commitRecovered.state)
            assertEquals(commitBefore.queueOrder, commitRecovered.queueOrder)
            assertEquals(commitBefore.createdAtMs, commitRecovered.createdAtMs)
            assertEquals(3971909L, commitRecovered.bytesWritten)
            assertEquals(commitPayload, DownloadExecutionRoomStore.read(context, "committing", db))
            assertTrue(commitPayload.preserveStaging)
            assertFalse(commitPayload.requiresWifiNetwork)
            assertEquals(
                listOf("in-flight", "committing", "new-after-restart"),
                page(db).requests.map { it.operationId }
            )
        } finally {
            db.close()
            if (phase != "seed") context.deleteDatabase(name)
        }
    }

    @Test
    fun interruptedRowsStayAheadOfFreshRowsAcrossDatabaseReopen() = runBlocking {
        val name = "ordered-recovery-${UUID.randomUUID()}"
        var db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        try {
            seed(db, "old-first", 1, "RETRYABLE", bytes = 300, error = "NETWORK_LOST")
            seed(db, "old-second", 2, "RETRYABLE", error = "PROCESS_RESTART_RECOVERY")
            seed(db, "fresh-legacy-low-order", 0)
            val first = page(db, limit = 1)
            assertEquals(listOf("old-first"), first.requests.map { it.operationId })
            val row = db.downloadOperationDao().find("old-first")!!
            db.downloadOperationDao().upsert(row.copy(updatedAtMs = 9000L))
            db.close()
            db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
            val next = DownloadExecutionRoomReadStore.listSchedulableForPumpPage(
                context, first.nextCursor, 10, database = db, nowMs = 1000L
            )
            assertEquals(listOf("old-second", "fresh-legacy-low-order"), next.requests.map { it.operationId })
            assertEquals(300L, db.downloadOperationDao().find("old-first")!!.bytesWritten)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun retryDeadlineBlocksFreshWorkAndExplicitResumeRemovesOnlyItsDeadline() = runBlocking {
        withDatabase { db ->
            seed(db, "retry", 5, "RETRYABLE", bytes = 123, retryAt = 5000L, error = "NETWORK_LOST")
            seed(db, "new", 6)
            val blocked = page(db)
            assertTrue(blocked.requests.isEmpty())
            assertEquals(5000L, blocked.nextRetryAtMs)
            assertNull(blocked.nextCursor)
            assertTrue(DownloadExecutionRoomStore.prepareExplicitResume(
                context, "retry", song("retry").stableKey(), database = db
            ))
            val resumed = page(db)
            assertEquals(listOf("retry", "new"), resumed.requests.map { it.operationId })
            val retained = db.downloadOperationDao().find("retry")!!
            assertEquals(5, retained.queueOrder)
            assertEquals(123L, retained.bytesWritten)
            assertNull(retained.nextRetryAtMs)
        }
    }

    @Test
    fun newQueueOrdersFollowRunningRowsEvenWhenNoWaitingRowsRemain() = runBlocking {
        withDatabase { db ->
            seed(db, "running", 42, "RUNNING")
            val queued = DownloadRecoveryRoomStore(context, db).upsertPendingDownloadQueue(
                listOf(song("A"), song("B")), nowMs = 100L, userInitiated = true
            )
            assertEquals(listOf(43, 44), queued.map { db.downloadOperationDao().find(it)!!.queueOrder })
        }
    }

    @Test
    fun directHostCannotOvertakeRecoveryButAlreadyAdmittedWorkCanContinue() = runBlocking {
        withDatabase { db ->
            seed(db, "interrupted", 5, "RETRYABLE", error = "PROCESS_RESTART_RECOVERY")
            seed(db, "new", 0)
            assertFalse(DownloadExecutionRoomStore.tryAcquireHostAdmission(context, "new", 3, database = db))
            assertTrue(DownloadExecutionRoomStore.tryAcquireHostAdmission(context, "interrupted", 3, database = db))
            assertTrue(DownloadExecutionRoomStore.tryAcquireHostAdmission(context, "new", 3, database = db))
            assertTrue(DownloadExecutionRoomStore.tryAcquireHostAdmission(context, "interrupted", 3, database = db))
        }
    }

    @Test
    fun stoppedRowsDoNotBlockOtherDownloads() = runBlocking {
        withDatabase { db ->
            seed(db, "stopped", 0, "RETRYABLE", error = "USER_CANCELLED")
            val old = db.downloadOperationDao().find("stopped")!!
            db.downloadOperationDao().upsert(old.copy(stopRequestedByUser = true))
            seed(db, "new", 1)
            assertEquals(listOf("new"), page(db).requests.map { it.operationId })
            assertTrue(DownloadExecutionRoomStore.tryAcquireHostAdmission(context, "new", 1, database = db))
        }
    }

    @Test
    fun continuingOfflinePersistsBatchPolicyAcrossNetworkGenerationAndPayloadRefresh() = runBlocking {
        withDatabase { db ->
            val track = song("batch-song")
            val identity = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            val request = DownloadExecutionRequest(
                operationId = "batch-song", song = track, requiresWifiNetwork = true,
                batchId = identity.batchId, batchGeneration = identity.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
            DownloadExecutionRoomStore.attachBatchIdentity(context, identity, listOf(request), database = db)
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context, listOf(identity), 7L, expectedNetworkGeneration = null, database = db
            )
            assertEquals(1, DownloadExecutionRoomStore.allowBatchesMobileData(
                context, listOf(identity), expectedNetworkGeneration = 7L, networkGeneration = 8L, database = db
            ))
            assertFalse(DownloadExecutionRoomStore.read(context, request.operationId, db)!!.requiresWifiNetwork)
            DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
            assertFalse(DownloadExecutionRoomStore.read(context, request.operationId, db)!!.requiresWifiNetwork)
            val batch = db.downloadBatchDao().findBatch(identity.batchId, identity.generation)!!
            assertTrue(DownloadExecutionRoomStore.canStartBatchForCurrentNetwork(batch, 9L))
            DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(context, 9L, database = db)
            DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
            assertFalse(DownloadExecutionRoomStore.read(context, request.operationId, db)!!.requiresWifiNetwork)
            assertEquals(0, DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context, listOf(identity), 10L, expectedNetworkGeneration = batch.networkGeneration, database = db
            ))
            seed(db, "unrelated", 1)
            assertTrue(DownloadExecutionRoomStore.read(context, "unrelated", db)!!.requiresWifiNetwork)
        }
    }

    @Test
    fun continueIntentSurvivesWifiReconnectingBeforeConfirmationButCannotReviveCancelledBatch() = runBlocking {
        withDatabase { db ->
            val track = song("confirmation-race")
            val identity = DownloadExecutionRoomStore.createBatchSnapshot(context, listOf(track), database = db)
            val request = DownloadExecutionRequest(
                operationId = "confirmation-race", song = track,
                batchId = identity.batchId, batchGeneration = identity.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "QUEUED", database = db)
            DownloadExecutionRoomStore.attachBatchIdentity(context, identity, listOf(request), database = db)
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context, listOf(identity), 7L, expectedNetworkGeneration = null, database = db
            )
            DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFences(context, 8L, database = db)
            assertEquals(1, DownloadExecutionRoomStore.allowBatchesMobileData(
                context, listOf(identity), expectedNetworkGeneration = 7L, networkGeneration = 8L, database = db
            ))
            assertFalse(DownloadExecutionRoomStore.read(context, request.operationId, db)!!.requiresWifiNetwork)
            assertEquals(0, DownloadExecutionRoomStore.allowBatchesMobileData(
                context, listOf(identity.copy(generation = identity.generation + 1)),
                expectedNetworkGeneration = 7L, networkGeneration = 8L, database = db
            ))
            db.downloadBatchDao().markAllOpenBatchesCancelled(9000L)
            assertEquals(0, DownloadExecutionRoomStore.allowBatchesMobileData(
                context, listOf(identity), expectedNetworkGeneration = 7L, networkGeneration = 8L, database = db
            ))
        }
    }

    @Test
    fun invalidCoreResetKeepsOrderAndRejectsStaleOrCancelledOwners() = runBlocking {
        withDatabase { db ->
            seed(db, "invalid-core", 9, "ASSETS_ENRICHING", bytes = 999)
            val dao = db.downloadOperationDao()
            val old = dao.find("invalid-core")!!
            assertEquals(0, dao.resetInvalidCoreForTransfer(
                old.operationId, old.stableKey, old.updatedAtMs - 1, "CORE_AUDIO_IDENTITY_MISMATCH", 2000L
            ))
            assertEquals(1, dao.resetInvalidCoreForTransfer(
                old.operationId, old.stableKey, old.updatedAtMs, "CORE_AUDIO_IDENTITY_MISMATCH", 2000L
            ))
            val reset = dao.find(old.operationId)!!
            assertEquals(old.queueOrder, reset.queueOrder)
            assertEquals(old.createdAtMs, reset.createdAtMs)
            assertEquals(0L, reset.bytesWritten)
            assertNull(reset.resumeJson)
            assertEquals("RETRYABLE", reset.state)
            dao.upsert(old.copy(stopRequestedByUser = true))
            assertEquals(0, dao.resetInvalidCoreForTransfer(
                old.operationId, old.stableKey, old.updatedAtMs, "CORE_AUDIO_IDENTITY_MISMATCH", 3000L
            ))
        }
    }

    private suspend fun page(db: NeriUserDataDatabase, limit: Int = 10) =
        DownloadExecutionRoomReadStore.listSchedulableForPumpPage(context, null, limit, db, nowMs = 1000L)

    @Test
    fun rejectedCoreCannotBecomeReusableAfterRestartOrStaleRequestRefresh() = runBlocking {
        val name = "invalid-core-recovery-${UUID.randomUUID()}"
        var db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        try {
            seed(db, "invalid-core", 4, "CORE_COMMITTED", bytes = 3131565L)
            val oldRequest = requireNotNull(DownloadExecutionRoomStore.read(context, "invalid-core", db))
            val oldHeader = requireNotNull(db.downloadOperationDao().find("invalid-core"))
            db.managedDownloadArtifactDao().upsert(ManagedDownloadArtifactEntity(
                rootKey = oldHeader.libraryId, stableKey = oldHeader.stableKey, artifactId = "artifact",
                state = "CORE_COMMITTED", leaseId = oldRequest.artifactLeaseId,
                audioReference = "content://test/known-invalid.mp3", audioName = "known-invalid.mp3"
            ))
            assertNull(resetInvalidCoreDownloadForTransfer(context, oldRequest.song, oldRequest.operationId,
                "content://test/known-invalid.mp3", "stale-lease", "CORE_AUDIO_DURATION_MISMATCH", db))
            val reset = requireNotNull(resetInvalidCoreDownloadForTransfer(
                context, oldRequest.song, oldRequest.operationId, "content://test/known-invalid.mp3",
                oldRequest.artifactLeaseId, "CORE_AUDIO_DURATION_MISMATCH", db
            ))
            assertFalse(reset.retryExhausted)
            assertTrue(reset.request.requiresFreshTransfer)
            assertFalse(reset.request.preserveStaging)
            db.close()
            db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
            DownloadExecutionRoomStore.upsert(context, oldRequest, "RETRYABLE", database = db)
            val reloaded = requireNotNull(DownloadExecutionRoomStore.read(context, oldRequest.operationId, db))
            assertTrue(reloaded.requiresFreshTransfer)
            assertEquals(oldRequest.artifactLeaseId, reloaded.artifactLeaseId)
            val after = requireNotNull(db.downloadOperationDao().find(oldRequest.operationId))
            assertEquals(oldHeader.queueOrder, after.queueOrder)
            assertEquals(oldHeader.createdAtMs, after.createdAtMs)
            assertEquals(1, after.retryCount)
            assertNull(db.managedDownloadArtifactDao().find(oldHeader.libraryId, oldHeader.stableKey)?.audioReference)
            assertTrue(DownloadExecutionRoomStore.prepareExplicitResume(
                context, oldRequest.operationId, oldHeader.stableKey, database = db
            ))
            assertTrue(DownloadExecutionRoomStore.read(context, oldRequest.operationId, db)!!.requiresFreshTransfer)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun seed(
        db: NeriUserDataDatabase, id: String, order: Int, state: String = "QUEUED",
        bytes: Long = 0, retryAt: Long? = null, error: String? = null
    ) {
        DownloadExecutionRoomStore.upsert(
            context, DownloadExecutionRequest(operationId = id, song = song(id)), state,
            queueOrder = order, createdAtMs = 100L, database = db
        )
        val row = db.downloadOperationDao().find(id)!!
        db.downloadOperationDao().upsert(row.copy(bytesWritten = bytes, nextRetryAtMs = retryAt, lastErrorCode = error))
    }

    private fun song(id: String) = SongItem(
        id = id.hashCode().toLong().let { if (it < 0) -it else it } + 1,
        name = id, artist = "artist", album = "netease", albumId = 0, durationMs = 1000, coverUrl = null
    )

    private suspend fun withDatabase(block: suspend (NeriUserDataDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }
}
