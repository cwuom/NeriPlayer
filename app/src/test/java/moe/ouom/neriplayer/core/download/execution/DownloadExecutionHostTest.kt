package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.host.DownloadOperationEntryPoint
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionPumpResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionSchedule
import moe.ouom.neriplayer.core.download.execution.host.normalizeDownloadOperationId
import moe.ouom.neriplayer.core.download.execution.host.releaseTransferReservation
import moe.ouom.neriplayer.core.download.execution.host.reserveTransferSlot
import moe.ouom.neriplayer.core.download.execution.host.selectDownloadExecutionBackend
import moe.ouom.neriplayer.core.download.execution.host.shouldBlockExistingDownloadOperation
import moe.ouom.neriplayer.core.download.execution.host.transferLaneOccupancy
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.download.execution.uidt.UidtDownloadJobService
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.player.download.resolveDownloadDispatchWindow
import moe.ouom.neriplayer.core.download.observability.DownloadPumpSelectionTrace
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock


class DownloadExecutionHostTest : DownloadExecutionHostTestSupport() {

    @Test
    fun `operation ids are restricted to safe file names`() {
        assertEquals("operation-01", normalizeDownloadOperationId(" operation-01 "))
        assertNull(normalizeDownloadOperationId(""))
        assertNull(normalizeDownloadOperationId("../operation"))
        assertNull(normalizeDownloadOperationId("operation/id"))
        assertNull(normalizeDownloadOperationId("operation id"))
    }

    @Test
    fun `operation store round trips request from the injected journal`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore {
            testJournal
        }
        val request = DownloadExecutionRequest(
            operationId = "operation-01",
            song = SongItem(
                id = 42L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 7L,
                durationMs = 1234L,
                coverUrl = "https://example.invalid/cover.jpg",
                sourceStableKey = "netease:42"
            ),
            preserveStaging = true,
            attemptId = 7L
        )

