package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionPumpResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadOperationEntryPoint
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationJournal
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.player.download.resolveDownloadDispatchWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionRecoveryAdmissionRaceTest : DownloadExecutionHostTestSupport() {
    @Test
    fun `retry during occupancy lookup cannot launch the next queued window`() = runTest {
        verifyRecoveryAdmissionRace(failDuringAdmission = false)
    }

    @Test
    fun `retry during acquired admission releases it without starting queued audio`() = runTest {
        verifyRecoveryAdmissionRace(failDuringAdmission = true)
    }

    private suspend fun verifyRecoveryAdmissionRace(failDuringAdmission: Boolean) {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val window = resolveDownloadDispatchWindow(1)
        val requests = (1..window + 2).map { index ->
            DownloadExecutionRequest(
                operationId = "recovery-admission-$index",
                song = sampleSong().copy(id = index.toLong())
            )
        }
        val allowFailure = CountDownLatch(1)
        val failedExecutionReleased = CountDownLatch(1)
        val failedOnce = AtomicBoolean(false)
        val triggered = AtomicBoolean(false)
        val heldAdmissions = ConcurrentHashMap.newKeySet<String>()
        val firstId = requests.first().operationId
        val nextId = requests[window].operationId
        fun failAndWaitForRelease() {
            triggered.set(true)
            allowFailure.countDown()
            check(failedExecutionReleased.await(5, TimeUnit.SECONDS))
        }
        val observedJournal = object : DownloadExecutionOperationJournal by journal {
            override fun tryAcquireHostAdmission(context: Context, operationId: String, capacity: Int): Boolean {
                val acquired = journal.tryAcquireHostAdmission(context, operationId, capacity)
                if (acquired) heldAdmissions.add(operationId)
                if (failDuringAdmission && operationId == nextId && !triggered.get()) {
                    failAndWaitForRelease()
                }
                return acquired
            }

            override suspend fun tryAcquireHostAdmissionSuspending(
                context: Context, operationId: String, capacity: Int
            ) = tryAcquireHostAdmission(context, operationId, capacity)

            override fun releaseHostAdmission(context: Context, operationId: String) {
                journal.releaseHostAdmission(context, operationId)
                heldAdmissions.remove(operationId)
                if (operationId == firstId) failedExecutionReleased.countDown()
            }

            override suspend fun releaseHostAdmissionSuspending(context: Context, operationId: String) {
                releaseHostAdmission(context, operationId)
            }
        }
        val store = DownloadExecutionOperationStore { observedJournal }
        requests.forEach { store.save(context, it) }
        val executed = ConcurrentLinkedQueue<String>()
        val occupancyReads = AtomicInteger()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                executed.add(request.operationId)
                if (request.operationId == firstId && failedOnce.compareAndSet(false, true)) {
                    check(allowFailure.await(5, TimeUnit.SECONDS))
                    DownloadExecutionResult.Retry
                } else {
                    if (!failDuringAdmission || request.operationId != requests[1].operationId) {
                        check(failedExecutionReleased.await(5, TimeUnit.SECONDS))
                    }
                    DownloadExecutionResult.Accepted
                }
            },
            sdkInt = 28,
            downloadParallelismProvider = { 1 },
            transferPermitOwnersProvider = {
                if (!failDuringAdmission && occupancyReads.incrementAndGet() == 2) {
                    failAndWaitForRelease()
                }
                emptySet()
            }
        )
        try {
            assertEquals(DownloadExecutionPumpResult.ContinueAfterRetry, host.pump(context))
            assertTrue("the selected race boundary must actually execute", triggered.get())
            assertEquals(requests.take(window).map { it.operationId }.toSet(), executed.toSet())
            requests.drop(window).forEach { request ->
                assertEquals("QUEUED", store.currentState(context, request.operationId))
            }
            assertTrue("failed admission must not retain a transfer reservation", host.transferReservationOwners.isEmpty())
            assertTrue("failed admission must release durable host ownership", heldAdmissions.isEmpty())

            assertEquals(DownloadExecutionPumpResult.Completed, host.pump(context))
            requests.forEach { request ->
                assertEquals("COMPLETED", store.currentState(context, request.operationId))
            }
        } finally {
            allowFailure.countDown()
            failedExecutionReleased.countDown()
        }
    }
}
