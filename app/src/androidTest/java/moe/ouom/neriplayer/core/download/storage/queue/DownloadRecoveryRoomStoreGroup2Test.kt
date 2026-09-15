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

class DownloadRecoveryRoomStoreGroup2Test : DownloadRecoveryRoomStoreTestSupport() {

    @Test
    fun queueRefresh_preservesRetryableOperationAfterHostPause() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(7L, "paused-operation")
            val operationId = "paused-operation-7"
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
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = "RETRYABLE",
                errorCode = "HOST_STOPPED",
                database = database
            )

            val refreshedOperationId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song.copy(name = "paused-updated")),
                    userInitiated = true
                )
                .single()

            assertEquals(operationId, refreshedOperationId)
            assertEquals(
                "paused-updated",
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.song?.name
            )
            assertEquals(1, database.downloadOperationDao().findAll().size)
            assertEquals(
                "RETRYABLE",
                database.downloadOperationDao().find(operationId)?.state
            )
            assertEquals(
                listOf(operationId),
                DownloadRecoveryRoomStore(context, database)
                    .listPendingQueuedDownloads()
                    .map { entry -> entry.operationId }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun cancellationCandidatesIncludeRetryableAndStoppedOperationsWithoutMemoryTasks() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val retryableSong = song(74L, "retryable-cancel")
            val stoppedSong = song(75L, "stopped-cancel")
            val retryableOperationId = "retryable-cancel-74"
            val stoppedOperationId = "stopped-cancel-75"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = retryableOperationId,
                    song = retryableSong,
                    userInitiated = true
                ),
                state = "RETRYABLE",
                database = database
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = stoppedOperationId,
                    song = stoppedSong,
                    userInitiated = true
                ),
                state = "STOPPED",
                database = database
            )
            database.downloadOperationDao().requestUserStop(
                operationId = stoppedOperationId,
                updatedAtMs = 2L
            )

            assertEquals(
                setOf(retryableOperationId, stoppedOperationId),
                DownloadExecutionRoomStore.listCancellationCandidates(
                    context = context,
                    database = database
                ).mapTo(linkedSetOf()) { entry -> entry.request.operationId }
            )
            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = retryableOperationId,
                    database = database
                )
            )
            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = stoppedOperationId,
                    database = database
                )
            )
            assertEquals(
                "CANCEL_REQUESTED",
                database.downloadOperationDao().find(retryableOperationId)?.state
            )
            assertEquals(
                "CANCEL_REQUESTED",
                database.downloadOperationDao().find(stoppedOperationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun clearAllFinalizesRequestedCancellationWithoutReopeningTheOperation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(76L, "clear-finalized")
            val operationId = "clear-finalized-76"
            val request = DownloadExecutionRequest(
                operationId = operationId,
                song = song,
                userInitiated = true
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "QUEUED",
                database = database
            )

            val snapshot = DownloadExecutionRoomStore.requestCancelAll(
                context = context,
                database = database
            )

            assertEquals("CANCEL_REQUESTED", database.downloadOperationDao().find(operationId)?.state)
            assertEquals(
                1,
                DownloadExecutionRoomStore.finalizeRequestedCancellations(
                    context = context,
                    operationIds = snapshot.operationIds,
                    database = database
                )
            )
            assertEquals("CANCELLED", database.downloadOperationDao().find(operationId)?.state)
            assertFalse(
                DownloadExecutionRoomStore.tryStart(
                    context = context,
                    operationId = operationId,
                    allowExistingRunning = true,
                    database = database
                )
            )

            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "RUNNING",
                database = database
            )
            assertEquals("CANCELLED", database.downloadOperationDao().find(operationId)?.state)
            assertTrue(
                DownloadExecutionRoomStore.requestCancelAll(
                    context = context,
                    database = database
                ).operationIds.isEmpty()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun clearAllCancellationDrainsPagesWithoutReselectingRequestedRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val operationIds = List(130) { index ->
                val song = song(76_000L + index, "clear-page-$index")
                val operationId = "clear-page-$index"
                DownloadExecutionRoomStore.upsert(
                    context = context,
                    request = DownloadExecutionRequest(
                        operationId = operationId,
                        song = song,
                        userInitiated = true
                    ),
                    state = "QUEUED",
                    database = database
                )
                operationId
            }
            val alreadyRequestedOperationId = "clear-page-already-requested"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = alreadyRequestedOperationId,
                    song = song(76_500L, "clear-page-already-requested"),
                    userInitiated = true
                ),
                state = "QUEUED",
                database = database
            )
            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = alreadyRequestedOperationId,
                    database = database
                )
            )

            val snapshot = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(15_000L) {
                    DownloadExecutionRoomStore.requestCancelAll(
                        context = context,
                        database = database
                    )
                }
            }

            assertEquals(operationIds.toSet(), snapshot.operationIds.toSet())
            operationIds.forEach { operationId ->
                assertEquals(
                    "CANCEL_REQUESTED",
                    database.downloadOperationDao().find(operationId)?.state
                )
            }
            assertEquals(
                "CANCEL_REQUESTED",
                database.downloadOperationDao().find(alreadyRequestedOperationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun waitingStorageMutationIsHiddenAndPromotesOnlyAfterAnAtomicCheck() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(77L, "storage-mutation-wait")
            val store = DownloadRecoveryRoomStore(context, database)

            val firstOperationId = store.upsertWaitingStorageMutation(
                songs = listOf(song),
                nowMs = 100L,
                userInitiated = true
            ).single()
            val secondOperationId = store.upsertWaitingStorageMutation(
                songs = listOf(song),
                nowMs = 200L,
                userInitiated = true
            ).single()

            assertEquals(firstOperationId, secondOperationId)
            assertTrue(store.listPendingQueuedDownloads().isEmpty())
            assertEquals(
                listOf(firstOperationId),
                store.listWaitingStorageMutations().map { entry -> entry.request.operationId }
            )
            val competingOperationId = "queued-during-storage-mutation-wait"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = competingOperationId,
                    song = song,
                    userInitiated = true
                ),
                state = "QUEUED",
                database = database
            )
            assertFalse(
                DownloadExecutionRoomStore.tryAcquireHostAdmission(
                    context = context,
                    operationId = competingOperationId,
                    capacity = 1,
                    database = database
                )
            )
            assertFalse(
                DownloadExecutionRoomStore.tryStart(
                    context = context,
                    operationId = competingOperationId,
                    allowExistingRunning = true,
                    database = database
                )
            )
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(firstOperationId)?.state
            )
            assertEquals(
                "QUEUED",
                database.downloadOperationDao().find(competingOperationId)?.state
            )
            val previousLibraryId = "previous-download-root"
            val waitingEntity = requireNotNull(
                database.downloadOperationDao().find(firstOperationId)
            )
            database.downloadOperationDao().upsert(
                waitingEntity.copy(libraryId = previousLibraryId)
            )
            assertFalse(
                store.promoteWaitingStorageMutation(
                    operationId = firstOperationId,
                    stableKey = "other:${song.id}"
                )
            )
            assertTrue(
                store.promoteWaitingStorageMutation(
                    operationId = firstOperationId,
                    stableKey = song.stableKey()
                )
            )
            assertEquals(
                "QUEUED",
                database.downloadOperationDao().find(firstOperationId)?.state
            )
            assertEquals(
                currentLibraryId(context),
                database.downloadOperationDao().find(firstOperationId)?.libraryId
            )
            assertTrue(store.listWaitingStorageMutations().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun runningOperationDefersDurablyUntilStorageMutationPromotion() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(78L, "storage-mutation-running")
            val operationId = "running-storage-mutation-operation"
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

            assertTrue(
                DownloadExecutionRoomStore.markWaitingForStorageMutation(
                    context = context,
                    operationId = operationId,
                    errorCode = "DIRECTORY_CHANGE_IN_PROGRESS",
                    database = database
                )
            )
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(operationId)?.state
            )
            assertEquals(
                "DIRECTORY_CHANGE_IN_PROGRESS",
                database.downloadOperationDao().find(operationId)?.lastErrorCode
            )

            assertTrue(
                DownloadExecutionRoomStore.promoteWaitingStorageMutation(
                    context = context,
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    database = database
                )
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
    fun terminalWaitingOperationGetsANewIdWithoutBlockingTheRestOfTheBatch() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val redownloadedSong = song(80L, "terminal-storage-mutation")
            val secondPlaylistSong = song(81L, "second-playlist-storage-mutation")
            val store = DownloadRecoveryRoomStore(context, database)
            val completedOperationId = store.upsertWaitingStorageMutation(
                songs = listOf(redownloadedSong),
                nowMs = 100L,
                userInitiated = true
            ).single()
            assertTrue(
                store.promoteWaitingStorageMutation(
                    operationId = completedOperationId,
                    stableKey = redownloadedSong.stableKey()
                )
            )
            assertEquals(
                1,
                database.downloadOperationDao().transitionState(
                    operationId = completedOperationId,
                    expectedStates = listOf("QUEUED"),
                    state = "COMPLETED",
                    updatedAtMs = 200L,
                    errorCode = null
                )
            )

            val waitingOperationIds = store.upsertWaitingStorageMutation(
                songs = listOf(redownloadedSong, secondPlaylistSong),
                nowMs = 300L,
                userInitiated = true
            )
            val operationIdsBySongKey = waitingOperationIds.associateBy { operationId ->
                requireNotNull(
                    DownloadExecutionRoomStore.read(
                        context = context,
                        operationId = operationId,
                        database = database
                    )
                ).song.stableKey()
            }
            val replacementOperationId = requireNotNull(
                operationIdsBySongKey[redownloadedSong.stableKey()]
            )

            assertEquals(2, waitingOperationIds.size)
            assertNotEquals(completedOperationId, replacementOperationId)
            assertEquals(
                "COMPLETED",
                database.downloadOperationDao().find(completedOperationId)?.state
            )
            waitingOperationIds.forEach { operationId ->
                val request = requireNotNull(
                    DownloadExecutionRoomStore.read(
                        context = context,
                        operationId = operationId,
                        database = database
                    )
                )
                assertEquals(
                    WAITING_STORAGE_MUTATION_OPERATION_STATE,
                    database.downloadOperationDao().find(operationId)?.state
                )
                assertTrue(
                    store.promoteWaitingStorageMutation(
                        operationId = operationId,
                        stableKey = request.song.stableKey()
                    )
                )
            }
            listOf(redownloadedSong, secondPlaylistSong).forEach { song ->
                assertEquals(
                    1,
                    database.downloadOperationDao().findAll().count { entity ->
                        entity.stableKey == song.stableKey() &&
                            entity.state in listOf(
                                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                                "QUEUED"
                            )
                    }
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun forcedReplacementKeepsAFreshRequestWhenThePredecessorIsStillRunning() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(805L, "forced-replacement")
            val predecessorId = "forced-replacement-old"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = predecessorId,
                    song = song,
                    userInitiated = true
                ),
                state = "RUNNING",
                database = database
            )

            val result = DownloadRecoveryRoomStore(context, database)
                .upsertWaitingStorageMutationWithRequests(
                    songs = listOf(song),
                    userInitiated = true,
                    excludedOperationIds = setOf(predecessorId),
                    forceNewOperationForStableKeys = setOf(song.stableKey())
                )
            val replacementId = result.operationIds.single()

            assertNotEquals(predecessorId, replacementId)
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(replacementId)?.state
            )
            assertEquals(
                "RUNNING",
                database.downloadOperationDao().find(predecessorId)?.state
            )
            assertEquals(song.stableKey(), result.requestsByOperationId[replacementId]
                ?.song
                ?.stableKey())
        } finally {
            database.close()
        }
    }

    @Test
    fun forcedReplacementDoesNotReuseAnOlderWaitingLease() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(807L, "forced-replacement-waiting")
            val predecessorId = "forced-replacement-waiting-old"
            val predecessorLeaseId = "forced-replacement-waiting-lease"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = predecessorId,
                    song = song,
                    preserveStaging = true,
                    attemptId = 42L,
                    artifactLeaseId = predecessorLeaseId,
                    userInitiated = true
                ),
                state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
                queueOrder = 3,
                createdAtMs = 100L,
                database = database
            )

            val result = DownloadRecoveryRoomStore(context, database)
                .upsertWaitingStorageMutationWithRequests(
                    songs = listOf(song),
                    nowMs = 200L,
                    userInitiated = true,
                    forceNewOperationForStableKeys = setOf(song.stableKey())
                )
            val replacementId = result.operationIds.single()
            val replacementRequest = checkNotNull(result.requestsByOperationId[replacementId])
            val replacement = database.downloadOperationDao().find(replacementId)

            assertNotEquals(predecessorId, replacementId)
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                replacement?.state
            )
            assertFalse(replacementRequest.preserveStaging)
            assertEquals(null, replacementRequest.attemptId)
            assertNotEquals(predecessorLeaseId, replacementRequest.artifactLeaseId)
            assertTrue(replacement?.queueOrder ?: 0 > 3)
            assertTrue(replacement?.createdAtMs ?: 0L >= 200L)
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(predecessorId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun cancellationBoundaryMarksOnlyThePredecessorAcrossLibraries() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(806L, "cancellation-boundary")
            val predecessorId = "cancellation-boundary-old"
            val replacementId = "cancellation-boundary-new"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = predecessorId,
                    song = song,
                    userInitiated = true
                ),
                state = "RUNNING",
                createdAtMs = 100L,
                database = database
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = replacementId,
                    song = song,
                    userInitiated = true
                ),
                state = "QUEUED",
                createdAtMs = 200L,
                database = database
            )

            val cancelledIds = DownloadExecutionRoomStore
                .requestCancelForStableKeysBefore(
                    context = context,
                    boundaries = listOf(
                        DownloadExecutionRoomStore.CancellationBoundary(
                            stableKey = song.stableKey(),
                            createdAtMsAtMost = 100L
                        )
                    ),
                    excludedOperationIds = setOf(replacementId),
                    database = database
                )

            assertEquals(setOf(predecessorId), cancelledIds)
            assertEquals(
                "CANCEL_REQUESTED",
                database.downloadOperationDao().find(predecessorId)?.state
            )
            assertEquals("QUEUED", database.downloadOperationDao().find(replacementId)?.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun cancelledOrStoppedWaitingStorageMutationCannotBeSilentlyRevived() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val cancelledSong = song(78L, "cancelled-storage-mutation-wait")
            val stoppedSong = song(79L, "stopped-storage-mutation-wait")
            val store = DownloadRecoveryRoomStore(context, database)
            val cancelledOperationId = store.upsertWaitingStorageMutation(
                songs = listOf(cancelledSong),
                userInitiated = true
            ).single()
            val cancellation = DownloadExecutionRoomStore.requestCancelAll(
                context = context,
                database = database
            )

            assertTrue(cancellation.operationIds.contains(cancelledOperationId))
            assertEquals(
                "CANCEL_REQUESTED",
                database.downloadOperationDao().find(cancelledOperationId)?.state
            )
            assertEquals(
                1,
                DownloadExecutionRoomStore.finalizeRequestedCancellations(
                    context = context,
                    operationIds = cancellation.operationIds,
                    database = database
                )
            )
            assertEquals(
                "CANCELLED",
                database.downloadOperationDao().find(cancelledOperationId)?.state
            )
            assertTrue(
                store.upsertWaitingStorageMutation(
                    songs = listOf(cancelledSong),
                    userInitiated = false
                ).isEmpty()
            )

            val stoppedOperationId = store.upsertWaitingStorageMutation(
                songs = listOf(stoppedSong),
                userInitiated = true
            ).single()
            database.downloadOperationDao().requestUserStop(
                operationId = stoppedOperationId,
                updatedAtMs = 300L
            )

            assertTrue(store.listWaitingStorageMutations().isEmpty())
            assertFalse(
                store.promoteWaitingStorageMutation(
                    operationId = stoppedOperationId,
                    stableKey = stoppedSong.stableKey()
                )
            )
            assertTrue(
                store.upsertWaitingStorageMutation(
                    songs = listOf(stoppedSong),
                    userInitiated = false
                ).isEmpty()
            )
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(stoppedOperationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun userRetryCreatesReplacementForCancelledOrStoppedWaitingMutation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val cancelledSong = song(780L, "retry-cancelled-storage-mutation")
            val stoppedSong = song(781L, "retry-stopped-storage-mutation")
            val store = DownloadRecoveryRoomStore(context, database)

            val cancelledOperationId = store.upsertWaitingStorageMutation(
                songs = listOf(cancelledSong),
                userInitiated = true
            ).single()
            val cancellation = DownloadExecutionRoomStore.requestCancel(
                context = context,
                operationId = cancelledOperationId,
                database = database
            )
            assertTrue(cancellation)
            assertEquals(
                1,
                DownloadExecutionRoomStore.finalizeRequestedCancellations(
                    context = context,
                    operationIds = setOf(cancelledOperationId),
                    database = database
                )
            )

            val cancelledReplacementId = store.upsertWaitingStorageMutation(
                songs = listOf(cancelledSong),
                userInitiated = true
            ).single()
            assertNotEquals(cancelledOperationId, cancelledReplacementId)
            assertEquals(
                "CANCELLED",
                database.downloadOperationDao().find(cancelledOperationId)?.state
            )
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(cancelledReplacementId)?.state
            )

            val stoppedOperationId = store.upsertWaitingStorageMutation(
                songs = listOf(stoppedSong),
                userInitiated = true
            ).single()
            database.downloadOperationDao().requestUserStop(
                operationId = stoppedOperationId,
                updatedAtMs = 300L
            )

            val stoppedReplacementId = store.upsertWaitingStorageMutation(
                songs = listOf(stoppedSong),
                userInitiated = true
            ).single()
            assertNotEquals(stoppedOperationId, stoppedReplacementId)
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(stoppedReplacementId)?.state
            )
            assertEquals(
                WAITING_STORAGE_MUTATION_OPERATION_STATE,
                database.downloadOperationDao().find(stoppedOperationId)?.state
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun scheduleRejectionOnlyMovesAnUnclaimedMatchingOperationToRetryable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val queuedSong = song(71L, "queued-rejection")
            val queuedOperationId = "queued-rejection-71"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = queuedOperationId,
                    song = queuedSong,
                    userInitiated = true
                ),
                state = "QUEUED",
                database = database
            )
            assertTrue(
                DownloadExecutionRoomStore.markScheduleRejectedRetryable(
                    context = context,
                    operationId = queuedOperationId,
                    stableKey = queuedSong.stableKey(),
                    errorCode = "HOST_REJECTED",
                    database = database
                )
            )
            assertEquals(
                "RETRYABLE",
                database.downloadOperationDao().find(queuedOperationId)?.state
            )

            val runningSong = song(72L, "running-rejection")
            val runningOperationId = "running-rejection-72"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = runningOperationId,
                    song = runningSong,
                    userInitiated = true
                ),
                state = "RUNNING",
                database = database
            )
            assertFalse(
                DownloadExecutionRoomStore.markScheduleRejectedRetryable(
                    context = context,
                    operationId = runningOperationId,
                    stableKey = runningSong.stableKey(),
                    errorCode = "LATE_HOST_REJECTED",
                    database = database
                )
            )
            assertFalse(
                DownloadExecutionRoomStore.markScheduleRejectedRetryable(
                    context = context,
                    operationId = queuedOperationId,
                    stableKey = runningSong.stableKey(),
                    errorCode = "WRONG_SONG",
                    database = database
                )
            )
            assertEquals(
                "RUNNING",
                database.downloadOperationDao().find(runningOperationId)?.state
            )
            assertTrue(
                DownloadExecutionRoomStore.isExecutionOwned(
                    context = context,
                    operationId = runningOperationId,
                    stableKey = runningSong.stableKey(),
                    database = database
                )
            )
            assertTrue(
                DownloadExecutionRoomStore.requestCancel(
                    context = context,
                    operationId = runningOperationId,
                    database = database
                )
            )
            assertFalse(
                DownloadExecutionRoomStore.isExecutionOwned(
                    context = context,
                    operationId = runningOperationId,
                    stableKey = runningSong.stableKey(),
                    database = database
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun stagingPreparationIsDurableBeforeTransferRetries() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(73L, "prepared-staging")
            val operationId = "prepared-staging-73"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = song,
                    preserveStaging = false,
                    attemptId = 19L,
                    userInitiated = true
                ),
                state = "RUNNING",
                database = database
            )

            assertTrue(
                DownloadExecutionRoomStore.markStagingPrepared(
                    context = context,
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    database = database
                )
            )
            val prepared = DownloadExecutionRoomStore.read(
                context = context,
                operationId = operationId,
                database = database
            ) ?: error("prepared request is missing")
            assertTrue(prepared.preserveStaging)
            assertEquals(19L, prepared.attemptId)
            assertEquals("RUNNING", database.downloadOperationDao().find(operationId)?.state)
            assertTrue(
                DownloadExecutionRoomStore.isExecutionOwned(
                    context = context,
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    database = database
                )
            )
            database.downloadOperationDao().requestUserStop(
                operationId = operationId,
                updatedAtMs = 20L
            )
            assertFalse(
                DownloadExecutionRoomStore.isExecutionOwned(
                    context = context,
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    database = database
                )
            )
            assertFalse(
                DownloadExecutionRoomStore.markStagingPrepared(
                    context = context,
                    operationId = operationId,
                    stableKey = song(74L, "wrong-song").stableKey(),
                    database = database
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun committingOperationCanReturnToRetryableAndBeClaimedAgain() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(70L, "commit-retry")
            val operationId = "commit-retry-70"
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

            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = "RETRYABLE",
                errorCode = "COMMIT_FAILED",
                database = database,
                nowMs = 1_000L
            )

            assertEquals("RETRYABLE", database.downloadOperationDao().find(operationId)?.state)
            assertEquals(
                1_000L + DOWNLOAD_RETRY_BASE_DELAY_MS,
                database.downloadOperationDao().find(operationId)?.nextRetryAtMs
            )
            assertFalse(
                DownloadExecutionRoomStore.tryStart(
                    context = context,
                    operationId = operationId,
                    database = database,
                    nowMs = 1_000L
                )
            )
            assertTrue(
                DownloadExecutionRoomStore.tryStart(
                    context = context,
                    operationId = operationId,
                    database = database,
                    nowMs = 1_000L + DOWNLOAD_RETRY_BASE_DELAY_MS
                )
            )
            assertEquals("RUNNING", database.downloadOperationDao().find(operationId)?.state)
        } finally {
            database.close()
        }
    }

    @Test
    fun interruptedCommitStatesCanBeClaimedWithoutDowngradingCommittedCore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val interruptedStates = linkedMapOf(
                "COMMITTING" to "RUNNING",
                "CORE_COMMITTED" to "CORE_COMMITTED",
                "ASSETS_ENRICHING" to "ASSETS_ENRICHING"
            )
            interruptedStates.entries.forEachIndexed { index, (state, expectedState) ->
                val operationId = "interrupted-commit-$index"
                DownloadExecutionRoomStore.upsert(
                    context = context,
                    request = DownloadExecutionRequest(
                        operationId = operationId,
                        song = song(80L + index, "interrupted-$state"),
                        userInitiated = true
                    ),
                    state = state,
                    database = database
                )

                assertTrue(
                    DownloadExecutionRoomStore.tryStart(
                        context = context,
                        operationId = operationId,
                        allowExistingRunning = true,
                        database = database
                    )
                )
                assertEquals(
                    expectedState,
                    database.downloadOperationDao().find(operationId)?.state
                )
            }
        } finally {
            database.close()
        }
    }
}