        store.save(context, request)
        val restored = store.read(context, request.operationId)
        assertNotNull(restored)
        val restoredRequest = restored!!
        assertEquals(request.operationId, restoredRequest.operationId)
        assertEquals(request.song.id, restoredRequest.song.id)
        assertEquals(request.song.name, restoredRequest.song.name)
        assertEquals(request.song.sourceStableKey, restoredRequest.song.sourceStableKey)
        assertEquals(request.preserveStaging, restoredRequest.preserveStaging)
        assertEquals(request.attemptId, restoredRequest.attemptId)
    }

    @Test
    fun `operation store finds the durable operation for a song`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val request = DownloadExecutionRequest(
            operationId = "operation-song",
            song = sampleSong()
        )

        store.save(context, request)

        assertEquals(
            request.operationId,
            store.findOperationIdForSong(context, request.song.stableKey())
        )
    }

    @Test
    fun `a cancellation-marked predecessor does not block a fresh operation`() {
        assertFalse(
            shouldBlockExistingDownloadOperation(
                existingOperationId = "old-operation",
                requestedOperationId = "new-operation",
                existingState = "RUNNING",
                existingReadable = true,
                cancellationRequested = true
            )
        )
        assertTrue(
            shouldBlockExistingDownloadOperation(
                existingOperationId = "old-operation",
                requestedOperationId = "new-operation",
                existingState = "RUNNING",
                existingReadable = true,
                cancellationRequested = false
            )
        )
    }

    @Test
    fun `stopped operation is durable and included in the stopped song index`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val request = DownloadExecutionRequest(
            operationId = "operation-stopped",
            song = sampleSong()
        )
        store.save(context, request)

        store.markStopped(context, request.operationId)

        assertTrue(store.isStopped(context, request.operationId))
        assertTrue(
            request.song.stableKey() in store.stoppedSongKeys(context)
        )
    }

    @Test
    fun `UIDT job id is stable and never uses reserved low range`() {
        val first = UidtDownloadJobService.jobIdFor("operation-01")
        val second = UidtDownloadJobService.jobIdFor("operation-01")
        assertEquals(first, second)
        assertTrue(first >= 100_000)
        assertTrue(first <= UidtDownloadJobService.UIDT_JOB_ID_MAX)
        assertTrue(UidtDownloadJobService.UIDT_JOB_ID_MIN > 99_999)
    }

    @Test
    fun `UIDT collision probes the next free scheduler slot`() {
        val first = UidtDownloadJobService.jobIdFor("op-21583")
        val second = UidtDownloadJobService.jobIdFor("op-33989")
        assertEquals(first, second)
        val selected = UidtDownloadJobService.selectAvailableUidtJobId(
            operationId = "op-33989",
            occupiedJobIds = setOf(first)
        )
        assertNotNull(selected)
        assertTrue(selected != first)
    }

    @Test
    fun `execution backend supports UIDT from API 34 through API 36`() {
        assertEquals(
            DownloadExecutionSchedule.Backend.FOREGROUND_WORK,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                userInitiated = true
            )
        )
        assertEquals(
            DownloadExecutionSchedule.Backend.FOREGROUND_WORK,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                userInitiated = false
            )
        )
        assertEquals(
            DownloadExecutionSchedule.Backend.UIDT_JOB,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                userInitiated = true
            )
        )
        assertEquals(
            DownloadExecutionSchedule.Backend.UIDT_JOB,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
                userInitiated = true
            )
        )
        assertEquals(
            DownloadExecutionSchedule.Backend.UIDT_JOB,
            selectDownloadExecutionBackend(
                sdkInt = Build.VERSION_CODES.BAKLAVA,
                userInitiated = true
            )
        )
    }

    @Test
    fun `foreground download work waits for a connected network`() {
        val request = ForegroundDownloadWorker.buildRequest("operation-network")

        assertEquals(
            NetworkType.CONNECTED,
            request.workSpec.constraints.requiredNetworkType
        )
        assertTrue(request.tags.contains("download_execution_all"))
    }

    @Test
    fun `new operation schedules share one durable pump work name`() {
        val pumpNames = (1..1_000)
            .map { ForegroundDownloadWorker.PUMP_WORK_NAME }
            .toSet()

        assertEquals(setOf(ForegroundDownloadWorker.PUMP_WORK_NAME), pumpNames)
        val request = ForegroundDownloadWorker.buildPumpRequest()
        assertTrue(request.tags.contains("download_execution_pump"))
        assertTrue(request.tags.contains("download_execution_all"))
        assertFalse(
            request.workSpec.input.keyValueMap.containsKey(
                ForegroundDownloadWorker.OPERATION_ID_KEY
            )
        )
    }

    @Test
    fun `fresh host pumps a durable queued operation after restart`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-pump-recovery",
            song = sampleSong()
        )
        store.save(context, request)
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, restoredRequest ->
                assertEquals(request.operationId, restoredRequest.operationId)
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(DownloadExecutionPumpResult.Completed, host.pump(context))
        assertEquals(1, executions)
        assertEquals("COMPLETED", store.currentState(context, request.operationId))
    }

    @Test
    fun `pump reports retry without dropping a transient operation`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-pump-retry",
            song = sampleSong()
        )
        store.save(context, request)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                DownloadExecutionResult.Retry
            },
            sdkInt = 28
        )

        assertEquals(DownloadExecutionPumpResult.ContinueAfterRetry, host.pump(context))
        assertEquals("RETRYABLE", store.currentState(context, request.operationId))
    }

    @Test
    fun `host admission uses the independent dispatch window`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-configured-capacity",
            song = sampleSong()
        )
        store.save(context, request)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 3 }
        )

        assertEquals(
            DownloadExecutionResult.Accepted,
            host.execute(context, request.operationId)
        )
        assertEquals(resolveDownloadDispatchWindow(3), journal.lastHostAdmissionCapacity)
    }

    @Test
    fun `pump continues with later operations when an earlier operation fails`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val failed = DownloadExecutionRequest(
            operationId = "operation-pump-failed",
            song = sampleSong().copy(id = 101L)
        )
        val later = DownloadExecutionRequest(
            operationId = "operation-pump-later",
            song = sampleSong().copy(id = 102L)
        )
        store.save(context, failed)
        store.save(context, later)
        val executed = mutableListOf<String>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                executed += request.operationId
                if (request.operationId == failed.operationId) {
                    DownloadExecutionResult.Failed(IllegalStateException("transient"))
                } else {
                    DownloadExecutionResult.Accepted
                }
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 }
        )

        assertEquals(DownloadExecutionPumpResult.ContinueAfterRetry, host.pump(context))
        assertEquals(setOf(failed.operationId, later.operationId), executed.toSet())
        assertEquals("RETRYABLE", store.currentState(context, failed.operationId))
        assertEquals("COMPLETED", store.currentState(context, later.operationId))
    }

    @Test
    fun `transient failure stops replenishing the window until recovery is reconsidered`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val requests = (1..5).map { index ->
            DownloadExecutionRequest(
                operationId = "ordered-retry-$index", song = sampleSong().copy(id = index.toLong())
            ).also { store.save(context, it) }
        }
        val firstFailed = CompletableDeferred<Unit>()
        journal.afterStateUpdate = { operationId, state ->
            if (operationId == requests.first().operationId && state == "RETRYABLE") {
                firstFailed.complete(Unit)
            }
        }
        val executed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                executed.add(request.operationId)
                if (request.operationId == requests.first().operationId) {
                    DownloadExecutionResult.Retry
                } else {
                    firstFailed.await()
                    DownloadExecutionResult.Accepted
                }
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 }
        )

        assertEquals(DownloadExecutionPumpResult.ContinueAfterRetry, host.pump(context))
        val window = resolveDownloadDispatchWindow(1)
        assertEquals(requests.take(window).map { it.operationId }.toSet(), executed.toSet())
        requests.drop(window).forEach { request ->
            assertEquals("QUEUED", store.currentState(context, request.operationId))
        }
        assertEquals("RETRYABLE", store.currentState(context, requests.first().operationId))
    }

    @Test
    fun `pump refills a freed window before slower operations finish`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val requests = (0..3).map { index ->
            DownloadExecutionRequest(
                operationId = "operation-pump-refill-$index",
                song = sampleSong().copy(id = 50_000L + index)
            )
        }
        requests.forEach { request -> store.save(context, request) }
        val firstCompleted = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val fourthStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                when (request.operationId) {
                    requests[0].operationId -> {
                        firstCompleted.complete(Unit)
                        DownloadExecutionResult.Accepted
                    }

                    requests[1].operationId -> {
                        slowStarted.complete(Unit)
                        releaseSlow.await()
                        DownloadExecutionResult.Accepted
                    }

                    requests[2].operationId -> {
                        thirdStarted.complete(Unit)
                        releaseThird.await()
                        DownloadExecutionResult.Accepted
                    }

                    requests[3].operationId -> {
                        fourthStarted.complete(Unit)
                        DownloadExecutionResult.Accepted
                    }

                    else -> DownloadExecutionResult.Accepted
                }
            },
            sdkInt = 28,
            // 一个真实传输槽位加两个准备名额，第四首必须在首项完成后立即补入
            downloadParallelismProvider = { 1 }
        )

        val pump = async { host.pump(context) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    firstCompleted.await()
                    slowStarted.await()
                    thirdStarted.await()
                    fourthStarted.await()
                }
            }
            assertFalse(pump.isCompleted)
        } finally {
            releaseSlow.complete(Unit)
            releaseThird.complete(Unit)
        }
        assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
        requests.forEach { request ->
            assertEquals("COMPLETED", store.currentState(context, request.operationId))
        }
    }

    @Test
    fun `pump keeps preparation headroom warm for six and eight transfer limits`() = runTest {
        listOf(6, 8).forEach { networkParallelism ->
            val context = mockContext()
            val journal = InMemoryDownloadExecutionOperationJournal()
            val store = DownloadExecutionOperationStore { journal }
            val dispatchWindow = resolveDownloadDispatchWindow(networkParallelism)
            val requests = (0 until dispatchWindow).map { index ->
                DownloadExecutionRequest(
                    operationId = "operation-pump-headroom-$networkParallelism-$index",
                    song = sampleSong().copy(
                        id = 50_050L + networkParallelism * 100L + index
                    )
                )
            }
            requests.forEach { request -> store.save(context, request) }
            val started = requests.associate { request ->
                request.operationId to CompletableDeferred<Unit>()
            }
            val release = CompletableDeferred<Unit>()
            val host = DefaultDownloadExecutionHost(
                operationStore = store,
                entryPoint = DownloadOperationEntryPoint { _, request ->
                    started.getValue(request.operationId).complete(Unit)
                    release.await()
                    DownloadExecutionResult.Accepted
                },
                sdkInt = 28,
                downloadParallelismProvider = { networkParallelism }
            )

            val pump = async { host.pump(context) }
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(2_000L) {
                        started.values.forEach { signal -> signal.await() }
                    }
                }
                assertFalse(pump.isCompleted)
            } finally {
                release.complete(Unit)
            }
            assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
            requests.forEach { request ->
                assertEquals("COMPLETED", store.currentState(context, request.operationId))
            }
        }
    }

    @Test
    fun `pump defers a candidate when an external reservation fills the lane`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-pump-reservation-deferred",
            song = sampleSong().copy(id = 50_100L)
        )
        store.save(context, request)
        val executed = mutableListOf<String>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, candidate ->
                executed += candidate.operationId
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 }
        )

        val dispatchWindow = resolveDownloadDispatchWindow(1)
        val peerTokens = (0 until dispatchWindow).map { index ->
            checkNotNull(
                host.reserveTransferSlot(
                    "operation-pump-reservation-peer-$index",
                    31L + index,
                    dispatchWindow
                )
            )
        }

        assertEquals(
            DownloadExecutionPumpResult.ContinueAfterContention,
            host.pump(context)
        )
        assertTrue(executed.isEmpty())

        peerTokens.forEachIndexed { index, peerToken ->
            host.releaseTransferReservation("operation-pump-reservation-peer-$index", peerToken)
        }
        assertEquals(DownloadExecutionPumpResult.Completed, host.pump(context))
        assertEquals(listOf(request.operationId), executed)
        assertEquals("COMPLETED", store.currentState(context, request.operationId))
    }

    @Test
    fun `core commit releases one transfer lane before enrichment finishes`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val requests = (0..2).map { index ->
            DownloadExecutionRequest(
                operationId = "operation-pump-core-release-$index",
                song = sampleSong().copy(id = 51_000L + index)
            )
        }
        requests.forEach { request -> store.save(context, request) }
        val firstEnrichment = CompletableDeferred<Unit>()
        val secondEnrichment = CompletableDeferred<Unit>()
        val firstCommitted = CompletableDeferred<Unit>()
        val secondCommitted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { entryContext, request ->
                if (request.operationId == requests[2].operationId) {
                    // 测试入口不经过真实网络 permit，先等待两个物理槽位完成交接
                    firstCommitted.await()
                    secondCommitted.await()
                }
                val token = host.onTransferStarted(
                    context = entryContext,
                    operationId = request.operationId,
                    attemptId = request.attemptId
                )
                assertNotNull(token)
                when (request.operationId) {
                    requests[0].operationId -> {
                        assertTrue(
                            host.onCoreCommitted(
                                context = entryContext,
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                transferOwnerToken = token
                            )
                        )
                        firstCommitted.complete(Unit)
                        firstEnrichment.await()
                    }

                    requests[1].operationId -> {
                        assertTrue(
                            host.onCoreCommitted(
                                context = entryContext,
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                transferOwnerToken = token
                            )
                        )
                        secondCommitted.complete(Unit)
                        secondEnrichment.await()
                    }

                    requests[2].operationId -> thirdStarted.complete(Unit)
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 2 }
        )

        val pump = async { host.pump(context) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    firstCommitted.await()
                    secondCommitted.await()
                    // 前两首仍在后处理，第三首必须在释放传输槽位后进入
                    thirdStarted.await()
                }
            }
            assertTrue(host.isExecuting(requests[0].operationId))
            assertTrue(host.isExecuting(requests[1].operationId))
        } finally {
            firstEnrichment.complete(Unit)
            secondEnrichment.complete(Unit)
        }
        assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
        requests.forEach { request ->
            assertEquals("COMPLETED", store.currentState(context, request.operationId))
        }
    }

    @Test
    fun `pump reservation follows the current durable attempt before transfer starts`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val first = DownloadExecutionRequest(
            operationId = "operation-pump-reservation-attempt-first",
            song = sampleSong().copy(id = 51_050L),
            attemptId = 7L
        )
        val second = DownloadExecutionRequest(
            operationId = "operation-pump-reservation-attempt-second",
            song = sampleSong().copy(id = 51_051L),
            attemptId = 8L
        )
        store.save(context, first)
        store.save(context, second)
        val firstCommitted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { entryContext, request ->
                if (request.operationId == first.operationId) {
                    val refreshedAttemptId = 19L
                    journal.forceRequest(first.copy(attemptId = refreshedAttemptId))
                    val token = checkNotNull(
                        host.onTransferStarted(
                            context = entryContext,
                            operationId = request.operationId,
                            attemptId = refreshedAttemptId
                        )
                    )
                    assertTrue(
                        host.onCoreCommitted(
                            context = entryContext,
                            operationId = request.operationId,
                            attemptId = refreshedAttemptId,
                            transferOwnerToken = token
                        )
                    )
                    firstCommitted.complete(Unit)
                    secondStarted.await()
                } else {
                    // 测试入口不经过真实网络 permit，这里模拟单槽位许可的交接顺序
                    firstCommitted.await()
                    val token = checkNotNull(
                        host.onTransferStarted(
                            context = entryContext,
                            operationId = request.operationId,
                            attemptId = request.attemptId
                        )
                    )
                    assertTrue(
                        host.onCoreCommitted(
                            context = entryContext,
                            operationId = request.operationId,
                            attemptId = request.attemptId,
                            transferOwnerToken = token
                        )
                    )
                    secondStarted.complete(Unit)
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 }
        )

        val pump = async { host.pump(context) }
        withContext(Dispatchers.Default) {
            withTimeout(2_000L) {
                firstCommitted.await()
                secondStarted.await()
            }
        }
        assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
        assertEquals("COMPLETED", store.currentState(context, first.operationId))
        assertEquals("COMPLETED", store.currentState(context, second.operationId))
    }

    @Test
    fun `released physical permit repairs a lost host transfer callback before pump refill`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val first = DownloadExecutionRequest(
            operationId = "operation-lost-transfer-first",
            song = sampleSong().copy(id = 51_100L),
            attemptId = 31L
        )
        val second = DownloadExecutionRequest(
            operationId = "operation-lost-transfer-second",
            song = sampleSong().copy(id = 51_101L),
            attemptId = 32L
        )
        store.save(context, first)
        store.save(context, second)
        val heldPermitOwners = linkedSetOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCleanup = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { entryContext, request ->
                when (request.operationId) {
                    first.operationId -> {
                        assertNotNull(
                            host.onTransferStarted(
                                context = entryContext,
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                transferPermitOwnerKey = "permit-first"
                            )
                        )
                        firstStarted.complete(Unit)
                        firstCleanup.await()
                    }

                    second.operationId -> {
                        assertNotNull(
                            host.onTransferStarted(
                                context = entryContext,
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                transferPermitOwnerKey = "permit-second"
                            )
                        )
                        secondStarted.complete(Unit)
                    }
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 },
            transferPermitOwnersProvider = {
                synchronized(heldPermitOwners) { heldPermitOwners.toSet() }
            }
        )

        synchronized(heldPermitOwners) {
            heldPermitOwners += "permit-first"
        }
        val firstExecution = async { host.execute(context, first.operationId) }
        firstStarted.await()

        // 模拟网络断开或宿主停止：真实 permit 已在 finally 释放，但旧宿主回调尚未到达
        synchronized(heldPermitOwners) {
            heldPermitOwners.remove("permit-first")
            heldPermitOwners += "permit-second"
        }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    assertEquals(DownloadExecutionPumpResult.Completed, host.pump(context))
                    secondStarted.await()
                }
            }
            assertEquals("COMPLETED", store.currentState(context, second.operationId))
        } finally {
            firstCleanup.complete(Unit)
        }
        assertEquals(DownloadExecutionResult.Accepted, firstExecution.await())
    }

    @Test
    fun `released physical permit retries failed durable admission release`() = runTest {
        val context = mockContext()
        val delegate = InMemoryDownloadExecutionOperationJournal()
        val journal = CapacityBoundDownloadExecutionJournal(
            delegate = delegate,
            admissionCapacity = 1,
            releaseFailuresBeforeSuccess = 1
        )
        val store = DownloadExecutionOperationStore { journal }
        val first = DownloadExecutionRequest(
            operationId = "operation-lost-admission-first",
            song = sampleSong().copy(id = 51_110L),
            attemptId = 41L
        )
        val second = DownloadExecutionRequest(
            operationId = "operation-lost-admission-second",
            song = sampleSong().copy(id = 51_111L),
            attemptId = 42L
        )
        store.save(context, first)
        store.save(context, second)
        val heldPermitOwners = linkedSetOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val coreReleaseFailed = CompletableDeferred<Unit>()
        val firstCleanup = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { entryContext, request ->
                when (request.operationId) {
                    first.operationId -> {
                        val token = checkNotNull(
                            host.onTransferStarted(
                                context = entryContext,
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                transferPermitOwnerKey = "permit-admission-first"
                            )
                        )
                        firstStarted.complete(Unit)
                        assertFalse(
                            host.onCoreCommitted(
                                context = entryContext,
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                transferOwnerToken = token
                            )
                        )
                        coreReleaseFailed.complete(Unit)
                        firstCleanup.await()
                    }

                    second.operationId -> {
                        assertNotNull(
                            host.onTransferStarted(
                                context = entryContext,
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                transferPermitOwnerKey = "permit-admission-second"
                            )
                        )
                        secondStarted.complete(Unit)
                    }
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 },
            transferPermitOwnersProvider = {
                synchronized(heldPermitOwners) { heldPermitOwners.toSet() }
            }
        )

        synchronized(heldPermitOwners) {
            heldPermitOwners += "permit-admission-first"
        }
        val firstExecution = async { host.execute(context, first.operationId) }
        firstStarted.await()
        coreReleaseFailed.await()

        // 模拟网络 finally 已经归还真实 permit，但 Core Commit 的 Room 释放曾失败
        synchronized(heldPermitOwners) {
            heldPermitOwners.remove("permit-admission-first")
            heldPermitOwners += "permit-admission-second"
        }
        firstCleanup.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, firstExecution.await())
        assertEquals(0, delegate.hostAdmissionReleaseCount)

        withContext(Dispatchers.Default) {
            withTimeout(2_000L) {
                assertEquals(DownloadExecutionPumpResult.Completed, host.pump(context))
                secondStarted.await()
            }
        }
        assertEquals(2, delegate.hostAdmissionReleaseCount)
        assertEquals("COMPLETED", store.currentState(context, second.operationId))
    }

    @Test
    fun `core commit fences stale attempt and duplicate owner callbacks`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-core-fence",
            song = sampleSong(),
            attemptId = 7L
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                started.complete(Unit)
                finish.await()
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        val ownerToken = host.onTransferStarted(
            context = context,
            operationId = request.operationId,
            attemptId = request.attemptId
        )
        assertNotNull(ownerToken)
        assertFalse(
            host.onCoreCommitted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId,
                transferOwnerToken = null
            )
        )
        assertFalse(
            host.onCoreCommitted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId!! + 1L,
                transferOwnerToken = ownerToken
            )
        )
        assertFalse(
            host.onCoreCommitted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId,
                transferOwnerToken = ownerToken!! + 1L
            )
        )
        assertTrue(
            host.onCoreCommitted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId,
                transferOwnerToken = ownerToken
            )
        )
        assertFalse(
            host.onCoreCommitted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId,
                transferOwnerToken = ownerToken
            )
        )
        assertEquals(1, journal.hostAdmissionReleaseCount)

        finish.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, execution.await())
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `core commit releases the old transfer lane when the durable attempt refreshes`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-core-attempt-refresh",
            song = sampleSong(),
            attemptId = 7L
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                started.complete(Unit)
                finish.await()
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 }
        )

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        val ownerToken = checkNotNull(
            host.onTransferStarted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId
            )
        )

        // a retry/user-intent refresh can update the payload while the old
        // transfer is already durable. the old callback must still free its lanes
        journal.forceRequest(request.copy(attemptId = 19L))

        assertTrue(
            host.onCoreCommitted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId,
                transferOwnerToken = ownerToken
            )
        )
        val occupancy = host.transferLaneOccupancy(context)
        assertEquals(0, occupancy)
        // the old transfer release also frees the same-generation host admission
        assertEquals(1, journal.hostAdmissionReleaseCount)

        finish.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, execution.await())
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `durable attempt refresh frees admission for a successor before enrichment finishes`() = runTest {
        val context = mockContext()
        val delegate = InMemoryDownloadExecutionOperationJournal()
        val journal = CapacityBoundDownloadExecutionJournal(delegate, admissionCapacity = 1)
        val store = DownloadExecutionOperationStore { journal }
        val first = DownloadExecutionRequest(
            operationId = "operation-core-successor-first",
            song = sampleSong().copy(id = 43L),
            attemptId = 7L
        )
        val second = DownloadExecutionRequest(
            operationId = "operation-core-successor-second",
            song = sampleSong().copy(id = 44L),
            attemptId = 8L
        )
        store.save(context, first)
        store.save(context, second)
        val firstStarted = CompletableDeferred<Unit>()
        val firstCommitted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { entryContext, request ->
                val ownerToken = checkNotNull(
                    host.onTransferStarted(
                        context = entryContext,
                        operationId = request.operationId,
                        attemptId = request.attemptId
                    )
                )
                if (request.operationId == first.operationId) {
                    firstStarted.complete(Unit)
                    delegate.forceRequest(first.copy(attemptId = 19L))
                    assertTrue(
                        host.onCoreCommitted(
                            context = entryContext,
                            operationId = first.operationId,
                            attemptId = first.attemptId,
                            transferOwnerToken = ownerToken
                        )
                    )
                    firstCommitted.complete(Unit)
                    releaseFirst.await()
                } else {
                    secondStarted.complete(Unit)
                    assertTrue(
                        host.onCoreCommitted(
                            context = entryContext,
                            operationId = second.operationId,
                            attemptId = second.attemptId,
                            transferOwnerToken = ownerToken
                        )
                    )
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 }
        )

        val firstExecution = async { host.execute(context, first.operationId) }
        firstStarted.await()
        firstCommitted.await()
        val pump = async { host.pump(context) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    secondStarted.await()
                }
            }
            assertFalse(releaseFirst.isCompleted)
        } finally {
            releaseFirst.complete(Unit)
        }
        assertEquals(DownloadExecutionResult.Accepted, firstExecution.await())
        assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
        assertEquals("COMPLETED", store.currentState(context, first.operationId))
        assertEquals("COMPLETED", store.currentState(context, second.operationId))
        assertEquals(2, delegate.hostAdmissionReleaseCount)
    }

    @Test
    fun `pump drains rows left behind by a full database page`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val requests = (0 until 130).map { index ->
            DownloadExecutionRequest(
                operationId = "operation-pump-page-$index",
                song = sampleSong().copy(id = 30_000L + index)
            )
        }
        requests.forEach { request -> store.save(context, request) }
        val executed = mutableListOf<String>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                executed += request.operationId
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 }
        )

        assertEquals(DownloadExecutionPumpResult.Completed, host.pump(context))
        assertEquals(requests.size, executed.size)
        assertEquals(
            requests.map(DownloadExecutionRequest::operationId).toSet(),
            executed.toSet()
        )
    }
}
