package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.uidt.UidtJobRunSteps
import moe.ouom.neriplayer.core.download.execution.uidt.UidtNotificationOwners
import moe.ouom.neriplayer.core.download.execution.uidt.runUidtJob
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UidtJobRunTest {
    @Test
    fun `notification is provided before the schedule lock and execution`() = runBlocking {
        val recorder = StepRecorder(executeResult = false)

        runUidtJob(OPERATION_ID, recorder.steps())

        assertEquals(
            listOf("acquire", "post", "markStarted", "execute", "cleanup", "report:false"),
            recorder.events
        )
    }

    @Test
    fun `execution reschedule request reaches jobFinished`() = runBlocking {
        val recorder = StepRecorder(executeResult = true)

        runUidtJob(OPERATION_ID, recorder.steps())

        assertEquals("report:true", recorder.events.last())
    }

    @Test
    fun `notification failure releases admission and retries without executing`() = runBlocking {
        val recorder = StepRecorder(postFailure = IllegalStateException("notification rejected"))

        runUidtJob(OPERATION_ID, recorder.steps())

        assertEquals(
            listOf("acquire", "post", "notificationFailure", "cleanup", "report:true"),
            recorder.events
        )
    }

    @Test
    fun `startup failures off the main thread still finish the job`() = runBlocking {
        val recorder = StepRecorder(acquireFailure = IllegalStateException("binder died"))

        runUidtJob(OPERATION_ID, recorder.steps())

        assertEquals(listOf("acquire", "cleanup", "report:true"), recorder.events)
    }

    @Test
    fun `scheduler cancellation still cleans up and reports`() = runBlocking {
        val executing = CompletableDeferred<Unit>()
        val recorder = StepRecorder(
            executeBlock = {
                executing.complete(Unit)
                awaitCancellation()
            }
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runUidtJob(OPERATION_ID, recorder.steps())
        }
        executing.await()

        job.cancelAndJoin()

        assertEquals(
            listOf("acquire", "post", "markStarted", "execute", "cleanup", "report:false"),
            recorder.events
        )
    }

    @Test
    fun `background release racing an acquisition never leaves a controller owner`() {
        val controllerOwners = Collections.synchronizedSet(mutableSetOf<String>())
        val acquiring = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val owners = UidtNotificationOwners(
            acquireOwner = { owner ->
                acquiring.countDown()
                assertTrue(finishAcquire.await(5, TimeUnit.SECONDS))
                controllerOwners += owner
            },
            releaseOwner = { owner -> controllerOwners -= owner }
        )
        val acquirer = thread { owners.acquire(OWNER) }
        assertTrue(acquiring.await(5, TimeUnit.SECONDS))
        val releaser = thread { owners.release(OWNER) }
        releaser.join(200)
        assertTrue("release must wait for the in-flight acquisition", releaser.isAlive)

        finishAcquire.countDown()
        acquirer.join(5_000)
        releaser.join(5_000)

        assertTrue(controllerOwners.isEmpty())
    }

    @Test
    fun `release before acquisition leaves the owner to the run cleanup`() {
        val controllerOwners = mutableSetOf<String>()
        val owners = UidtNotificationOwners(
            acquireOwner = { owner -> controllerOwners += owner },
            releaseOwner = { owner -> controllerOwners -= owner }
        )

        owners.release(OWNER)
        owners.acquire(OWNER)
        assertEquals(setOf(OWNER), controllerOwners)

        owners.release(OWNER)
        assertTrue(controllerOwners.isEmpty())
    }

    @Test
    fun `service destruction releases every registered owner`() {
        val controllerOwners = mutableSetOf<String>()
        val owners = UidtNotificationOwners(
            acquireOwner = { owner -> controllerOwners += owner },
            releaseOwner = { owner -> controllerOwners -= owner }
        )
        owners.acquire("uidt:1:1")
        owners.acquire("uidt:2:2")

        owners.releaseAll()
        owners.release("uidt:1:1")

        assertTrue(controllerOwners.isEmpty())
    }

    @Test
    fun `main thread job callbacks never touch the schedule lock or notification binder`() {
        val source = projectFile(
            "modules/download/runtime/src/main/java/moe/ouom/neriplayer/core/download/execution/uidt/" +
                "UidtDownloadJobService.kt"
        ).readText()
        val onStartJob = methodBody(source, "override fun onStartJob(")
        val launchIndex = onStartJob.indexOf("serviceScope.launch(")
        assertTrue(launchIndex >= 0)
        val beforeLaunch = onStartJob.substring(0, launchIndex)
        val launchBody = methodBody(onStartJob.substring(launchIndex), "serviceScope.launch(")
        val afterLaunch = onStartJob.substring(
            onStartJob.indexOf(launchBody, launchIndex) + launchBody.length
        )
        MAIN_THREAD_BLOCKERS.forEach { blocker ->
            assertFalse("onStartJob calls $blocker on the main thread", beforeLaunch.contains(blocker))
            assertFalse("onStartJob calls $blocker on the main thread", afterLaunch.contains(blocker))
        }

        val reportCompletion = methodBody(onStartJob, "reportCompletion =")
        MAIN_THREAD_BLOCKERS.forEach { blocker ->
            assertFalse("job completion calls $blocker on the main thread", reportCompletion.contains(blocker))
        }

        val onStopJob = methodBody(source, "override fun onStopJob(")
        val stopBookkeeping = methodBody(onStopJob, "bookkeepingScope.launch")
        val onStopOutsideBookkeeping = onStopJob.replace(stopBookkeeping, "")
        MAIN_THREAD_BLOCKERS.forEach { blocker ->
            assertFalse("onStopJob calls $blocker on the main thread", onStopOutsideBookkeeping.contains(blocker))
        }

        val onDestroy = methodBody(source, "override fun onDestroy(")
        val destroyBookkeeping = methodBody(onDestroy, "bookkeepingScope.launch")
        val onDestroyOutsideBookkeeping = onDestroy.replace(destroyBookkeeping, "")
        MAIN_THREAD_BLOCKERS.forEach { blocker ->
            assertFalse(
                "onDestroy calls $blocker on the main thread",
                onDestroyOutsideBookkeeping.contains(blocker)
            )
        }
    }

    private class StepRecorder(
        private val executeResult: Boolean = false,
        private val acquireFailure: Throwable? = null,
        private val postFailure: Throwable? = null,
        private val executeBlock: (suspend () -> Boolean)? = null
    ) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun steps() = UidtJobRunSteps(
            acquireNotification = {
                events += "acquire"
                acquireFailure?.let { throw it }
            },
            postNotification = {
                events += "post"
                postFailure?.let { throw it }
            },
            markStarted = { events += "markStarted" },
            execute = {
                events += "execute"
                executeBlock?.invoke() ?: executeResult
            },
            onNotificationFailure = { events += "notificationFailure" },
            cleanup = { events += "cleanup" },
            reportCompletion = { wantsReschedule -> events += "report:$wantsReschedule" }
        )
    }

    private fun projectFile(path: String): File =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(
            moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.projectFile(path)
        )

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

    private companion object {
        const val OPERATION_ID = "operation-uidt-run"
        const val OWNER = "uidt:7:1"
        val MAIN_THREAD_BLOCKERS = listOf(
            "markJobStarted(",
            "markJobFinished(",
            "notificationOwners.",
            "DownloadExecutionNotificationController.",
            "setNotification(",
            "buildNotification("
        )
    }
}
