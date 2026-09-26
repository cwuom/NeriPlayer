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

class BatchDownloadStartupTest {
    @Test
    fun nextBatchWaitsForTheFirstWindowButIndependentWorkContinues() = runBlocking {
        withTimeout(5_000L) {
            val entered = CompletableDeferred<Unit>()
            val firstWindowReady = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launchBatchDownloadStartup(beforeStartup = { Unit }) {
                entered.complete(Unit)
                firstWindowReady.await()
            }
            entered.await()
            val second = launchBatchDownloadStartup(beforeStartup = { Unit }) { secondEntered.complete(Unit) }
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
        withTimeout(5_000L) {
            val entered = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val first = launchBatchDownloadStartup(beforeStartup = { Unit }) {
                entered.complete(Unit)
                blocked.await()
            }
            entered.await()
            val second = launchBatchDownloadStartup(beforeStartup = { Unit }) { secondEntered.complete(Unit) }
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
        val second = launchBatchDownloadStartup(beforeStartup = { Unit }) {
            secondPrepared.complete(Unit)
        }
        try {
            assertTrue("无关批次不应等待另一批次的持久准入栅栏",
                withTimeoutOrNull(1_000L) { secondPrepared.await(); true } == true)
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }
}
