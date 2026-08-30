package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.junit.Test

class DownloadExecutionHostTest {
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
    fun `download notification ids are stable and partitioned per backend`() {
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
        assertTrue(
            firstForegroundId in DownloadExecutionNotificationIds.FOREGROUND_MIN..
                DownloadExecutionNotificationIds.FOREGROUND_MAX
        )
        assertTrue(
            firstUidtId in DownloadExecutionNotificationIds.UIDT_MIN..
                DownloadExecutionNotificationIds.UIDT_MAX
        )
        assertTrue(firstForegroundId != secondForegroundId)
        assertTrue(firstUidtId != secondUidtId)
        assertTrue(firstForegroundId != firstUidtId)
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

        assertEquals(3_000L, request.workSpec.initialDelay)
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
    fun `successful UIDT keeps its unique fallback until the job starts`() {
        val actions = mutableListOf<String>()

        val scheduled = scheduleUidtKeepingFallback(
            scheduleFallback = { actions += "fallback" },
            scheduleUidt = {
                actions += "uidt"
                true
            },
            cancelFallback = { actions += "cancel" }
        )

        assertTrue(scheduled)
        assertEquals(listOf("fallback", "uidt"), actions)
    }

    @Test
    fun `rejected UIDT removes its armed fallback`() {
        val actions = mutableListOf<String>()

        val scheduled = scheduleUidtKeepingFallback(
            scheduleFallback = { actions += "fallback" },
            scheduleUidt = {
                actions += "uidt"
                false
            },
            cancelFallback = { actions += "cancel" }
        )

        assertFalse(scheduled)
        assertEquals(listOf("fallback", "uidt", "cancel"), actions)
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
    fun `scheduling refreshes the durable attempt before the worker starts`() {
        val store = DownloadExecutionOperationStore { testJournal }
        val context = mockContext()
        val queued = DownloadExecutionRequest(
            operationId = "operation-batch-attempt",
            song = sampleSong(),
            attemptId = null
        )
        store.save(context, queued)
        val scheduled = queued.copy(attemptId = 19L)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            sdkInt = 28
        )

        host.schedule(context, scheduled)

        assertEquals(19L, store.read(context, queued.operationId)?.attemptId)
    }

    @Test
    fun `host stop handles only resumable operation states`() {
        listOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE",
            "RETRYABLE",
            "STOPPED"
        ).forEach { state ->
            assertTrue("expected resumable state: $state", shouldHandleHostStop(state))
        }
        listOf("CANCEL_REQUESTED", "CANCELLED", "FINALIZED", "COMPLETED", "INVALID")
            .forEach { state ->
                assertFalse("expected terminal state: $state", shouldHandleHostStop(state))
            }
        assertFalse(shouldHandleHostStop(null))
    }

    @Test
    fun `scheduler can restore an interrupted durable operation without reopening terminal work`() {
        listOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE",
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        ).forEach { state ->
            assertTrue("expected schedulable state: $state", canScheduleDownloadOperation(state))
        }
        listOf("CANCEL_REQUESTED", "CANCELLED", "STOPPED", "COMPLETED", "INVALID")
            .forEach { state ->
                assertFalse("expected rejected state: $state", canScheduleDownloadOperation(state))
            }
    }

    @Test
    fun `worker cancellation cannot reschedule an explicitly stopped operation`() {
        assertTrue(
            shouldBlockHostReschedule(
                preventReschedule = false,
                alreadyStoppedByUser = true
            )
        )
        assertTrue(
            shouldBlockHostReschedule(
                preventReschedule = true,
                alreadyStoppedByUser = false
            )
        )
        assertFalse(
            shouldBlockHostReschedule(
                preventReschedule = false,
                alreadyStoppedByUser = false
            )
        )
    }

    @Test
    fun `fallback retries while a system stopped UIDT execution is unwinding`() {
        assertEquals(
            DownloadExecutionResult.Retry,
            resolveConcurrentExecutionResult(systemRetryStopPending = true)
        )
        assertEquals(
            DownloadExecutionResult.AlreadyHandled,
            resolveConcurrentExecutionResult(systemRetryStopPending = false)
        )
    }

    @Test
    fun `fallback already running remains owner when UIDT starts`() = runTest {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val request = DownloadExecutionRequest(
            operationId = "operation-fallback-first",
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

        val fallbackExecution = async { host.execute(context, request.operationId) }
        started.await()

        assertEquals(
            DownloadExecutionResult.AlreadyHandled,
            host.execute(context, request.operationId)
        )
        assertFalse(fallbackExecution.isCompleted)
        assertEquals("RUNNING", store.currentState(context, request.operationId))

        release.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, fallbackExecution.await())
        assertEquals("COMPLETED", store.currentState(context, request.operationId))
    }

    @Test
    fun `fresh host resumes interrupted commit and enrichment operations`() = runTest {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        var executions = 0
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                executions++
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28
        )
        val interruptedStates = listOf(
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        )

        interruptedStates.forEachIndexed { index, state ->
            val request = DownloadExecutionRequest(
                operationId = "operation-recover-$index",
                song = sampleSong().copy(id = index.toLong() + 1L)
            )
            store.save(context, request)
            testJournal.forceState(request.operationId, state, updatedAtMs = 1L)

            assertEquals(
                DownloadExecutionResult.Accepted,
                host.execute(context, request.operationId)
            )
            assertEquals("COMPLETED", store.currentState(context, request.operationId))
        }

        assertEquals(interruptedStates.size, executions)
    }

    @Test
    fun `unsupported metadata result persists a non schedulable action state`() = runTest {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val request = DownloadExecutionRequest(
            operationId = "operation-metadata-action-required",
            song = sampleSong()
        )
        store.save(context, request)
        testJournal.forceState(request.operationId, "DEGRADED_COMPLETE", updatedAtMs = 1L)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, _ ->
                DownloadExecutionResult.UserActionRequired
            },
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.UserActionRequired,
            host.execute(context, request.operationId)
        )
        assertEquals(
            METADATA_ACTION_REQUIRED_OPERATION_STATE,
            store.currentState(context, request.operationId)
        )
        assertFalse(
            canScheduleDownloadOperation(store.currentState(context, request.operationId))
        )
    }

    private val testJournal = InMemoryDownloadExecutionOperationJournal()

    private fun mockContext(activeClearFence: Boolean = false): Context {
        return mock(Context::class.java).also { context ->
            `when`(context.applicationContext).thenReturn(context)
            val preferences = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.getBoolean(anyString(), anyBoolean())).thenReturn(activeClearFence)
        }
    }

    private fun sampleSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 1234L,
            coverUrl = null
        )
    }

}
