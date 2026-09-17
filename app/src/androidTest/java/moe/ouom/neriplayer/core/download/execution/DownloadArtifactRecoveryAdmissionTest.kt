package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.recovery.isArtifactRecoveryAllowed
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadArtifactRecoveryAdmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val song = SongItem(
        id = 991L, name = "recovery-admission", artist = "artist",
        album = "Netease", albumId = 1L, durationMs = 180_000L, coverUrl = null
    )

    @Test
    fun clearedCoreCancellationSurvivesDatabaseReopenAndAllowsNewRequest() = runTest {
        val name = "download-recovery-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            val old = DownloadExecutionRequest(operationId = "old-core", song = song)
            DownloadExecutionRoomStore.upsert(context, old, "CORE_COMMITTED", database = database)
            assertTrue(DownloadExecutionRoomStore.requestCancel(context, old.operationId, database))
            assertEquals(0, DownloadExecutionRoomStore.purgeFullyClearedOperations(
                context, listOf(old.operationId), database
            ))
            assertNotNull(database.downloadOperationDao().findHeader(old.operationId))
            database.close()
            database = open()
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, old.operationId, database = database
            ))
            val replacement = DownloadExecutionRequest(operationId = "new-core", song = song)
            DownloadExecutionRoomStore.upsert(context, replacement, "CORE_COMMITTED", database = database)
            assertTrue(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, replacement.operationId, database = database
            ))
            val progress = DownloadExecutionRoomStore.listProgressEntriesAnyLibrary(context, database)
            assertTrue(progress.any { it.request.operationId == replacement.operationId })
            assertTrue(progress.first { it.request.operationId == old.operationId }.stopRequestedByUser)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun cancelledBatchBlocksLegacyOrphanAfterOperationWasDeleted() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(
                context, listOf(song), database = database, nowMs = 1L
            )
            val request = DownloadExecutionRequest(
                operationId = "legacy-orphan", song = song, attemptId = 1L,
                batchId = batch.batchId, batchGeneration = batch.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "CORE_COMMITTED", database = database)
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, listOf(request), database = database
            ))
            val capture = DownloadExecutionRoomStore.beginBatchClear(context, 1L, database)
            database.downloadOperationDao().delete(request.operationId)
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database
            ))
            assertTrue(DownloadExecutionRoomStore.finalizeBatchClear(
                context, capture.identities, database
            ))
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database
            ))
            assertTrue(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, "unrelated-orphan", database = database
            ))
        } finally {
            database.close()
        }
    }

    @Test
    fun directoryRecoveryRespectsPersistentRetryDeadlineAndExhaustion() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val batch = DownloadExecutionRoomStore.createBatchSnapshot(
                context, listOf(song), database = database, nowMs = 1L
            )
            val request = DownloadExecutionRequest(
                operationId = "retry-core", song = song,
                batchId = batch.batchId, batchGeneration = batch.generation
            )
            DownloadExecutionRoomStore.upsert(context, request, "CORE_COMMITTED", database = database)
            assertEquals(1, DownloadExecutionRoomStore.attachBatchIdentity(
                context, batch, listOf(request), database = database
            ))
            val retry = requireNotNull(DownloadExecutionRoomStore.recordPostCoreRetryFailure(
                context = context, operationId = request.operationId, stableKey = song.stableKey(),
                expectedAttemptId = null, errorCode = "TEST_FAILURE", database = database, nowMs = 10L
            ))
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database, nowMs = retry.nextRetryAtMs - 1L
            ))
            assertTrue(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database, nowMs = retry.nextRetryAtMs
            ))
            assertTrue(DownloadExecutionRoomStore.markPostCoreRetryExhausted(
                context = context, operationId = request.operationId, stableKey = song.stableKey(),
                expectedAttemptId = null, minimumRetryCount = 1,
                errorCode = "POST_CORE_RETRY_EXHAUSTED", database = database
            ))
            assertEquals("INVALID", DownloadExecutionRoomStore.listProgressEntriesAnyLibrary(
                context, database
            ).single().state)
            assertTrue(requireNotNull(database.downloadBatchDao().findBatchById(batch.batchId))
                .stateBits and DownloadBatchState.COMPLETED != 0)
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database, nowMs = Long.MAX_VALUE
            ))
            DownloadExecutionRoomStore.purgeFullyClearedOperations(context, listOf(request.operationId), database)
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, request.operationId, database = database
            ))
        } finally {
            database.close()
        }
    }

    @Test
    fun ordinaryCancelledTransferIsStillPurged() = runTest {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(operationId = "cancelled-transfer", song = song)
            DownloadExecutionRoomStore.upsert(context, request, "CANCELLED", database = database)
            assertEquals(1, DownloadExecutionRoomStore.purgeFullyClearedOperations(
                context, listOf(request.operationId), database
            ))
            assertTrue(database.downloadOperationDao().findAll().isEmpty())
        } finally {
            database.close()
        }
    }
}
