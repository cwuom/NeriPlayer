package moe.ouom.neriplayer.core.download.manager.batch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class BatchDownloadStartupTest {
    @Test
    fun nextBatchWaitsForTheFirstWindowButIndependentWorkContinues() = runBlocking {
        withTimeout(5_000.milliseconds) {
            val entered = CompletableDeferred<Unit>()
            val firstWindowReady = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launchBatchDownloadStartup(beforeStartup = {}) {
                entered.complete(Unit)
                firstWindowReady.await()
            }
            entered.await()
            val second = launchBatchDownloadStartup(beforeStartup = {}) { secondEntered.complete(Unit) }
            yield()
            assertFalse(secondEntered.isCompleted)
            val independent = launch { }
            independent.join()
            assertTrue(independent.isCompleted)
            firstWindowReady.complete(Unit)
            first.join()
            second.join()
            assertTrue(secondEntered.isCompleted)
        }
    }

    @Test
    fun cancellingStartupReleasesTheNextBatch() = runBlocking {
        withTimeout(5_000.milliseconds) {
            val entered = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launchBatchDownloadStartup(beforeStartup = {}) {
                entered.complete(Unit)
                blocked.await()
            }
            entered.await()
            val second = launchBatchDownloadStartup(beforeStartup = {}) { secondEntered.complete(Unit) }
            first.cancelAndJoin()
            second.join()
            assertTrue(secondEntered.isCompleted)
        }
    }

    @Test
    fun anotherBatchCanPrepareWhileTheFirstBatchWaitsForAdmission() = runBlocking {
        val waitingForAdmission = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        val secondPrepared = CompletableDeferred<Unit>()
        val first = launchBatchDownloadStartup(beforeStartup = {
            waitingForAdmission.complete(Unit)
            releaseAdmission.await()
        }) { }
        waitingForAdmission.await()
        val second = launchBatchDownloadStartup(beforeStartup = {}) {
            secondPrepared.complete(Unit)
        }
        try {
            assertTrue("无关批次不应等待另一批次的持久准入栅栏",
                withTimeoutOrNull(1_000.milliseconds) { secondPrepared.await(); true } == true)
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }
}
