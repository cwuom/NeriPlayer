package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.recovery.isArtifactRecoveryAllowed
import moe.ouom.neriplayer.core.download.policy.recoveredDownloadTaskPresentation
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadTaskClearHistoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun invalidFailureDoesNotReturnAfterTaskClearAndDatabaseReopen() = runBlocking {
        verifyFailureClear("INVALID", completedBatch = false)
    }

    @Test
    fun metadataActionFailureDoesNotReturnAfterTaskClearAndDatabaseReopen() = runBlocking {
        verifyFailureClear("METADATA_ACTION_REQUIRED", completedBatch = false)
    }

    @Test
    fun completedBatchFailureDoesNotReturnAfterTaskClearAndDatabaseReopen() = runBlocking {
        verifyFailureClear("INVALID", completedBatch = true)
    }

    private suspend fun verifyFailureClear(state: String, completedBatch: Boolean) {
        val databaseName = "download-task-clear-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        var database = open()
        try {
            val song = SongItem(
                id = 8_221L, name = "clear failed history", artist = "artist",
                album = "Netease", albumId = 1L, durationMs = 1_000L, coverUrl = null
            )
            val batch = if (completedBatch) {
                DownloadExecutionRoomStore.createBatchSnapshot(
                    context, listOf(song), database = database, nowMs = 1L
                )
            } else null
            val failed = DownloadExecutionRequest(
                operationId = "failed-history", song = song, attemptId = 1L,
                batchId = batch?.batchId, batchGeneration = batch?.generation
            )
            DownloadExecutionRoomStore.upsert(context, failed, state, database = database)
            if (batch != null) {
                DownloadExecutionRoomStore.attachBatchIdentity(
                    context, batch, listOf(failed), database = database
                )
                DownloadExecutionRoomStore.markBatchMembersForOperation(
                    context, failed.operationId, song.stableKey(), failed.attemptId,
                    DownloadBatchMemberTerminal.FAILED, database = database
                )
                val completed = requireNotNull(database.downloadBatchDao().findBatchById(batch.batchId))
                assertTrue(completed.stateBits and DownloadBatchState.COMPLETED != 0)
            }
            assertEquals(listOf("failed-history"), restoredTaskOperationIds(database))
            val finalized = failed.copy(operationId = "completed-audio", song = song.copy(id = 8_222L),
                batchId = null, batchGeneration = null)
            DownloadExecutionRoomStore.upsert(context, finalized, "FINALIZED", database = database)

            val captured = DownloadExecutionRoomStore.listCancellationIdentitiesAnyLibrary(context, database)
            assertEquals(setOf("failed-history"), captured.map { it.operationId }.toSet())
            assertTrue(DownloadExecutionRoomStore.listCancellationCandidatesAnyLibrary(context, database).isEmpty())

            val replacement = failed.copy(operationId = "replacement-request", attemptId = 2L,
                batchId = null, batchGeneration = null)
            DownloadExecutionRoomStore.upsert(context, replacement, "QUEUED", database = database)
            val operationIds = captured.map { it.operationId }
            DownloadExecutionRoomStore.requestCancelOperations(context, operationIds, database)
            DownloadExecutionRoomStore.finalizeRequestedCancellations(context, operationIds, database)
            DownloadExecutionRoomStore.purgeFullyClearedOperations(context, operationIds, database)

            database.close()
            database = open()
            assertEquals(listOf("replacement-request"), restoredTaskOperationIds(database))
            val cleared = requireNotNull(database.downloadOperationDao().findHeader(failed.operationId))
            assertTrue(cleared.stopRequestedByUser)
            assertEquals("USER_CANCELLED", cleared.lastErrorCode)
            assertFalse(DownloadExecutionRoomStore.isArtifactRecoveryAllowed(
                context, failed.operationId, database = database
            ))
            assertEquals("FINALIZED", database.downloadOperationDao().findHeader(finalized.operationId)?.state)
            assertEquals("QUEUED", database.downloadOperationDao().findHeader(replacement.operationId)?.state)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun restoredTaskOperationIds(database: NeriUserDataDatabase): List<String> {
        return DownloadExecutionRoomStore.listProgressEntriesAnyLibrary(context, database)
            .filter { entry ->
                recoveredDownloadTaskPresentation(
                    operationState = entry.state,
                    stopRequestedByUser = entry.stopRequestedByUser,
                    batchStateBits = entry.batchStateBits
                ) != null
            }
            .map { it.request.operationId }
    }
}
