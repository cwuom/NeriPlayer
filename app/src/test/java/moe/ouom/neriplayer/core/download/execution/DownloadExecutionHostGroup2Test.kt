package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.host.DownloadOperationEntryPoint
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearFenceReleaseResult
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearOwnership
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionPumpResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionSchedule
import moe.ouom.neriplayer.core.download.execution.host.enqueueDeferredSchedule
import moe.ouom.neriplayer.core.download.execution.host.resolveClaimFailureResult
import moe.ouom.neriplayer.core.download.execution.host.resolveExecutionCancellationResult
import moe.ouom.neriplayer.core.download.execution.host.triggerDeferredSchedules
import moe.ouom.neriplayer.core.download.execution.host.tryAcquireHostAdmission
import moe.ouom.neriplayer.core.download.execution.host.withDeferredSchedulingLock
import moe.ouom.neriplayer.core.download.execution.notification.DOWNLOAD_EXECUTION_NOTIFICATION_ID
import moe.ouom.neriplayer.core.download.execution.notification.isLegacyDownloadExecutionNotificationId
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.download.execution.persistence.METADATA_ACTION_REQUIRED_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.scheduling.DownloadRetryDeadlineWakeCoordinator
import moe.ouom.neriplayer.core.download.execution.uidt.UIDT_SHARED_PUMP_GRACE_MS
import moe.ouom.neriplayer.core.download.execution.uidt.scheduleUidtWithSharedPump
import moe.ouom.neriplayer.core.download.execution.uidt.shouldRescheduleUidtExecution
import moe.ouom.neriplayer.core.download.execution.worker.DownloadExecutionNotificationIds
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.execution.worker.shouldHandoffWifiBoundDownloadWake
import moe.ouom.neriplayer.core.download.execution.worker.shouldRetireLegacyPerOperationWork
import moe.ouom.neriplayer.core.download.execution.worker.shouldRouteFallbackToSharedPump
import moe.ouom.neriplayer.core.download.execution.worker.shouldScheduleWifiBoundDownloadWakeup
import moe.ouom.neriplayer.core.download.execution.worker.toWorkerResult
import moe.ouom.neriplayer.core.download.execution.worker.wifiBoundDownloadWakeExistingWorkPolicy
import moe.ouom.neriplayer.core.download.execution.worker.wifiBoundDownloadWakeHandoffRearmPolicy
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


class DownloadExecutionHostGroup2Test : DownloadExecutionHostTestSupport() {

    @Test
    fun `pump selection reads each durable page once across scale sizes`() = runTest {
        val scaleSizes = listOf(10, 100, 500, 1_000)
        scaleSizes.forEach { size ->
            val context = mockContext()
            val journal = InMemoryDownloadExecutionOperationJournal()
            val store = DownloadExecutionOperationStore { journal }
            val requests = (0 until size).map { index ->
                DownloadExecutionRequest(
                    operationId = "operation-pump-scale-$size-$index",
                    song = sampleSong().copy(id = 40_000L + index)
                )
            }
            requests.forEach { request -> store.save(context, request) }
            val host = DefaultDownloadExecutionHost(
                operationStore = store,
                entryPoint = DownloadOperationEntryPoint { _, _ ->
                    DownloadExecutionResult.Accepted
                },
                sdkInt = 28,
                downloadParallelismProvider = { 8 }
            )

            DownloadPumpSelectionTrace.clearForTests()
            var pumpResult = host.pump(context)
            var pumpRuns = 1
            while (pumpResult == DownloadExecutionPumpResult.ContinueSoon && pumpRuns < 8) {
                pumpResult = host.pump(context)
                pumpRuns++
            }
            assertEquals(DownloadExecutionPumpResult.Completed, pumpResult)

            val dispatchWindow = resolveDownloadDispatchWindow(8)
            val expectedPageCount = (size + dispatchWindow - 1) / dispatchWindow
            assertTrue(journal.pumpPageCallCount <= expectedPageCount + 1)
            assertTrue(
                DownloadPumpSelectionTrace.snapshot()
                    .maxOf { sample -> sample.rowsRead } <= dispatchWindow
            )
        }
    }

