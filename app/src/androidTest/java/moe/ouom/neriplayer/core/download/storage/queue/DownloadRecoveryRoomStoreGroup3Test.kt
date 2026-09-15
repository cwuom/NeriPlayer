package moe.ouom.neriplayer.core.download.storage.queue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.DOWNLOAD_RETRY_BASE_DELAY_MS
import moe.ouom.neriplayer.core.download.execution.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)

class DownloadRecoveryRoomStoreGroup3Test : DownloadRecoveryRoomStoreTestSupport() {

    @Test
    fun commitBoundaryCancellationBlocksRecoveryButAllowsCoreCommit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val operationId = "cancel-at-commit-90"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song(90L, "cancel-at-commit"),
                    userInitiated = true
                ),
                state = "COMMITTING",
                database = database
            )

            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = operationId,
                    database = database
                )
            )
            assertEquals(true, database.downloadOperationDao().isUserStopped(operationId))
            assertEquals("COMMITTING", database.downloadOperationDao().find(operationId)?.state)
            assertEquals(
                1,
                database.downloadOperationDao().markCoreCommitted(
                    operationId = operationId,
                    expectedStates = listOf("COMMITTING"),
                    updatedAtMs = 2L
                )
            )
            assertEquals("CORE_COMMITTED", database.downloadOperationDao().find(operationId)?.state)
            assertEquals(true, database.downloadOperationDao().isUserStopped(operationId))
            assertFalse(
                DownloadExecutionRoomStore.updateState(
                    context = context,
                    operationId = operationId,
                    state = "RETRYABLE",
                    errorCode = "LATE_HOST_CANCEL",
                    database = database
                )
            )
            assertFalse(
                DownloadExecutionRoomStore.tryStart(
                    context = context,
                    operationId = operationId,
                    allowExistingRunning = true,
                    database = database
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun clearCancellationSurvivesCoreCommitAndCannotBecomeAnExplicitResume() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val operationId = "clear-at-commit-92"
            val song = song(92L, "clear-at-commit")
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    userInitiated = true
                ),
                state = "COMMITTING",
                database = database
            )

            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = operationId,
                    database = database
                )
            )
            assertEquals(
                1,
                database.downloadOperationDao().markCoreCommitted(
                    operationId = operationId,
                    expectedStates = listOf("COMMITTING"),
                    updatedAtMs = 2L
                )
            )
            assertTrue(
                database.downloadOperationDao().isUserCancellationRequested(operationId)
            )
            assertFalse(
                DownloadExecutionRoomStore.prepareExplicitResume(
                    context = context,
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    database = database
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun freshQueueReplacesUserCancelledCoreCommittedOperation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(93L, "fresh-after-clear")
            val cancelledOperationId = "clear-at-commit-93"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = cancelledOperationId,
                    song = song,
                    userInitiated = true
                ),
                state = "COMMITTING",
                database = database
            )
            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = cancelledOperationId,
                    database = database
                )
            )
            assertEquals(
                1,
                database.downloadOperationDao().markCoreCommitted(
                    operationId = cancelledOperationId,
                    expectedStates = listOf("COMMITTING"),
                    updatedAtMs = 2L
                )
            )

            val freshOperationId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song),
                    userInitiated = true
                )
                .single()

            assertTrue(freshOperationId != cancelledOperationId)
            assertEquals(
                "CORE_COMMITTED",
                database.downloadOperationDao().find(cancelledOperationId)?.state
            )
            assertTrue(
                database.downloadOperationDao()
                    .isUserCancellationRequested(cancelledOperationId)
            )
            assertEquals(
                "QUEUED",
                database.downloadOperationDao().find(freshOperationId)?.state
            )
            assertEquals(false, database.downloadOperationDao().isUserStopped(freshOperationId))
        } finally {
            database.close()
        }
    }

    @Test
    fun freshQueueKeepsHostStoppedInFlightOperationReadable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(94L, "host-stopped")
            val operationId = "host-stopped-94"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    userInitiated = true
                ),
                state = "COMMITTING",
                database = database
            )
            database.downloadOperationDao().requestUserStop(
                operationId = operationId,
                updatedAtMs = 2L
            )
            database.downloadOperationDao().updateState(
                operationId = operationId,
                state = "COMMITTING",
                updatedAtMs = 3L,
                errorCode = "HOST_STOPPED"
            )

            val selectedOperationId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song),
                    userInitiated = true
                )
                .single()

            assertEquals(operationId, selectedOperationId)
        } finally {
            database.close()
        }
    }

    @Test
    fun staleScheduleRefreshCannotReopenCancelledOperation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val operationId = "cancelled-schedule-91"
            val request = DownloadExecutionRequest(
                operationId = operationId,
                song = song(91L, "cancelled-schedule"),
                userInitiated = true
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "QUEUED",
                database = database
            )
            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = operationId,
                    database = database
                )
            )

            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request.copy(attemptId = 99L),
                state = "QUEUED",
                database = database
            )

            assertEquals(
                "CANCEL_REQUESTED",
                database.downloadOperationDao().find(operationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRefresh_reusesRunningOperationWithoutCreatingQueuedDuplicate() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(8L, "running-operation")
            val operationId = "running-operation-8"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    userInitiated = true
                ),
                state = "RUNNING",
                database = database
            )

            val store = DownloadRecoveryRoomStore(context, database)
            val refreshedOperationIds = listOf(
                store.upsertPendingDownloadQueue(
                    songs = listOf(song.copy(name = "duplicate-request")),
                    userInitiated = true
                ).single(),
                store.upsertPendingDownloadQueue(
                    songs = listOf(song.copy(name = "second-single-request")),
                    userInitiated = true
                ).single(),
                store.upsertPendingDownloadQueue(
                    songs = listOf(
                        song.copy(name = "batch-request"),
                        song.copy(name = "batch-duplicate")
                    ),
                    userInitiated = true
                ).single()
            )

            assertEquals(listOf(operationId, operationId, operationId), refreshedOperationIds)
            assertEquals(1, database.downloadOperationDao().findAll().size)
            assertEquals(
                "RUNNING",
                database.downloadOperationDao().find(operationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun roomOperationRefreshPersistsPreparedAttemptId() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(9L, "prepared-attempt")
            val operationId = "prepared-attempt-9"
            val queuedRequest = DownloadExecutionRequest(
                operationId = operationId,
                song = song,
                userInitiated = true
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = queuedRequest,
                state = "QUEUED",
                database = database
            )

            DownloadExecutionRoomStore.upsert(
                context = context,
                request = queuedRequest.copy(attemptId = 19L),
                state = "QUEUED",
                database = database
            )

            assertEquals(
                19L,
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.attemptId
            )
            assertEquals(
                "QUEUED",
                database.downloadOperationDao().find(operationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun explicitResumeAtomicallyClearsStopAndRestoresItAfterHostRejection() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(10L, "explicit-resume")
            val operationId = "explicit-resume-10"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    preserveStaging = true,
                    userInitiated = true
                ),
                state = "STOPPED",
                database = database
            )
            database.downloadOperationDao().requestUserStop(
                operationId = operationId,
                updatedAtMs = 2L
            )

            assertTrue(
                DownloadExecutionRoomStore.prepareExplicitResume(
                    context = context,
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    database = database
                )
            )
            assertEquals("RETRYABLE", database.downloadOperationDao().find(operationId)?.state)
            assertEquals(false, database.downloadOperationDao().isUserStopped(operationId))
            assertEquals(
                operationId,
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.operationId
            )

            assertTrue(
                DownloadExecutionRoomStore.restoreExplicitStop(
                    context = context,
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    errorCode = "HOST_REJECTED",
                    database = database
                )
            )
            assertEquals("STOPPED", database.downloadOperationDao().find(operationId)?.state)
            assertEquals(true, database.downloadOperationDao().isUserStopped(operationId))
            assertEquals(
                "HOST_REJECTED",
                database.downloadOperationDao().find(operationId)?.lastErrorCode
            )

            assertEquals(
                1,
                DownloadExecutionRoomStore.prepareExplicitResumesForStableKeys(
                    context = context,
                    stableKeys = setOf(song.stableKey()),
                    database = database
                )
            )
            assertEquals("RETRYABLE", database.downloadOperationDao().find(operationId)?.state)
            assertEquals(false, database.downloadOperationDao().isUserStopped(operationId))
        } finally {
            database.close()
        }
    }

    @Test
    fun clearAllStopsDegradedCompleteOperationBeforeHostRecovery() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(11L, "degraded-clear")
            val operationId = "degraded-clear-11"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    userInitiated = true
                ),
                state = "DEGRADED_COMPLETE",
                database = database
            )

            val snapshot = DownloadExecutionRoomStore.requestCancelAll(
                context = context,
                database = database
            )

            assertTrue(snapshot.operationIds.contains(operationId))
            assertEquals(true, database.downloadOperationDao().isUserStopped(operationId))
            assertEquals(
                "USER_CANCELLED",
                database.downloadOperationDao().find(operationId)?.lastErrorCode
            )
            assertFalse(
                DownloadExecutionRoomStore.tryStart(
                    context = context,
                    operationId = operationId,
                    allowExistingRunning = true,
                    database = database
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun malformedLegacyFileDoesNotPromoteRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        queueFile.writeText("{malformed")

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            store.bootstrapLegacyFilesOnce()
            assertTrue(store.listPendingQueuedDownloads().isEmpty())
            assertEquals(
                null,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY
                    )
            )
            assertTrue(queueFile.exists())
        } finally {
            queueFile.delete()
            database.close()
        }
    }

    @Test
    fun orphaned_legacy_cancel_marker_does_not_create_synthetic_operation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val cancelledFile = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        cancelledFile.writeText(
            ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(
                songKeys = setOf("orphan-stable-key"),
                updatedAtMs = 50L
            )
        )

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            store.bootstrapLegacyFilesOnce()
            assertTrue(database.downloadOperationDao().findAll().isEmpty())
        } finally {
            cancelledFile.delete()
            database.close()
        }
    }

    @Test
    fun clearFinalizationAndPhysicalPurgeChunkMoreThanSqliteBindLimit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val libraryId = currentLibraryId(context)
            val operationIds = (0 until LARGE_OPERATION_COUNT).map { index ->
                "large-clear-operation-$index"
            }
            val dao = database.downloadOperationDao()
            operationIds.forEachIndexed { index, operationId ->
                dao.upsert(
                    largeCancelledOperation(
                        operationId = operationId,
                        stableKey = "large-clear-key-$index",
                        libraryId = libraryId
                    )
                )
                assertEquals(
                    1,
                    dao.setHostAdmission(
                        operationId = operationId,
                        processToken = "test-process",
                        admittedAtMs = index.toLong()
                    )
                )
            }

            assertEquals(
                LARGE_OPERATION_COUNT,
                DownloadExecutionRoomStore.finalizeRequestedCancellations(
                    context = context,
                    operationIds = operationIds,
                    database = database
                )
            )
            assertTrue(
                dao.findAll().all { operation -> operation.state == "CANCELLED" }
            )
            assertEquals(
                LARGE_OPERATION_COUNT,
                DownloadExecutionRoomStore.purgeFullyClearedOperations(
                    context = context,
                    operationIds = operationIds,
                    database = database
                )
            )
            assertTrue(dao.findAll().isEmpty())
            assertTrue(operationIds.none { operationId ->
                dao.find(operationId)?.hostProcessToken != null
            })
        } finally {
            database.close()
        }
    }

    @Test
    fun cancelledKeyPurgeChunksMoreThanSqliteBindLimit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val libraryId = currentLibraryId(context)
            val stableKeys = (0 until LARGE_OPERATION_COUNT).map { index ->
                "large-cancel-key-$index"
            }
            val dao = database.downloadOperationDao()
            stableKeys.forEachIndexed { index, stableKey ->
                dao.upsert(
                    largeCancelledOperation(
                        operationId = "large-cancel-operation-$index",
                        stableKey = stableKey,
                        state = "CANCELLED",
                        libraryId = libraryId
                    )
                )
            }

            DownloadExecutionRoomStore.purgeCancelled(
                context = context,
                stableKeys = stableKeys,
                database = database
            )

            assertTrue(dao.findAll().isEmpty())
        } finally {
            database.close()
        }
    }
}
