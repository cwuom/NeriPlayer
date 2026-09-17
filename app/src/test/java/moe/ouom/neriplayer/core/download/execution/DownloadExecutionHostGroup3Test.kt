package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.host.DownloadOperationEntryPoint
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.host.canScheduleDownloadOperation
import moe.ouom.neriplayer.core.download.execution.host.releaseTransferReservation
import moe.ouom.neriplayer.core.download.execution.host.requiresTransferHostAdmission
import moe.ouom.neriplayer.core.download.execution.host.reserveTransferSlot
import moe.ouom.neriplayer.core.download.execution.host.resolveConcurrentExecutionResult
import moe.ouom.neriplayer.core.download.execution.host.shouldBlockHostReschedule
import moe.ouom.neriplayer.core.download.execution.host.shouldHandleHostStop
import moe.ouom.neriplayer.core.download.execution.host.stopInternal
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.download.execution.persistence.METADATA_ACTION_REQUIRED_OPERATION_STATE
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


class DownloadExecutionHostGroup3Test : DownloadExecutionHostTestSupport() {

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
    fun `post core recovery does not consume transfer host admission`() {
        listOf("CORE_COMMITTED", "ASSETS_ENRICHING", "DEGRADED_COMPLETE").forEach { state ->
            assertFalse(state, requiresTransferHostAdmission(state))
        }
        listOf(null, "PENDING_QUEUE", "QUEUED", "RETRYABLE", "RUNNING", "COMMITTING")
            .forEach { state ->
                assertTrue(state, requiresTransferHostAdmission(state))
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
    fun `host stop holds the scheduling permit through retry queue persistence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/host/" +
                "DownloadExecutionHostExecution.kt"
        ).readText()
        val stopBody = methodBody(source, "stopInternal")

        val permitIndex = stopBody.indexOf(
            "PersistentDownloadClearFenceStore.withSchedulingPermit("
        )
        val fenceCancellationIndex = stopBody.indexOf("cancel(appContext, normalizedId)")
        val stateIndex = stopBody.indexOf("operationStore.updateState(")
        val queueStopIndex = stopBody.indexOf(
            "GlobalDownloadManager.stopDownloadOperation("
        )

        assertTrue(permitIndex >= 0)
        assertTrue(fenceCancellationIndex > permitIndex)
        assertTrue(stateIndex > permitIndex)
        assertTrue(queueStopIndex > stateIndex)
    }

    @Test
    fun `execution host keeps pump and worker database access suspending`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/host/" +
                "DownloadExecutionHost.kt"
        ).readText()

        assertFalse(
            "execute must not block a shared worker thread on Room",
            methodBody(source, "execute").contains("runBlocking")
        )
        assertFalse(
            "pump must not block a shared worker thread on Room",
            methodBody(source, "pump").contains("runBlocking")
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
    fun `duplicate execution does not clear the active transfer owner`() = runTest {
        val context = mockContext()
        val store = DownloadExecutionOperationStore { testJournal }
        val request = DownloadExecutionRequest(
            operationId = "operation-duplicate-owner",
            song = sampleSong(),
            attemptId = 11L
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

        val activeExecution = async { host.execute(context, request.operationId) }
        started.await()

        assertEquals(
            DownloadExecutionResult.AlreadyHandled,
            host.execute(context, request.operationId)
        )
        assertTrue(host.isExecuting(request.operationId))
        assertNotNull(
            host.onTransferStarted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId
            )
        )

        release.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, activeExecution.await())
    }

    @Test
    fun `uidt winner keeps the pump reservation until transfer starts`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-uidt-reservation-handoff",
            song = sampleSong(),
            attemptId = 17L
        )
        store.save(context, request)
        val started = CompletableDeferred<Unit>()
        val allowTransfer = CompletableDeferred<Unit>()
        var targetToken = 0L
        lateinit var host: DefaultDownloadExecutionHost
        host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { entryContext, entryRequest ->
                started.complete(Unit)
                allowTransfer.await()
                assertEquals(
                    targetToken,
                    host.onTransferStarted(
                        context = entryContext,
                        operationId = entryRequest.operationId,
                        attemptId = entryRequest.attemptId
                    )
                )
                DownloadExecutionResult.Accepted
            },
            sdkInt = 28,
            downloadParallelismProvider = { 3 }
        )

        targetToken = checkNotNull(
            host.reserveTransferSlot(
                request.operationId,
                request.attemptId,
                4
            )
        )
        host.reserveTransferSlot("operation-reservation-peer-1", 21L, 4)
        host.reserveTransferSlot("operation-reservation-peer-2", 22L, 4)
        host.reserveTransferSlot("operation-reservation-peer-3", 23L, 4)

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        host.releaseTransferReservation(request.operationId, targetToken)
        allowTransfer.complete(Unit)

        assertEquals(DownloadExecutionResult.Accepted, execution.await())
    }

    @Test
    fun `pump does not reserve a slot for an already executing operation`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-pump-after-uidt-claim",
            song = sampleSong(),
            attemptId = 19L
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
            sdkInt = 28,
            downloadParallelismProvider = { 3 }
        )

        val execution = async { host.execute(context, request.operationId) }
        started.await()

        assertNull(
            host.reserveTransferSlot(
                request.operationId,
                request.attemptId,
                3
            )
        )
        assertNotNull(
            host.onTransferStarted(
                context = context,
                operationId = request.operationId,
                attemptId = request.attemptId
            )
        )

        finish.complete(Unit)
        assertEquals(DownloadExecutionResult.Accepted, execution.await())
    }

    @Test
    fun `uidt transfer is not blocked by pump reservations`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-uidt-behind-pump-reservations",
            song = sampleSong(),
            attemptId = 23L
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
            sdkInt = 28,
            downloadParallelismProvider = { 3 }
        )
        val peerTokens = (1..3).map { index ->
            checkNotNull(
                host.reserveTransferSlot(
                    "operation-pump-reservation-peer-$index",
                    index.toLong(),
                    3
                )
            )
        }

        val execution = async { host.execute(context, request.operationId) }
        started.await()
        try {
            assertNotNull(
                host.onTransferStarted(
                    context = context,
                    operationId = request.operationId,
                    attemptId = request.attemptId
                )
            )
        } finally {
            finish.complete(Unit)
            peerTokens.forEach { token ->
                host.releaseTransferReservation(
                    "operation-pump-reservation-peer-${peerTokens.indexOf(token) + 1}",
                    token
                )
            }
        }

        assertEquals(DownloadExecutionResult.Accepted, execution.await())
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
        assertEquals(1, testJournal.hostAdmissionAcquireCount)
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
}