    @Test
    fun `pump schedules one delayed wake for a future durable retry deadline`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal().apply {
            nextPumpRetryDeadlineMs = 2_000L
        }
        val store = DownloadExecutionOperationStore { journal }
        val delays = mutableListOf<Long>()
        val wakeCoordinator = DownloadRetryDeadlineWakeCoordinator(
            nowMs = { 1_000L },
            schedulePump = { _, delayMs ->
                delays += delayMs
                true
            }
        )
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28,
            retryDeadlineWakeCoordinator = wakeCoordinator
        )

        assertEquals(DownloadExecutionPumpResult.Completed, host.pump(context))
        assertEquals(listOf(1_000L), delays)
        assertEquals(2_000L, wakeCoordinator.scheduledDeadlineForTests())
    }

    @Test
    fun `pump scans past a UIDT grace blocked first page`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val graceRequests = (1..64).map { index ->
            DownloadExecutionRequest(
                operationId = "operation-pump-grace-$index",
                song = sampleSong().copy(id = 10_000L + index)
            )
        }
        val runnableRequest = DownloadExecutionRequest(
            operationId = "operation-pump-page-two",
            song = sampleSong().copy(id = 20_000L)
        )
        (graceRequests + runnableRequest).forEach { request ->
            store.save(context, request)
        }
        val graceOperationIds = graceRequests.mapTo(mutableSetOf()) { request ->
            request.operationId
        }
        val executedOperationIds = mutableListOf<String>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                executedOperationIds += request.operationId
                DownloadExecutionResult.Accepted
            },
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            downloadParallelismProvider = { 1 },
            pendingUidtGraceDelayProvider = { _, request ->
                if (request.operationId in graceOperationIds) 1L else 0L
            }
        )

        assertEquals(DownloadExecutionPumpResult.ContinueAfterContention, host.pump(context))
        assertEquals(listOf(runnableRequest.operationId), executedOperationIds)
        assertEquals(
            "COMPLETED",
            store.currentState(context, runnableRequest.operationId)
        )
    }

    @Test
    fun `UIDT grace predecessor does not hide a runnable replacement for the same song`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val song = sampleSong().copy(id = 20_001L)
        val graceBlocked = DownloadExecutionRequest(
            operationId = "operation-pump-grace-predecessor",
            song = song
        )
        val replacement = DownloadExecutionRequest(
            operationId = "operation-pump-runnable-replacement",
            song = song
        )
        store.save(context, graceBlocked)
        store.save(context, replacement)
        val executedOperationIds = mutableListOf<String>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                executedOperationIds += request.operationId
                DownloadExecutionResult.Accepted
            },
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            downloadParallelismProvider = { 1 },
            pendingUidtGraceDelayProvider = { _, request ->
                if (request.operationId == graceBlocked.operationId) 1L else 0L
            }
        )

        assertEquals(DownloadExecutionPumpResult.ContinueAfterContention, host.pump(context))
        assertEquals(listOf(replacement.operationId), executedOperationIds)
        assertEquals(
            "COMPLETED",
            store.currentState(context, replacement.operationId)
        )
    }

    @Test
    fun `download notification ids are shared across operations and backends`() {
        val firstOperation = "operation-notification-01"
        val secondOperation = "operation-notification-02"
        val firstForegroundId = DownloadExecutionNotificationIds.foreground(firstOperation)
        val secondForegroundId = DownloadExecutionNotificationIds.foreground(secondOperation)
        val firstUidtId = DownloadExecutionNotificationIds.uidt(firstOperation)
        val secondUidtId = DownloadExecutionNotificationIds.uidt(secondOperation)

        assertEquals(
            firstForegroundId,
            DownloadExecutionNotificationIds.foreground(firstOperation)
        )
        assertEquals(firstUidtId, DownloadExecutionNotificationIds.uidt(firstOperation))
        assertEquals(DOWNLOAD_EXECUTION_NOTIFICATION_ID, firstForegroundId)
        assertEquals(DOWNLOAD_EXECUTION_NOTIFICATION_ID, firstUidtId)
        assertEquals(firstForegroundId, secondForegroundId)
        assertEquals(firstUidtId, secondUidtId)
        assertEquals(firstForegroundId, firstUidtId)
    }

    @Test
    fun `legacy notification ranges are isolated from current and unrelated ids`() {
        assertTrue(
            isLegacyDownloadExecutionNotificationId(
                DownloadExecutionNotificationIds.FOREGROUND_MIN
            )
        )
        assertTrue(
            isLegacyDownloadExecutionNotificationId(
                DownloadExecutionNotificationIds.UIDT_MAX
            )
        )
        assertFalse(
            isLegacyDownloadExecutionNotificationId(DOWNLOAD_EXECUTION_NOTIFICATION_ID)
        )
        assertFalse(isLegacyDownloadExecutionNotificationId(1001))
    }

    @Test
    fun `Wi-Fi wake work only resumes Wi-Fi-bound reusable operations`() {
        assertTrue(
            shouldScheduleWifiBoundDownloadWakeup(
                requiresWifiNetwork = true,
                operationState = "RETRYABLE"
            )
        )
        assertFalse(
            shouldScheduleWifiBoundDownloadWakeup(
                requiresWifiNetwork = false,
                operationState = "RETRYABLE"
            )
        )
        assertFalse(
            shouldScheduleWifiBoundDownloadWakeup(
                requiresWifiNetwork = true,
                operationState = "RUNNING"
            )
        )
    }

    @Test
    fun `Wi-Fi wake only appends for a host handoff that must survive an active wake`() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            wifiBoundDownloadWakeExistingWorkPolicy
        )
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            wifiBoundDownloadWakeHandoffRearmPolicy
        )
    }

    @Test
    fun `Wi-Fi wake only hands off while the default route remains Wi-Fi-class`() {
        assertTrue(shouldHandoffWifiBoundDownloadWake(TrafficNetworkType.WIFI))
        assertFalse(shouldHandoffWifiBoundDownloadWake(TrafficNetworkType.MOBILE))
        assertFalse(shouldHandoffWifiBoundDownloadWake(TrafficNetworkType.ROAMING))
        assertFalse(shouldHandoffWifiBoundDownloadWake(null))
    }

    @Test
    fun `transient pump operation retry uses a successor instead of WorkManager backoff`() {
        assertEquals(
            ListenableWorker.Result.success()::class,
            DownloadExecutionPumpResult.ContinueAfterRetry.toWorkerResult()::class
        )
    }

    @Test
    fun `ready pump successor has no WorkManager delay`() {
        assertEquals(
            0L,
            ForegroundDownloadWorker.successorDelayMsFor(
                DownloadExecutionPumpResult.ContinueSoon
            )
        )
        assertEquals(
            1_000L,
            ForegroundDownloadWorker.successorDelayMsFor(
                DownloadExecutionPumpResult.ContinueAfterRetry
            )
        )
        assertEquals(
            UIDT_SHARED_PUMP_GRACE_MS,
            ForegroundDownloadWorker.successorDelayMsFor(
                DownloadExecutionPumpResult.ContinueAfterContention
            )
        )
    }

    @Test
    fun `missing operation terminates WorkManager without an infinite retry`() {
        assertEquals(
            ListenableWorker.Result.success()::class,
            DownloadExecutionResult.MissingOperation.toWorkerResult()::class
        )
    }

    @Test
    fun `metadata action required terminates WorkManager without an infinite retry`() {
        assertEquals(
            ListenableWorker.Result.success()::class,
            DownloadExecutionResult.UserActionRequired.toWorkerResult()::class
        )
    }

    @Test
    fun `claim loss keeps a queued replacement available for a later pump`() {
        assertEquals(
            DownloadExecutionResult.Retry,
            resolveClaimFailureResult(
                currentState = "QUEUED",
                userStopped = false
            )
        )
        assertEquals(
            DownloadExecutionResult.Retry,
            resolveClaimFailureResult(
                currentState = "RETRYABLE",
                userStopped = false
            )
        )
    }

    @Test
    fun `claim loss honors cancellation and user stop before retry`() {
        assertEquals(
            DownloadExecutionResult.Cancelled,
            resolveClaimFailureResult(
                currentState = "CANCEL_REQUESTED",
                userStopped = false
            )
        )
        assertEquals(
            DownloadExecutionResult.UserStopped,
            resolveClaimFailureResult(
                currentState = "QUEUED",
                userStopped = true
            )
        )
    }

    @Test
    fun `network policy waiting terminates WorkManager without an infinite retry`() {
        assertEquals(
            ListenableWorker.Result.success()::class,
            DownloadExecutionResult.NetworkPolicyWaiting.toWorkerResult()::class
        )
    }

    @Test
    fun `terminal operation states do not enter host admission or retry`() = runTest {
        val terminalStates = listOf(
            "COMPLETED" to DownloadExecutionResult.AlreadyHandled,
            "FINALIZED" to DownloadExecutionResult.AlreadyHandled,
            "INVALID" to DownloadExecutionResult.MissingOperation,
            "CANCEL_REQUESTED" to DownloadExecutionResult.Cancelled,
            "CANCELLED" to DownloadExecutionResult.Cancelled,
            METADATA_ACTION_REQUIRED_OPERATION_STATE to
                DownloadExecutionResult.UserActionRequired
        )

        terminalStates.forEachIndexed { index, (state, expectedResult) ->
            val context = mockContext()
            val journal = InMemoryDownloadExecutionOperationJournal()
            val store = DownloadExecutionOperationStore { journal }
            val request = DownloadExecutionRequest(
                operationId = "operation-terminal-$index",
                song = sampleSong().copy(id = 20_000L + index)
            )
            store.save(context, request)
            journal.forceState(request.operationId, state, updatedAtMs = 1L)
            val host = DefaultDownloadExecutionHost(
                operationStore = store,
                entryPoint = DownloadOperationEntryPoint { _, _ ->
                    error("terminal operation must not enter the entry point")
                },
                sdkInt = 28
            )

            assertEquals(expectedResult, host.execute(context, request.operationId))
            assertEquals(0, journal.hostAdmissionAcquireCount)
        }
    }

    @Test
    fun `detached enrichment settles hosts without overwriting durable core state`() = runTest {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                DownloadExecutionResult.AlreadyHandled
            },
            sdkInt = 28
        )

        listOf("CORE_COMMITTED", "ASSETS_ENRICHING").forEachIndexed { index, state ->
            val request = DownloadExecutionRequest(
                operationId = "detached-enrichment-$index",
                song = sampleSong().copy(id = 10_000L + index)
            )
            store.save(context, request)
            testJournal.forceState(request.operationId, state, updatedAtMs = 1L)

            assertEquals(
                DownloadExecutionResult.AlreadyHandled,
                host.execute(context, request.operationId)
            )
            assertEquals(state, store.currentState(context, request.operationId))
        }

        assertEquals(
            ListenableWorker.Result.success()::class,
            DownloadExecutionResult.AlreadyHandled.toWorkerResult()::class
        )
        assertFalse(shouldRescheduleUidtExecution(DownloadExecutionResult.AlreadyHandled))
    }

    @Test
    fun `UIDT fallback work waits briefly before claiming the operation`() {
        val request = ForegroundDownloadWorker.buildFallbackRequest("operation-fallback")

        assertEquals(250L, request.workSpec.initialDelay)
        assertEquals(
            NetworkType.CONNECTED,
            request.workSpec.constraints.requiredNetworkType
        )
        assertTrue(request.tags.contains("download_execution_all"))
        assertEquals(
            ExistingWorkPolicy.KEEP,
            ForegroundDownloadWorker.fallbackExistingWorkPolicy
        )
        assertEquals(
            "download_execution_fallback_operation-fallback",
            ForegroundDownloadWorker.fallbackWorkName("operation-fallback")
        )
        assertTrue(
            ForegroundDownloadWorker.fallbackWorkName("operation-fallback") !=
                ForegroundDownloadWorker.fallbackWorkName("operation-other")
        )
    }

    @Test
    fun `API 34 retires every legacy per operation work`() {
        assertTrue(
            shouldRetireLegacyPerOperationWork(
                hasOperationId = true,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )
        )
        assertFalse(
            shouldRetireLegacyPerOperationWork(
                hasOperationId = false,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )
        )
        assertFalse(
            shouldRetireLegacyPerOperationWork(
                hasOperationId = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU
            )
        )
    }

    @Test
    fun `API 34 routes new fallback requests to the shared pump`() {
        assertTrue(
            shouldRouteFallbackToSharedPump(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        assertTrue(
            shouldRouteFallbackToSharedPump(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        )
        assertFalse(shouldRouteFallbackToSharedPump(Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun `successful UIDT arms one shared pump after the UIDT request`() {
        val actions = mutableListOf<String>()

        val scheduled = scheduleUidtWithSharedPump(
            scheduleUidt = {
                actions += "uidt"
                true
            },
            scheduleSharedPump = {
                actions += "pump"
                true
            }
        )

        assertTrue(scheduled)
        assertEquals(listOf("uidt", "pump"), actions)
    }

    @Test
    fun `rejected UIDT does not create a per operation fallback`() {
        val actions = mutableListOf<String>()

        val scheduled = scheduleUidtWithSharedPump(
            scheduleUidt = {
                actions += "uidt"
                false
            },
            scheduleSharedPump = {
                actions += "pump"
                true
            }
        )

        assertFalse(scheduled)
        assertEquals(listOf("uidt"), actions)
    }

    @Test
    fun `temporary UIDT scheduling failure does not create a per operation fallback`() {
        val actions = mutableListOf<String>()

        val scheduled = scheduleUidtWithSharedPump(
            scheduleUidt = {
                actions += "uidt"
                error("binder unavailable")
            },
            scheduleSharedPump = {
                actions += "pump"
                true
            }
        )

        assertFalse(scheduled)
        assertEquals(listOf("uidt"), actions)
    }

    @Test
    fun `terminal operation pruning is bounded and honors cutoff`() {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val old = DownloadExecutionRequest(
            operationId = "operation-old-terminal",
            song = sampleSong().copy(id = 1L)
        )
        val recent = DownloadExecutionRequest(
            operationId = "operation-recent-terminal",
            song = sampleSong().copy(id = 2L)
        )
        store.save(context, old)
        store.save(context, recent)
        testJournal.forceState(old.operationId, "COMPLETED", updatedAtMs = 10L)
        testJournal.forceState(recent.operationId, "CANCELLED", updatedAtMs = 100L)

        assertEquals(1, store.pruneTerminalOperations(context, cutoffMs = 50L, limit = 1))
        assertNull(store.read(context, old.operationId))
        assertNotNull(store.read(context, recent.operationId))
    }

    @Test
    fun `execution forwards the durable operation id to the entry point`() = runTest {
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-forwarded",
            song = sampleSong()
        )
        val store = DownloadExecutionOperationStore { testJournal }
        store.save(context, request)
        var forwardedOperationId: String? = null
        val entryPoint = DownloadOperationEntryPoint { _, restoredRequest ->
            forwardedOperationId = restoredRequest.operationId
            DownloadExecutionResult.Accepted
        }
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = entryPoint,
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Accepted,
            host.execute(context, request.operationId)
        )
        assertEquals(request.operationId, forwardedOperationId)
    }

    @Test
    fun `execution retries before entry point when the durable host window is full`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal().apply {
            hostAdmissionAllowed = false
        }
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-host-window",
            song = sampleSong()
        )
        store.save(context, request)
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(DownloadExecutionResult.Retry, host.execute(context, request.operationId))
        assertEquals(0, executions)
        assertEquals("QUEUED", store.currentState(context, request.operationId))
        assertEquals(1, journal.hostAdmissionAcquireCount)
        assertEquals(0, journal.hostAdmissionReleaseCount)

        journal.hostAdmissionAllowed = true

        assertEquals(DownloadExecutionResult.Accepted, host.execute(context, request.operationId))
        assertEquals(1, executions)
        assertEquals(2, journal.hostAdmissionAcquireCount)
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `execution waits for the shared entry point to finish`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-wait",
            song = sampleSong()
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                started.complete(Unit)
                release.await()
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        assertTrue(!execution.isCompleted)
        release.complete(Unit)

        assertEquals(DownloadExecutionResult.Accepted, execution.await())
    }

    @Test
    fun `idle handoff admission is released before a worker retries`() {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-idle-handoff",
            song = sampleSong()
        )
        store.save(context, request)
        assertTrue(store.tryAcquireHostAdmission(context, request.operationId, capacity = 1))
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28
        )

        host.releaseHandoffAdmissionIfIdle(context, request.operationId)

        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `handoff release cannot remove an admission owned by active execution`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-active-handoff",
            song = sampleSong()
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
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
        host.releaseHandoffAdmissionIfIdle(context, request.operationId)

        assertEquals(0, journal.hostAdmissionReleaseCount)
        finish.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, execution.await())
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `batch cancellation keeps active admission until execution finally`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-cancel-active",
            song = sampleSong()
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
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
        host.cancelAll(context, listOf(request.operationId))

        assertEquals(0, journal.hostAdmissionReleaseCount)
        finish.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, execution.await())
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `owned batch cancellation keeps active admission until execution finally`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-cancel-owned-active",
            song = sampleSong()
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
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
        host.cancelAllOwned(context)

        assertEquals(0, journal.hostAdmissionReleaseCount)
        finish.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, execution.await())
        assertEquals(1, journal.hostAdmissionReleaseCount)
    }

    @Test
    fun `execution persists the operation scoped entry point result`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-scoped-result",
            song = sampleSong(),
            attemptId = 23L
        )
        store.save(context, request)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, restoredRequest ->
                assertEquals(request.operationId, restoredRequest.operationId)
                assertEquals(request.attemptId, restoredRequest.attemptId)
                DownloadExecutionResult.Retry
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Retry,
            host.execute(context, request.operationId)
        )
        assertEquals("RETRYABLE", store.currentState(context, request.operationId))
    }

    @Test
    fun `network policy wait retries when its durable Wi-Fi wake cannot be armed`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-network-policy-wait",
            song = sampleSong()
        )
        store.save(context, request)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                DownloadExecutionResult.NetworkPolicyWaiting
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Retry,
            host.execute(context, request.operationId)
        )
        assertEquals("RETRYABLE", store.currentState(context, request.operationId))
    }

    @Test
    fun `execution rereads the latest attempt after claiming the operation`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val queued = DownloadExecutionRequest(
            operationId = "operation-reread-attempt",
            song = sampleSong(),
            attemptId = 7L
        )
        val refreshed = queued.copy(attemptId = 19L)
        store.save(context, queued)
        testJournal.afterStateUpdate = { operationId, state ->
            if (operationId == queued.operationId && state == "RUNNING") {
                testJournal.forceRequest(refreshed)
                testJournal.afterStateUpdate = null
            }
        }
        var receivedAttemptId: Long? = null
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                receivedAttemptId = request.attemptId
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Accepted,
            host.execute(context, queued.operationId)
        )
        assertEquals(19L, receivedAttemptId)
    }

    @Test
    fun `cancel accepted after claim prevents entry point execution`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-cancel-after-claim",
            song = sampleSong()
        )
        store.save(context, request)
        testJournal.afterStateUpdate = { operationId, state ->
            if (operationId == request.operationId && state == "RUNNING") {
                store.requestCancel(context, operationId)
                testJournal.afterStateUpdate = null
            }
        }
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Cancelled,
            host.execute(context, request.operationId)
        )
        assertEquals(0, executions)
        assertEquals("CANCEL_REQUESTED", store.currentState(context, request.operationId))
    }

    @Test
    fun `user stop accepted after claim prevents entry point execution`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-stop-after-claim",
            song = sampleSong()
        )
        store.save(context, request)
        testJournal.afterStateUpdate = { operationId, state ->
            if (operationId == request.operationId && state == "RUNNING") {
                store.markStopped(context, operationId)
                testJournal.afterStateUpdate = null
            }
        }
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.UserStopped,
            host.execute(context, request.operationId)
        )
        assertEquals(0, executions)
        assertTrue(store.isStopped(context, request.operationId))
    }

    @Test
    fun `temporarily rejected scheduling stays deferred with its durable operation`() {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val request = DownloadExecutionRequest(
            operationId = "operation-reject",
            song = sampleSong()
        )
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28
        )

        val result = host.schedule(context, request)

        assertTrue(result is DownloadExecutionSchedule.Deferred)
        assertNotNull(store.read(context, request.operationId))
        host.cancel(context, request.operationId)
    }

    @Test
    fun `deferred scheduler cannot lose an enqueue while its worker exits`() {
        val hostSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/host/" +
                "DownloadExecutionHost.kt"
        ).readText()
        val pumpSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/host/" +
                "DownloadExecutionHostPump.kt"
        ).readText()
        val enqueueBody = methodBody(pumpSource, "enqueueDeferredSchedule")
        val triggerBody = methodBody(pumpSource, "triggerDeferredSchedules")

        assertTrue(hostSource.contains("internal val deferredSchedulingLock = Any()"))
        assertTrue(enqueueBody.contains("withDeferredSchedulingLock"))
        assertTrue(triggerBody.contains("synchronized(deferredSchedulingLock)"))
        assertTrue(triggerBody.contains("deferredSchedulingRunning.set(false)"))
        assertTrue(triggerBody.contains("!deferredRequests.isEmpty()"))
    }

    @Test
    fun `active clear fence rejects scheduling and execution before entry point`() = runTest {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext(activeClearFence = true)
        val request = DownloadExecutionRequest(
            operationId = "operation-clear-fence",
            song = sampleSong()
        )
        store.save(context, request)
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertTrue(host.schedule(context, request) is DownloadExecutionSchedule.Rejected)
        assertEquals(
            DownloadExecutionResult.Cancelled,
            host.execute(context, request.operationId)
        )
        assertEquals(0, executions)
        assertEquals("CANCEL_REQUESTED", store.currentState(context, request.operationId))
    }

    @Test
    fun `unowned execution survives scoped clear fence release`() = runTest {
        val context = statefulMockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val owner = DownloadExecutionRequest(
            operationId = "operation-clear-owner",
            song = sampleSong()
        )
        val request = DownloadExecutionRequest(
            operationId = "operation-clear-unowned",
            song = sampleSong().copy(id = 43L)
        )
        store.save(context, request)
        val ownership = DownloadClearOwnership(
            operationIds = setOf(owner.operationId),
            stableKeys = setOf(owner.song.stableKey())
        )
        val clearEpoch = PersistentDownloadClearFenceStore.beginClear(
            purpose = DownloadClearPurpose.TASK_PROGRESS,
            ownership = ownership
        )

        try {
            assertTrue(
                PersistentDownloadClearFenceStore.activate(
                    context = context,
                    ownership = ownership
                )
            )
            assertTrue(
                PersistentDownloadClearFenceStore.setOwnership(
                    context = context,
                    expectedEpoch = clearEpoch,
                    ownership = ownership
                )
            )
            assertFalse(
                PersistentDownloadClearFenceStore.isBlocked(
                    context = context,
                    stableKey = request.song.stableKey(),
                    operationId = request.operationId
                )
            )

            var executions = 0
            var fenceReleased = false
            journal.afterStateUpdate = { operationId, state ->
                if (operationId == request.operationId && state == "RUNNING") {
                    assertEquals(
                        DownloadClearFenceReleaseResult.RELEASED,
                        PersistentDownloadClearFenceStore.clearIfCurrent(
                            context = context,
                            expectedEpoch = clearEpoch
                        )
                    )
                    assertEquals(
                        clearEpoch,
                        PersistentDownloadClearFenceStore.currentEpoch(context)
                    )
                    fenceReleased = true
                    journal.afterStateUpdate = null
                }
            }
            val host = DefaultDownloadExecutionHost(
                operationStore = store,
                entryPoint = DownloadOperationEntryPoint { _, _ ->
                    executions++
                    DownloadExecutionResult.Accepted
                },
                sdkInt = 28
            )

            assertEquals(
                DownloadExecutionResult.Accepted,
                host.execute(context, request.operationId)
            )
            assertTrue(fenceReleased)
            assertEquals(1, executions)
            assertEquals("COMPLETED", store.currentState(context, request.operationId))
            assertFalse(PersistentDownloadClearFenceStore.isActive(context))
        } finally {
            PersistentDownloadClearFenceStore.clearIfCurrent(
                context = context,
                expectedEpoch = clearEpoch
            )
        }
    }

    @Test
    fun `waiting storage mutation is never scheduled or executed`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-storage-mutation-wait",
            song = sampleSong()
        )
        store.save(context, request)
        journal.forceState(
            operationId = request.operationId,
            state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
            updatedAtMs = 1L
        )
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )

        assertTrue(host.schedule(context, request) is DownloadExecutionSchedule.Rejected)
        assertEquals(
            DownloadExecutionResult.AlreadyHandled,
            host.execute(context, request.operationId)
        )
        assertEquals(0, executions)
        assertEquals(0, journal.hostAdmissionAcquireCount)
    }

    @Test
    fun `storage cancellation is handled without cancelling sibling pump work`() {
        assertEquals(
            DownloadExecutionResult.AlreadyHandled,
            resolveExecutionCancellationResult(WAITING_STORAGE_MUTATION_OPERATION_STATE)
        )
        assertNull(resolveExecutionCancellationResult("RUNNING"))
    }

    @Test
    fun `attempt refresh during scheduling is deferred instead of misclassified as clear`() {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal().apply {
            hostAdmissionAllowed = false
        }
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-attempt-race",
            song = sampleSong(),
            attemptId = 31L
        )
        journal.afterSave = { saved ->
            if (saved.operationId == request.operationId && saved.attemptId == 31L) {
                journal.forceRequest(saved.copy(attemptId = 32L))
                journal.afterSave = null
            }
        }
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28
        )

        val result = host.schedule(context, request)

        assertTrue(result is DownloadExecutionSchedule.Deferred)
        assertEquals(32L, store.read(context, request.operationId)?.attemptId)
        assertFalse(store.currentState(context, request.operationId) == "CANCEL_REQUESTED")
        host.cancel(context, request.operationId)
    }
}
