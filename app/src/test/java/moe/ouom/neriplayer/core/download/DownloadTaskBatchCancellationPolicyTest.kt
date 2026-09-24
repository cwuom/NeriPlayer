package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.manager.batch.cancelDownloadTasksDurably
import moe.ouom.neriplayer.core.download.manager.batch.requestDownloadTaskCancellation
import moe.ouom.neriplayer.core.download.manager.catalog.buildManagedDownloadDeletePlans
import moe.ouom.neriplayer.core.download.manager.catalog.deleteDownloadedSongsOnIo
import moe.ouom.neriplayer.core.download.policy.runBoundedDownloadCancellationCleanup
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskBatchCancellationPolicyTest {
    @Test
    fun `bounded cleanup never exceeds its worker limit`() = runBlocking {
        val activeWorkers = AtomicInteger(0)
        val maximumWorkers = AtomicInteger(0)
        val completedItems = AtomicInteger(0)

        runBoundedDownloadCancellationCleanup(
            items = (1..40).toList(),
            parallelism = 4
        ) {
            val active = activeWorkers.incrementAndGet()
            maximumWorkers.updateAndGet { current -> maxOf(current, active) }
            try {
                delay(5L)
                completedItems.incrementAndGet()
            } finally {
                activeWorkers.decrementAndGet()
            }
        }

        assertEquals(40, completedItems.get())
        assertTrue(maximumWorkers.get() in 2..4)
    }

    @Test
    fun `download deletion requests one cancellation batch instead of one job per song`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val deletionBody = methodBody(source, "deleteDownloadedSongsOnIo")
        val batchEntryBody = methodBody(source, "requestDownloadTaskCancellation")
        val durableBatchBody = methodBody(source, "cancelDownloadTasksDurably")

        assertFalse(deletionBody.contains("deletionKeys.forEach(::cancelDownloadTask)"))
        assertTrue(deletionBody.contains("requestDownloadTaskCancellation(cancellationKeys)?.join()"))
        assertTrue(deletionBody.contains("selectDeletionCancellationKeys("))
        assertTrue(
            deletionBody.indexOf("activeCancellationKeys.isNotEmpty()") <
                deletionBody.indexOf("buildManagedDownloadDeletePlans(")
        )
        assertEquals(1, Regex("taskStore\\.currentTasks\\(\\)").findAll(batchEntryBody).count())
        assertEquals(
            1,
            Regex("invalidateDownloadRequestGenerations\\(").findAll(batchEntryBody).count()
        )
        assertTrue(durableBatchBody.contains("runBoundedDownloadCancellationCleanup("))
        assertTrue(durableBatchBody.contains("val logSettlement = entries.size == 1"))
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )

    private fun locateProjectFile(relativePath: String): java.io.File {
        val userDirectory = System.getProperty("user.dir")
            ?: error("user.dir is unavailable")
        var current = java.io.File(userDirectory).absoluteFile
        repeat(8) {
            val candidate = java.io.File(current, relativePath)
            if (candidate.isFile) {
                return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath")
    }
}
