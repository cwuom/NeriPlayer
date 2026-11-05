package moe.ouom.neriplayer.core.download.manager.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GlobalDownloadManagerCancellationSettlementTest {
    @Test
    fun `single song cancellation stops captured enrichment without cancelling replacement`() = runBlocking {
        val coordinator = GlobalDownloadManager.assetEnrichmentCoordinator
        val songKey = "enrichment-cancel-target"
        val oldOperationId = "enrichment-cancel-old"
        val replacementId = "enrichment-cancel-replacement"
        val started = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val old = coordinator.enqueue(oldOperationId) {
            started.complete(Unit)
            awaitCancellation()
        }
        val replacement = coordinator.enqueue(replacementId) {
            replacementStarted.complete(Unit)
            awaitCancellation()
        }
        try {
            withTimeout(2_000.milliseconds) {
                started.await()
                replacementStarted.await()
            }
            GlobalDownloadManager.cancelledSongKeys.add(songKey)

            assertTrue(GlobalDownloadManager.awaitSongCancellationSettled(
                songKey = songKey,
                timeoutMs = 2_000L,
                logProgress = false,
                operationIds = setOf(oldOperationId)
            ))

            assertTrue(old.isCancelled)
            assertFalse(coordinator.isActive(oldOperationId))
            assertTrue(replacement.isActive)
            assertTrue(coordinator.isActive(replacementId))
        } finally {
            coordinator.cancelAndJoin(setOf(oldOperationId, replacementId))
            GlobalDownloadManager.clearSongCancelled(songKey)
        }
    }

    @Test
    fun `single song cancellation retains ownership while enrichment cleanup is still writing`() = runBlocking {
        val coordinator = GlobalDownloadManager.assetEnrichmentCoordinator
        val songKey = "enrichment-cleanup-target"
        val operationId = "enrichment-cleanup-old"
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        coordinator.enqueue(operationId) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }
        try {
            withTimeout(2_000.milliseconds) { started.await() }
            GlobalDownloadManager.cancelledSongKeys.add(songKey)

            assertFalse(GlobalDownloadManager.awaitSongCancellationSettled(
                songKey = songKey,
                timeoutMs = 25L,
                logProgress = false,
                operationIds = setOf(operationId)
            ))

            withTimeout(2_000.milliseconds) { cleanupStarted.await() }
            assertTrue(coordinator.isActive(operationId))
            assertTrue(GlobalDownloadManager.isSongCancelled(songKey))
            releaseCleanup.complete(Unit)
            assertTrue(GlobalDownloadManager.awaitSongCancellationSettled(
                songKey = songKey,
                timeoutMs = 2_000L,
                logProgress = false,
                operationIds = setOf(operationId)
            ))
            assertFalse(coordinator.isActive(operationId))
            assertFalse(GlobalDownloadManager.isSongCancelled(songKey))
        } finally {
            releaseCleanup.complete(Unit)
            coordinator.cancelAndJoin(setOf(operationId))
            GlobalDownloadManager.clearSongCancelled(songKey)
        }
    }
}
