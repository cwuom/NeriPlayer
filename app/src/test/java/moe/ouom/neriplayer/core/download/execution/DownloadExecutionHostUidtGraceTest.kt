package moe.ouom.neriplayer.core.download.execution

import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionPumpResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionResult
import moe.ouom.neriplayer.core.download.execution.host.DownloadOperationEntryPoint
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionHostUidtGraceTest : DownloadExecutionHostTestSupport() {
    @Test
    fun `expired UIDT grace is reconsidered while another operation is still running`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val waiting = DownloadExecutionRequest(
            operationId = "uidt-grace-waiting",
            song = sampleSong().copy(id = 80_001L)
        )
        val slow = DownloadExecutionRequest(
            operationId = "uidt-grace-slow",
            song = sampleSong().copy(id = 80_002L)
        )
        listOf(waiting, slow).forEach { store.save(context, it) }
        val graceExpired = AtomicBoolean(false)
        val waitingStarted = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                if (request.operationId == slow.operationId) {
                    graceExpired.set(true)
                    slowStarted.complete(Unit)
                    releaseSlow.await()
                } else {
                    waitingStarted.complete(Unit)
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            downloadParallelismProvider = { 1 },
            pendingUidtGraceDelayProvider = { _, request ->
                if (request.operationId == waiting.operationId && !graceExpired.get()) 100L else 0L
            }
        )

        val pump = async { host.pump(context) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    slowStarted.await()
                    waitingStarted.await()
                }
            }
            assertFalse(pump.isCompleted)
        } finally {
            releaseSlow.complete(Unit)
        }
        assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
        assertEquals("COMPLETED", store.currentState(context, waiting.operationId))
    }

    @Test
    fun `later pages do not forget a UIDT grace waiter before the pump drains`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val requests = (0..4).map { index ->
            DownloadExecutionRequest(
                operationId = "uidt-grace-pages-$index",
                song = sampleSong().copy(id = 81_000L + index)
            ).also { store.save(context, it) }
        }
        val graceExpired = AtomicBoolean(false)
        val started = requests.associate { it.operationId to CompletableDeferred<Unit>() }
        val releaseSlow = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                started.getValue(request.operationId).complete(Unit)
                when (request.operationId) {
                    requests[1].operationId, requests[2].operationId -> releaseSlow.await()
                    requests[3].operationId -> releaseThird.await()
                    requests[4].operationId -> graceExpired.set(true)
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            downloadParallelismProvider = { 1 },
            pendingUidtGraceDelayProvider = { _, request ->
                if (request.operationId == requests[0].operationId && !graceExpired.get()) 100L else 0L
            }
        )

        val pump = async { host.pump(context) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    requests.subList(1, 4).forEach { started.getValue(it.operationId).await() }
                    releaseThird.complete(Unit)
                    started.getValue(requests[4].operationId).await()
                    started.getValue(requests[0].operationId).await()
                }
            }
            assertFalse(pump.isCompleted)
        } finally {
            releaseThird.complete(Unit)
            releaseSlow.complete(Unit)
        }
        assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
        requests.forEach { assertEquals("COMPLETED", store.currentState(context, it.operationId)) }
    }

    @Test
    fun `expired UIDT grace waits for capacity without exceeding the dispatch window`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val requests = (0..3).map { index ->
            DownloadExecutionRequest(
                operationId = "uidt-grace-capacity-$index",
                song = sampleSong().copy(id = 82_000L + index)
            ).also { store.save(context, it) }
        }
        val graceExpired = AtomicBoolean(false)
        val started = requests.associate { it.operationId to CompletableDeferred<Unit>() }
        val releaseOne = CompletableDeferred<Unit>()
        val releaseOthers = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    started.getValue(request.operationId).complete(Unit)
                    when (request.operationId) {
                        requests[1].operationId -> releaseOne.await()
                        requests[2].operationId, requests[3].operationId -> releaseOthers.await()
                    }
                    DownloadExecutionResult.Accepted
                } finally {
                    active.decrementAndGet()
                }
            },
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            downloadParallelismProvider = { 1 },
            pendingUidtGraceDelayProvider = { _, request ->
                if (request.operationId == requests[0].operationId && !graceExpired.get()) 50L else 0L
            }
        )

        val pump = async { host.pump(context) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    requests.drop(1).forEach { started.getValue(it.operationId).await() }
                    graceExpired.set(true)
                    delay(100L)
                    assertFalse(started.getValue(requests[0].operationId).isCompleted)
                    assertEquals(3, active.get())
                    releaseOne.complete(Unit)
                    started.getValue(requests[0].operationId).await()
                }
            }
        } finally {
            releaseOne.complete(Unit)
            releaseOthers.complete(Unit)
        }
        assertEquals(DownloadExecutionPumpResult.Completed, pump.await())
        assertEquals(3, maximumActive.get())
    }

    @Test
    fun `repeated UIDT grace has bounded rereads while a slow operation runs`() = runTest {
        val context = mockContext()
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val waiting = DownloadExecutionRequest(
            operationId = "uidt-grace-repeated-waiting",
            song = sampleSong().copy(id = 83_001L)
        )
        val slow = DownloadExecutionRequest(
            operationId = "uidt-grace-repeated-slow",
            song = sampleSong().copy(id = 83_002L)
        )
        listOf(waiting, slow).forEach { store.save(context, it) }
        val slowStarted = CompletableDeferred<Unit>()
        val graceRechecked = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val graceReads = AtomicInteger()
        val waitingStarted = AtomicBoolean(false)
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = DownloadOperationEntryPoint { _, request ->
                if (request.operationId == slow.operationId) {
                    slowStarted.complete(Unit)
                    releaseSlow.await()
                } else {
                    waitingStarted.set(true)
                }
                DownloadExecutionResult.Accepted
            },
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            downloadParallelismProvider = { 1 },
            pendingUidtGraceDelayProvider = { _, request ->
                if (request.operationId == waiting.operationId) {
                    if (graceReads.incrementAndGet() == 2) graceRechecked.complete(Unit)
                    1L
                } else {
                    0L
                }
            }
        )

        val pump = async { host.pump(context) }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(2_000L) {
                    slowStarted.await()
                    graceRechecked.await()
                    delay(100L)
                }
            }
            assertEquals(2, graceReads.get())
            assertTrue(journal.pumpPageCallCount <= 4)
            assertFalse(waitingStarted.get())
        } finally {
            releaseSlow.complete(Unit)
        }
        assertEquals(DownloadExecutionPumpResult.ContinueAfterContention, pump.await())
        assertEquals("QUEUED", store.currentState(context, waiting.operationId))
    }
}
