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
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadHostAdmissionEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadRecoveryRoomStoreTest {
    @Test
    fun concurrentQueueRefreshCreatesOneActiveOperationPerSong() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(71L, "concurrent-operation")
            val deterministicOperationId = UUID.nameUUIDFromBytes(
                "pending-download:${song.stableKey()}".toByteArray(Charsets.UTF_8)
            ).toString()
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = deterministicOperationId,
                    song = song,
                    userInitiated = true
                ),
                state = "COMPLETED",
                database = database
            )
            val start = CompletableDeferred<Unit>()
            val store = DownloadRecoveryRoomStore(context, database)

            val operationIds = coroutineScope {
                List(2) {
                    async(Dispatchers.IO) {
                        start.await()
                        store.upsertPendingDownloadQueue(
                            songs = listOf(song),
                            userInitiated = true
                        ).single()
                    }
                }.also { start.complete(Unit) }.awaitAll()
            }

            assertEquals(1, operationIds.distinct().size)
            assertEquals(
                1,
                database.downloadOperationDao().findAll().count { entity ->
                    entity.stableKey == song.stableKey() &&
                        entity.state in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
                }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun concurrentHostAdmissionsNeverExceedTheSharedCapacity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val requests = (0 until 7).map { index ->
                DownloadExecutionRequest(
                    operationId = "host-admission-$index",
                    song = song(index.toLong() + 800L, "host-admission-$index"),
                    userInitiated = true
                )
            }
            requests.forEach { request ->
                DownloadExecutionRoomStore.upsert(
                    context = context,
                    request = request,
                    state = "QUEUED",
                    database = database
                )
            }
            val start = CompletableDeferred<Unit>()

            val results = coroutineScope {
                requests.map { request ->
                    async(Dispatchers.IO) {
                        start.await()
                        request.operationId to DownloadExecutionRoomStore.tryAcquireHostAdmission(
                            context = context,
                            operationId = request.operationId,
                            capacity = 6,
                            database = database
                        )
                    }
                }.also { start.complete(Unit) }.awaitAll()
            }

            assertEquals(6, results.count { (_, acquired) -> acquired })
            assertEquals(
                6,
                DownloadExecutionRoomStore.currentHostAdmissionCount(
                    context = context,
                    database = database
                )
            )
            val releasedOperationId = results.first { (_, acquired) -> acquired }.first
            val deferredOperationId = results.first { (_, acquired) -> !acquired }.first
            DownloadExecutionRoomStore.releaseHostAdmission(
                context = context,
                operationId = releasedOperationId,
                database = database
            )
            assertTrue(
                DownloadExecutionRoomStore.tryAcquireHostAdmission(
                    context = context,
                    operationId = deferredOperationId,
                    capacity = 6,
                    database = database
                )
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun staleQueuedHostAdmissionsAreReclaimedWithoutReclaimingRunningWork() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val admittedAtMs = 1_000L
            val requests = (0 until 7).map { index ->
                DownloadExecutionRequest(
                    operationId = "stale-host-admission-$index",
                    song = song(index.toLong() + 900L, "stale-host-admission-$index"),
                    userInitiated = true
                )
            }
            requests.forEach { request ->
                DownloadExecutionRoomStore.upsert(
                    context = context,
                    request = request,
                    state = "QUEUED",
                    database = database
                )
            }
            requests.take(6).forEach { request ->
                assertTrue(
                    DownloadExecutionRoomStore.tryAcquireHostAdmission(
                        context = context,
                        operationId = request.operationId,
                        capacity = 6,
                        nowMs = admittedAtMs,
                        database = database
                    )
                )
            }
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = requests.first().operationId,
                state = "RUNNING",
                database = database
            )

            assertTrue(
                DownloadExecutionRoomStore.tryAcquireHostAdmission(
                    context = context,
                    operationId = requests.last().operationId,
                    capacity = 6,
                    nowMs = admittedAtMs + DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_LEASE_MS + 1L,
                    database = database
                )
            )
            assertEquals(
                2,
                DownloadExecutionRoomStore.currentHostAdmissionCount(
                    context = context,
                    nowMs = admittedAtMs + DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_LEASE_MS + 1L,
                    database = database
                )
            )
            assertTrue(
                database.downloadOperationDao().findHostAdmission(requests.first().operationId) != null
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun retryableOperationRemainsVisibleToStartupRecovery() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(
                operationId = "retryable-startup-recovery",
                song = song(999L, "retryable-startup-recovery"),
                userInitiated = true
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "RETRYABLE",
                database = database
            )

            assertEquals(
                listOf(request.operationId),
                DownloadRecoveryRoomStore(context, database)
                    .listPendingQueuedDownloads()
                    .map { entry -> entry.operationId }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unreadableRunningOperationIsInvalidatedBeforeReplacementIsQueued() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(72L, "invalid-operation")
            val invalidOperationId = "invalid-running-72"
            val libraryId = currentLibraryId(context)
            database.downloadOperationDao().upsert(
                DownloadOperationEntity(
                    operationId = invalidOperationId,
                    stableKey = song.stableKey(),
                    libraryId = libraryId,
                    state = "RUNNING",
                    queueOrder = 0,
                    sourceHintJson = "{invalid",
                    stagingDirName = invalidOperationId,
                    bytesWritten = 123L,
                    totalBytes = 456L,
                    resumeJson = "{\"legacy\":true}",
                    retryCount = 2,
                    nextRetryAtMs = 30L,
                    lastErrorCode = "LEGACY_PAYLOAD",
                    createdAtMs = 1L,
                    updatedAtMs = 1L
                )
            )
            database.downloadOperationDao().upsertHostAdmission(
                DownloadHostAdmissionEntity(
                    operationId = invalidOperationId,
                    libraryId = libraryId,
                    processToken = "legacy-payload",
                    admittedAtMs = 10L
                )
            )

            val replacementId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song),
                    userInitiated = true
                )
                .single()

            assertTrue(replacementId != invalidOperationId)
            assertEquals(
                "INVALID",
                database.downloadOperationDao().find(invalidOperationId)?.state
            )
            assertEquals(
                song.stableKey(),
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = replacementId,
                    database = database
                )?.song?.stableKey()
            )
            assertEquals(
                null,
                database.downloadOperationDao().findHostAdmission(invalidOperationId)
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun validInFlightOperationWinsOverMalformedReusableDuplicate() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(73L, "in-flight-wins")
            val runningOperationId = "running-73"
            val malformedOperationId = "malformed-queued-73"
            val libraryId = currentLibraryId(context)
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = runningOperationId,
                    song = song,
                    userInitiated = true
                ),
                state = "RUNNING",
                database = database
            )
            database.downloadOperationDao().upsert(
                DownloadOperationEntity(
                    operationId = malformedOperationId,
                    stableKey = song.stableKey(),
                    libraryId = libraryId,
                    state = "QUEUED",
                    queueOrder = 1,
                    sourceHintJson = "{invalid",
                    stagingDirName = malformedOperationId,
                    bytesWritten = 0L,
                    totalBytes = null,
                    resumeJson = null,
                    retryCount = 0,
                    nextRetryAtMs = null,
                    lastErrorCode = null,
                    createdAtMs = 2L,
                    updatedAtMs = 2L
                )
            )

            val selectedOperationId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song.copy(name = "updated")),
                    userInitiated = true
                )
                .single()

            assertEquals(runningOperationId, selectedOperationId)
            assertEquals(
                "INVALID",
                database.downloadOperationDao().find(malformedOperationId)?.state
            )
            assertTrue(
                DownloadRecoveryRoomStore(context, database)
                    .listPendingQueuedDownloads()
                    .isEmpty()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRoundTripDoesNotRewriteLegacyFiles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        queueFile.delete()

        try {
            val first = song(1L, "first")
            val second = song(2L, "second")
            val store = DownloadRecoveryRoomStore(context, database)

            store.upsertPendingDownloadQueue(listOf(first, second), nowMs = 10L)
            store.upsertPendingDownloadQueue(listOf(first.copy(name = "updated")), nowMs = 20L)

            val queued = store.listPendingQueuedDownloads()
            assertEquals(listOf("updated", "second"), queued.map { it.song.name })
            assertEquals(10L, queued.first().queuedAtMs)
            assertEquals("updated", queued.first().song.name)
            assertTrue(!queueFile.exists())

            store.removePendingDownloadQueueEntries(listOf(first.stableKey()))
            assertEquals(listOf("second"), store.listPendingQueuedDownloads().map { it.song.name })
        } finally {
            queueFile.delete()
            database.close()
        }
    }

    @Test
    fun importsLegacyFilesBeforePromotingRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        val cancelledFile = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        val first = song(3L, "legacy")
        queueFile.writeText(
            ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
                entries = listOf(
                    ManagedDownloadStorage.PendingDownloadQueueEntry(
                        stableKey = first.stableKey(),
                        song = first,
                        order = 0,
                        queuedAtMs = 40L
                    )
                ),
                updatedAtMs = 40L
            )
        )
        cancelledFile.writeText(
            ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(
                songKeys = setOf(first.stableKey()),
                updatedAtMs = 40L
            )
        )

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            store.bootstrapLegacyFilesOnce()
            assertEquals(listOf("legacy"), store.listPendingQueuedDownloads().map { it.song.name })
            assertEquals(
                setOf(first.stableKey()),
                DownloadExecutionRoomStore.listByStates(
                    context = context,
                    states = listOf("CANCEL_REQUESTED", "CANCELLED"),
                    database = database
                )
                    .map { it.request.song.stableKey() }
                    .toSet()
            )
            assertEquals(
                DownloadRecoveryRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY
                    )
                    ?.value
            )
            assertEquals(
                DownloadRecoveryRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadRecoveryRoomStore.CANCELLED_KEYS_CUTOVER_STATE_KEY
                    )
                    ?.value
            )
            assertTrue(queueFile.exists())
            assertTrue(cancelledFile.exists())
        } finally {
            queueFile.delete()
            cancelledFile.delete()
            database.close()
        }
    }

    @Test
    fun runtime_reads_do_not_lazily_import_legacy_queue_files() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        val legacySong = song(33L, "legacy-runtime")
        queueFile.writeText(
            ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
                entries = listOf(
                    ManagedDownloadStorage.PendingDownloadQueueEntry(
                        stableKey = legacySong.stableKey(),
                        song = legacySong,
                        order = 0,
                        queuedAtMs = 40L
                    )
                ),
                updatedAtMs = 40L
            )
        )

        try {
            val store = DownloadRecoveryRoomStore(context, database)

            assertTrue(store.listPendingQueuedDownloads().isEmpty())
            assertTrue(queueFile.exists())
            assertEquals(
                null,
                database.syncMetadataDao()
                    .getMigrationMetadata(DownloadRecoveryRoomStore.PENDING_QUEUE_CUTOVER_STATE_KEY)
            )
        } finally {
            queueFile.delete()
            database.close()
        }
    }

    @Test
    fun queueRefreshPreservesUserInitiatedOperationIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val queueFile = File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        val cancelledFile = File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        queueFile.delete()
        cancelledFile.delete()

        try {
            val song = song(4L, "user")
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = "user-operation-4",
                    song = song,
                    userInitiated = true
                ),
                state = "QUEUED",
                database = database
            )
            val store = DownloadRecoveryRoomStore(context, database)

            store.upsertPendingDownloadQueue(listOf(song.copy(name = "updated")), nowMs = 50L)

            val restored = DownloadExecutionRoomStore.listByState(
                context = context,
                state = "QUEUED",
                database = database
            ).single()
            assertEquals("user-operation-4", store.listPendingQueuedDownloads().single().operationId)
            assertTrue(restored.request.userInitiated)
        } finally {
            queueFile.delete()
            cancelledFile.delete()
            database.close()
        }
    }

    @Test
    fun queueRefresh_rewritesLegacyOperationPayload_inPlace() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(5L, "legacy-operation")
            val operationId = "legacy-operation-5"
            val libraryId = currentLibraryId(context)
            database.downloadOperationDao().upsert(
                DownloadOperationEntity(
                    operationId = operationId,
                    stableKey = song.stableKey(),
                    libraryId = libraryId,
                    state = "QUEUED",
                    queueOrder = 0,
                    sourceHintJson = "{\"channelId\":\"netease\",\"audioId\":\"5\"}",
                    stagingDirName = operationId,
                    bytesWritten = 123L,
                    totalBytes = 456L,
                    resumeJson = "{\"legacy\":true}",
                    retryCount = 2,
                    nextRetryAtMs = 30L,
                    lastErrorCode = "LEGACY_PAYLOAD",
                    createdAtMs = 10L,
                    updatedAtMs = 10L
                )
            )
            database.downloadOperationDao().upsertHostAdmission(
                DownloadHostAdmissionEntity(
                    operationId = operationId,
                    libraryId = libraryId,
                    processToken = "legacy-payload",
                    admittedAtMs = 10L
                )
            )

            val store = DownloadRecoveryRoomStore(context, database)
            store.upsertPendingDownloadQueue(listOf(song.copy(name = "rewritten")), nowMs = 20L)

            val restored = DownloadExecutionRoomStore.read(
                context = context,
                operationId = operationId,
                database = database
            )
            assertEquals("rewritten", restored?.song?.name)
            assertEquals(
                listOf(operationId),
                store.listPendingQueuedDownloads().map { it.operationId }
            )
            assertEquals(1, database.downloadOperationDao().findAll().size)
            val rehydrated = database.downloadOperationDao().find(operationId)
            assertEquals(0L, rehydrated?.bytesWritten)
            assertEquals(null, rehydrated?.totalBytes)
            assertEquals(null, rehydrated?.resumeJson)
            assertEquals(0, rehydrated?.retryCount)
            assertEquals(null, rehydrated?.nextRetryAtMs)
            assertEquals(null, rehydrated?.lastErrorCode)
            assertEquals(
                null,
                database.downloadOperationDao().findHostAdmission(operationId)
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRoundTrip_persistsCanonicalRemoteStableKey() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = SongItem(
                id = 2022649173L,
                name = "愛が灯る",
                artist = "ロクデナシ",
                album = "Netease",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = null,
                channelId = "netease",
                audioId = "2022649173"
            )
            val store = DownloadRecoveryRoomStore(context, database)
            val operationId = store.upsertPendingDownloadQueue(listOf(song)).single()
            val entity = database.downloadOperationDao().find(operationId)
                ?: error("operation was not persisted")

            assertEquals(
                song.stableKey(),
                JSONObject(entity.sourceHintJson).optString("sourceStableKey")
            )
            assertEquals(
                song.stableKey(),
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.song?.stableKey()
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRefresh_doesNotReuseStoppedDeterministicOperation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(6L, "stopped-operation")
            val deterministicId = java.util.UUID.nameUUIDFromBytes(
                "pending-download:${song.stableKey()}".toByteArray(Charsets.UTF_8)
            ).toString()
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = deterministicId,
                    song = song,
                    userInitiated = true
                ),
                state = "QUEUED",
                database = database
            )
            database.downloadOperationDao().requestUserStop(
                operationId = deterministicId,
                updatedAtMs = 20L
            )

            val operationId = DownloadRecoveryRoomStore(context, database)
                .upsertPendingDownloadQueue(
                    songs = listOf(song.copy(name = "restarted")),
                    userInitiated = true
                )
                .single()

            assertTrue(operationId != deterministicId)
            assertEquals(
                "restarted",
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.song?.name
            )
            assertEquals(
                true,
                database.downloadOperationDao().find(deterministicId)
                    ?.stopRequestedByUser
            )
            assertEquals(
                "QUEUED",
                database.downloadOperationDao().find(operationId)?.state
            )
            assertEquals(
                false,
                database.downloadOperationDao().find(operationId)?.stopRequestedByUser
            )
        } finally {
            database.close()
        }
    }

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
            assertTrue(store.listWaitingStorageMutations().isEmpty())
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
                    userInitiated = true
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
                    userInitiated = true
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
                database = database
            )

            assertEquals("RETRYABLE", database.downloadOperationDao().find(operationId)?.state)
            assertTrue(
                DownloadExecutionRoomStore.tryStart(
                    context = context,
                    operationId = operationId,
                    database = database
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
                dao.upsertHostAdmission(
                    DownloadHostAdmissionEntity(
                        operationId = operationId,
                        libraryId = libraryId,
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
                dao.findHostAdmission(operationId) != null
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

    private fun largeCancelledOperation(
        operationId: String,
        stableKey: String,
        state: String = "CANCEL_REQUESTED",
        libraryId: String
    ): DownloadOperationEntity {
        return DownloadOperationEntity(
            operationId = operationId,
            stableKey = stableKey,
            libraryId = libraryId,
            state = state,
            queueOrder = 0,
            sourceHintJson = "{}",
            stagingDirName = operationId,
            bytesWritten = 0L,
            totalBytes = null,
            resumeJson = null,
            retryCount = 0,
            nextRetryAtMs = null,
            lastErrorCode = null,
            createdAtMs = 1L,
            updatedAtMs = 1L
        )
    }

    private fun currentLibraryId(context: Context): String {
        return ManagedDownloadStorage.currentSnapshotCacheKey(context)
    }

    private fun song(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "album",
            albumId = 10L,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = id.toString()
        )
    }

    private companion object {
        const val LARGE_OPERATION_COUNT = 1_001
    }
}
