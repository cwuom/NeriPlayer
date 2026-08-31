package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class UidtStopCoordinatorTest {
    @Test
    fun `onStopJob delegates persistence without blocking the callback thread`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "UidtDownloadJobService.kt"
        ).readText()
        val onStopJob = methodBody(source, "override fun onStopJob(")

        assertFalse(onStopJob.contains("runBlocking"))
        assertFalse(onStopJob.contains("DownloadExecutionHosts.stopForSystemRetry("))
        assertFalse(onStopJob.contains("DownloadExecutionHosts.default.stop("))
        assertTrue(onStopJob.contains("UidtStopCoordinators.default.enqueue("))
        val prepareIndex = onStopJob.indexOf("DownloadExecutionHosts.prepareSchedulerStop(")
        val cancelIndex = onStopJob.indexOf("runningJobs.remove(params.jobId)?.cancel(")
        val enqueueIndex = onStopJob.indexOf("UidtStopCoordinators.default.enqueue(")
        assertTrue(prepareIndex >= 0)
        assertTrue(cancelIndex > prepareIndex)
        assertTrue(enqueueIndex > cancelIndex)

        val hostSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadExecutionHost.kt"
        ).readText()
        val prepareStop = methodBody(
            hostSource,
            "internal fun prepareSchedulerStop("
        )
        assertFalse(prepareStop.contains("runBlocking"))
        assertFalse(prepareStop.contains("operationStore"))
        assertFalse(prepareStop.contains("GlobalDownloadManager"))
    }

    @Test
    fun `UIDT start only retires a fallback after execution completes`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "UidtDownloadJobService.kt"
        ).readText()
        val onStartJob = methodBody(source, "override fun onStartJob(")

        val executeIndex = onStartJob.indexOf(
            "DownloadExecutionHosts.default.execute(applicationContext, operationId)"
        )
        val cancelIndex = onStartJob.indexOf("cancelFallback(")
        assertTrue(executeIndex >= 0)
        assertTrue(cancelIndex > executeIndex)
    }

    @Test
    fun `startup trims excess UIDT jobs and rearms the durable pump`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "UidtDownloadJobService.kt"
        ).readText()
        val trim = methodBody(source, "internal fun trimPendingJobs(")

        assertTrue(trim.contains("UIDT_PENDING_JOB_LIMIT"))
        assertTrue(trim.contains("scheduler.cancel("))
        assertTrue(trim.contains("ForegroundDownloadWorker.schedulePump(context)"))
    }

    @Test
    fun `service destruction seals every completion gate before cancellation`() {
        val firstGate = UidtJobCompletionGate()
        val secondGate = UidtJobCompletionGate()

        sealUidtCompletionGates(listOf(firstGate, secondGate))

        assertFalse(firstGate.shouldReportCompletion())
        assertFalse(secondGate.shouldReportCompletion())

        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "UidtDownloadJobService.kt"
        ).readText()
        val onDestroy = methodBody(source, "override fun onDestroy(")
        val sealIndex = onDestroy.indexOf("sealUidtCompletionGates(")
        val cancelIndex = onDestroy.indexOf("serviceScope.cancel()")
        val clearIndex = onDestroy.indexOf("completionGates.clear()")

        assertTrue(sealIndex >= 0)
        assertTrue(cancelIndex > sealIndex)
        assertTrue(clearIndex > cancelIndex)
    }

    @Test
    fun `coordinator converges stop requests off caller thread in FIFO order`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "uidt-stop-coordinator-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val callerThread = Thread.currentThread()
        val observed = ConcurrentLinkedQueue<Pair<UidtStopAction, Thread>>()
        val completed = CountDownLatch(2)
        val context = mock(Context::class.java)
        val coordinator = UidtStopCoordinator(
            scope = scope,
            converge = { request ->
                observed += request.action to Thread.currentThread()
                completed.countDown()
            }
        )

        try {
            assertTrue(
                coordinator.enqueue(
                    UidtStopRequest(
                        context = context,
                        operationId = "operation-system-stop",
                        action = UidtStopAction.RETRY_WITHOUT_CANCELLING_BACKENDS,
                        preventReschedule = false
                    )
                )
            )
            assertTrue(
                coordinator.enqueue(
                    UidtStopRequest(
                        context = context,
                        operationId = "operation-user-stop",
                        action = UidtStopAction.STOP_AND_CANCEL_BACKENDS,
                        preventReschedule = true
                    )
                )
            )
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    UidtStopAction.RETRY_WITHOUT_CANCELLING_BACKENDS,
                    UidtStopAction.STOP_AND_CANCEL_BACKENDS
                ),
                observed.map { observation -> observation.first }
            )
            assertTrue(observed.all { (_, executionThread) -> executionThread !== callerThread })
        } finally {
            scope.cancel()
            dispatcher.close()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `scheduler stop suppresses a late jobFinished callback`() {
        val gate = UidtJobCompletionGate()

        assertTrue(gate.shouldReportCompletion())
        gate.markSchedulerStopped()
        assertFalse(gate.shouldReportCompletion())
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private fun methodBody(source: String, signature: String): String {
        val signatureStart = source.indexOf(signature)
        require(signatureStart >= 0) { "method not found: $signature" }
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $signature" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unterminated method body: $signature")
    }
}
