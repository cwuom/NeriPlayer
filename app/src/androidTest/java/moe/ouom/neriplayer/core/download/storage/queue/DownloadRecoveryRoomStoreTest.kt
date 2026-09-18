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
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.state.DOWNLOAD_RETRY_BASE_DELAY_MS
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
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

class DownloadRecoveryRoomStoreTest : DownloadRecoveryRoomStoreTestSupport() {

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
    fun mobileApprovedQueueKeepsItsNetworkPolicyAcrossAutomaticRefresh() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(72L, "mobile-policy")
            val store = DownloadRecoveryRoomStore(context, database)
            val operationId = store.upsertPendingDownloadQueue(
                songs = listOf(song),
                userInitiated = true,
                requiresWifiNetwork = false
            ).single()

            assertFalse(
                requireNotNull(
                    DownloadExecutionRoomStore.read(
                        context = context,
                        operationId = operationId,
                        database = database
                    )
                ).requiresWifiNetwork
            )
            assertFalse(store.listPendingQueuedDownloads().single().requiresWifiNetwork)

            store.upsertPendingDownloadQueue(
                songs = listOf(song),
                userInitiated = false,
                requiresWifiNetwork = true
            )

            assertFalse(
                requireNotNull(
                    DownloadExecutionRoomStore.read(
                        context = context,
                        operationId = operationId,
                        database = database
                    )
                ).requiresWifiNetwork
            )
            assertFalse(store.listPendingQueuedDownloads().single().requiresWifiNetwork)
        } finally {
            database.close()
        }
    }

    @Test
    fun queueRefreshPreservesItsInitialDownloadQualitySnapshot() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val song = song(73L, "quality-snapshot")
            val initialQuality = DownloadAudioQualitySelection(
                neteaseQuality = "lossless",
                youtubeQuality = "very_high",
                biliQuality = "dolby"
            )
            val store = DownloadRecoveryRoomStore(context, database)
            val operationId = store.upsertPendingDownloadQueue(
                songs = listOf(song),
                userInitiated = true,
                downloadAudioQuality = initialQuality
            ).single()

            assertEquals(
                initialQuality,
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.downloadAudioQuality
            )

            store.upsertPendingDownloadQueue(
                songs = listOf(song),
                userInitiated = true,
                downloadAudioQuality = DownloadAudioQualitySelection(
                    neteaseQuality = "standard",
                    youtubeQuality = "low",
                    biliQuality = "low"
                )
            )

            assertEquals(
                initialQuality,
                DownloadExecutionRoomStore.read(
                    context = context,
                    operationId = operationId,
                    database = database
                )?.downloadAudioQuality
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

            val acquiredCount = results.count { (_, acquired) -> acquired }
            assertTrue(acquiredCount in 1..6)
            assertEquals(
                requests.take(acquiredCount).map { it.operationId }.toSet(),
                results.filter { it.second }.map { it.first }.toSet()
            )
            // 并发到达可以暂缓后项，按持久顺序补位后仍须用满全部名额
            requests.take(6).forEach { request ->
                assertTrue(DownloadExecutionRoomStore.tryAcquireHostAdmission(
                    context, request.operationId, 6, database = database
                ))
            }
            assertEquals(
                6,
                DownloadExecutionRoomStore.currentHostAdmissionCount(
                    context = context,
                    database = database
                )
            )
            val releasedOperationId = requests.first().operationId
            val deferredOperationId = requests.last().operationId
            assertFalse(DownloadExecutionRoomStore.tryAcquireHostAdmission(
                context, deferredOperationId, 6, database = database
            ))
            DownloadExecutionRoomStore.updateState(context, releasedOperationId, "RUNNING", database = database)
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
    fun operationUpsertPreservesItsEmbeddedHostAdmissionLease() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val request = DownloadExecutionRequest(
                operationId = "embedded-host-admission",
                song = song(899L, "embedded-host-admission"),
                userInitiated = true
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "QUEUED",
                database = database
            )
            assertTrue(
                DownloadExecutionRoomStore.tryAcquireHostAdmission(
                    context = context,
                    operationId = request.operationId,
                    capacity = 1,
                    nowMs = 2_000L,
                    database = database
                )
            )
            val admissionBeforeUpsert = database.downloadOperationDao()
                .find(request.operationId)

            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = "QUEUED",
                database = database
            )

            val admissionAfterUpsert = database.downloadOperationDao()
                .find(request.operationId)
            assertEquals(
                admissionBeforeUpsert?.hostProcessToken,
                admissionAfterUpsert?.hostProcessToken
            )
            assertEquals(2_000L, admissionAfterUpsert?.hostAdmittedAtMs)
            assertEquals(
                1,
                DownloadExecutionRoomStore.currentHostAdmissionCount(
                    context = context,
                    nowMs = 2_000L,
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

            assertFalse(
                DownloadExecutionRoomStore.tryAcquireHostAdmission(
                    context = context,
                    operationId = requests.last().operationId,
                    capacity = 6,
                    nowMs = admittedAtMs + DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_LEASE_MS + 1L,
                    database = database
                )
            )
            assertEquals(1, DownloadExecutionRoomStore.currentHostAdmissionCount(context, database = database))
            assertTrue(DownloadExecutionRoomStore.tryAcquireHostAdmission(
                context, requests[1].operationId, 6,
                nowMs = admittedAtMs + DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_LEASE_MS + 1L,
                database = database
            ))
            assertEquals(
                2,
                DownloadExecutionRoomStore.currentHostAdmissionCount(
                    context = context,
                    nowMs = admittedAtMs + DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_LEASE_MS + 1L,
                    database = database
                )
            )
            assertTrue(
                database.downloadOperationDao().find(requests.first().operationId)
                    ?.hostProcessToken != null
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
            assertEquals(
                1,
                database.downloadOperationDao().setHostAdmission(
                    operationId = invalidOperationId,
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
                database.downloadOperationDao().find(invalidOperationId)?.hostProcessToken
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
    fun batchReadableLookupSkipsStoppedAndMalformedCandidatesPerSong() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val first = song(74L, "batch-readable-first")
            val second = song(75L, "batch-readable-second")
            val olderFirstOperationId = "batch-readable-first-old"
            val stoppedFirstOperationId = "batch-readable-first-stopped"
            val validSecondOperationId = "batch-readable-second-valid"
            val malformedSecondOperationId = "batch-readable-second-malformed"
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = olderFirstOperationId,
                    song = first,
                    userInitiated = true
                ),
                state = "RUNNING",
                createdAtMs = 10L,
                database = database
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = stoppedFirstOperationId,
                    song = first,
                    userInitiated = true
                ),
                state = "RUNNING",
                createdAtMs = 20L,
                database = database
            )
            database.downloadOperationDao().requestUserStop(
                operationId = stoppedFirstOperationId,
                updatedAtMs = 30L
            )
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = DownloadExecutionRequest(
                    operationId = validSecondOperationId,
                    song = second,
                    userInitiated = true
                ),
                state = "RUNNING",
                createdAtMs = 10L,
                database = database
            )
            val validSecondEntity = requireNotNull(
                database.downloadOperationDao().find(validSecondOperationId)
            )
            database.downloadOperationDao().upsert(
                validSecondEntity.copy(
                    operationId = malformedSecondOperationId,
                    sourceHintJson = "{invalid",
                    stagingDirName = malformedSecondOperationId,
                    createdAtMs = 20L,
                    updatedAtMs = 20L
                )
            )

            val operations = DownloadExecutionRoomStore.findReadableOperationsBySongKeys(
                context = context,
                songKeys = listOf(first.stableKey(), second.stableKey(), "missing"),
                states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                excludeUserStoppedOperations = true,
                database = database
            )

            assertEquals(olderFirstOperationId, operations[first.stableKey()]?.operationId)
            assertEquals(validSecondOperationId, operations[second.stableKey()]?.operationId)
            assertEquals(
                "INVALID",
                database.downloadOperationDao().find(malformedSecondOperationId)?.state
            )
            val snapshots = DownloadExecutionRoomStore.readOperationSnapshots(
                context = context,
                operationIds = listOf(
                    olderFirstOperationId,
                    malformedSecondOperationId,
                    "missing"
                ),
                database = database
            )
            assertEquals("RUNNING", snapshots[olderFirstOperationId]?.state)
            assertFalse(snapshots.containsKey(malformedSecondOperationId))
        } finally {
            database.close()
        }
    }

    @Test
    fun largeLyricPayloadDoesNotBlockSiblingBatchOperations() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val largeLyric = "lyric-line\n".repeat(220_000)
            assertTrue(largeLyric.length >= 2_200_000)
            val largeSong = song(76L, "batch-readable-large")
                .copy(originalLyric = largeLyric)
            val normalSong = song(77L, "batch-readable-normal")
            val store = DownloadRecoveryRoomStore(context, database)
            val roomDispatcher = Dispatchers.Default.limitedParallelism(1)

            val operationIds = withContext(roomDispatcher) {
                withTimeout(15_000L) {
                    store.upsertPendingDownloadQueue(
                        songs = listOf(largeSong, normalSong),
                        userInitiated = true
                    )
                }
            }
            assertEquals(2, operationIds.size)
            val largeOperationId = operationIds[0]
            val normalOperationId = operationIds[1]

            val snapshots = withContext(roomDispatcher) {
                withTimeout(15_000L) {
                    DownloadExecutionRoomStore.readOperationSnapshots(
                        context = context,
                        operationIds = operationIds,
                        database = database
                    )
                }
            }
            assertEquals(operationIds.toSet(), snapshots.keys)
            assertEquals(
                largeLyric,
                snapshots[largeOperationId]?.request?.song?.originalLyric
            )
            assertEquals(
                normalSong.stableKey(),
                snapshots[normalOperationId]?.request?.song?.stableKey()
            )

            val operations = withContext(roomDispatcher) {
                withTimeout(15_000L) {
                    DownloadExecutionRoomStore.findReadableOperationsBySongKeys(
                        context = context,
                        songKeys = listOf(largeSong.stableKey(), normalSong.stableKey()),
                        states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                        excludeUserStoppedOperations = true,
                        database = database
                    )
                }
            }
            assertEquals(
                setOf(largeSong.stableKey(), normalSong.stableKey()),
                operations.keys
            )
            assertEquals(largeOperationId, operations[largeSong.stableKey()]?.operationId)
            assertEquals(normalOperationId, operations[normalSong.stableKey()]?.operationId)
            assertEquals(
                largeLyric,
                operations[largeSong.stableKey()]?.song?.originalLyric
            )

            val refreshedOperationIds = withContext(roomDispatcher) {
                withTimeout(15_000L) {
                    store.upsertPendingDownloadQueue(
                        songs = listOf(largeSong, normalSong),
                        userInitiated = true
                    )
                }
            }
            assertEquals(operationIds, refreshedOperationIds)
        } finally {
            database.close()
        }
    }

    @Test
    fun batchReadableLookupPagesPastSqliteInClauseLimit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val songs = List(901) { index ->
                song(80_000L + index, "batch-page-$index")
            }
            val operationIds = songs.mapIndexed { index, _ -> "batch-page-$index" }
            for (index in songs.indices) {
                DownloadExecutionRoomStore.upsert(
                    context = context,
                    request = DownloadExecutionRequest(
                        operationId = operationIds[index],
                        song = songs[index],
                        userInitiated = true
                    ),
                    state = "RETRYABLE",
                    createdAtMs = index.toLong(),
                    database = database
                )
            }

            val operations = DownloadExecutionRoomStore.findReadableOperationsBySongKeys(
                context = context,
                songKeys = songs.map(SongItem::stableKey),
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                excludeUserStoppedOperations = true,
                database = database
            )
            val snapshots = DownloadExecutionRoomStore.readOperationSnapshots(
                context = context,
                operationIds = operationIds,
                database = database
            )

            assertEquals(901, operations.size)
            assertEquals(operationIds.first(), operations[songs.first().stableKey()]?.operationId)
            assertEquals(operationIds.last(), operations[songs.last().stableKey()]?.operationId)
            assertEquals(901, snapshots.size)
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
            assertEquals(
                1,
                database.downloadOperationDao().setHostAdmission(
                    operationId = operationId,
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
                database.downloadOperationDao().find(operationId)?.hostProcessToken
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
}
