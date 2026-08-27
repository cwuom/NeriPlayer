package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTemporaryWriteCleanupRetryPolicyTest {
    @Test
    fun `failed terminal cleanup uses bounded exponential backoff`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L),
            (1..TerminalTemporaryWriteCleanupRetryPolicy.MAX_FAILED_ATTEMPTS).map {
                TerminalTemporaryWriteCleanupRetryPolicy.delayMsForFailedAttempt(it)
            }
        )
        assertNull(TerminalTemporaryWriteCleanupRetryPolicy.delayMsForFailedAttempt(0))
        assertNull(
            TerminalTemporaryWriteCleanupRetryPolicy.delayMsForFailedAttempt(
                TerminalTemporaryWriteCleanupRetryPolicy.MAX_FAILED_ATTEMPTS + 1
            )
        )
    }

    @Test
    fun `scheduler retries persisted cleanup without delaying under its mutex`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val schedulingBody = source.substringAfter(
            "private fun scheduleFinalizedTemporaryWriteCleanup"
        ).substringBefore("private fun isDurableCoreOperationState")

        assertTrue(schedulingBody.contains("cleanupPersistedTerminalTemporaryWriteArtifacts"))
        assertTrue(schedulingBody.contains("delayMsForFailedAttempt"))
        assertTrue(schedulingBody.contains("retryPending = true"))
        assertTrue(schedulingBody.contains("delay(retryDelayMs)"))
        assertFalse(
            schedulingBody.contains(
                "terminalTemporaryWriteCleanupMutex.withLock {\n" +
                    "                            delay("
            )
        )
    }

    @Test
    fun `startup recovery failure wakes persisted cleanup without a synthetic target name`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializationBody = source.substringAfter("fun initialize(context: Context)")
            .substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")
        val schedulingBody = source.substringAfter(
            "private fun scheduleFinalizedTemporaryWriteCleanup"
        ).substringBefore("private fun isDurableCoreOperationState")

        assertTrue(initializationBody.contains("startupRecovery.failedCount > 0"))
        assertTrue(
            initializationBody.contains(
                "schedulePersistedTerminalTemporaryWriteCleanup(appContext)"
            )
        )
        assertTrue(
            schedulingBody.contains(
                "targetNames: Collection<String> = emptyList()"
            )
        )
        assertTrue(schedulingBody.contains("terminalTemporaryWriteCleanupWakeRequested = true"))
        assertTrue(schedulingBody.contains("cleanupRequested || retryPending"))
    }

    @Test
    fun `late startup recovery failure also wakes persisted cleanup`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val observerBody = source.substringAfter(
            "private fun observeStorageStartupRecovery(context: Context)"
        ).substringBefore("private suspend fun recoverPendingDownloadsForStartup")

        assertTrue(observerBody.contains("if (result.failedCount > 0)"))
        assertTrue(
            observerBody.contains(
                "schedulePersistedTerminalTemporaryWriteCleanup(appContext)"
            )
        )
    }

    @Test
    fun `single and batch cancellation failures wake persisted cleanup`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val singleCleanupBody = source.substringAfter(
            "private suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        song: SongItem,"
        ).substringBefore(
            "private suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        operationRequests: Collection<DownloadExecutionRequest>,"
        )
        val batchCleanupBody = source.substringAfter(
            "private suspend fun cleanupCancelledPendingDownloadArtifacts(\n" +
                "        context: Context,\n" +
                "        operationRequests: Collection<DownloadExecutionRequest>,"
        ).substringBefore("fun scanLocalFiles")

        assertTrue(singleCleanupBody.contains("if (result.failedCount > 0)"))
        assertTrue(
            singleCleanupBody.contains(
                "schedulePersistedTerminalTemporaryWriteCleanup(context)"
            )
        )
        assertTrue(batchCleanupBody.contains("if (result.failedCount == 0)"))
        assertTrue(
            batchCleanupBody.contains(
                "schedulePersistedTerminalTemporaryWriteCleanup(context)"
            )
        )
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
}
