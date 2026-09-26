package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.manager.commit.cleanupCancelledPendingDownloadArtifacts
import moe.ouom.neriplayer.core.download.manager.commit.schedulePersistedTerminalTemporaryWriteCleanup
import moe.ouom.neriplayer.core.download.manager.recovery.observeStorageStartupRecovery
import moe.ouom.neriplayer.core.download.policy.TerminalTemporaryWriteCleanupRetryPolicy
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
        val schedulingBody = methodBody(source, "schedulePersistedTerminalTemporaryWriteCleanup")

        assertTrue(schedulingBody.contains("cleanupPersistedTerminalTemporaryWriteArtifacts"))
        assertTrue(schedulingBody.contains("immediatelyRetryableFailedCount"))
        assertTrue(schedulingBody.contains("externalSignalRequiredCount"))
        assertTrue(schedulingBody.contains("delayMsForFailedAttempt"))
        assertTrue(schedulingBody.contains("retryPending = true"))
        assertTrue(schedulingBody.contains("delay(retryDelayMs)"))
        val externalWaitBody = schedulingBody
            .substringAfter("if (retryableFailedCount == 0)")
            .substringBefore("} else {")
        assertTrue(externalWaitBody.contains("failedAttempt = 0"))
        assertFalse(externalWaitBody.contains("delay("))
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
        val initializationBody = methodBody(source, "initialize")
        val schedulingBody = methodBody(source, "schedulePersistedTerminalTemporaryWriteCleanup")

        assertTrue(initializationBody.contains("startupRecovery.failedCount > 0"))
        assertTrue(
            initializationBody.contains(
                "schedulePersistedTerminalTemporaryWriteCleanup(appContext)"
            )
        )
        assertTrue(
            source.contains(
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
        val observerBody = methodBody(source, "observeStorageStartupRecovery")

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
        val singleCleanupBody = methodBodyFromSignature(
            source,
            "internal suspend fun GlobalDownloadManager.cleanupCancelledPendingDownloadArtifacts(\n" +
                "    context: Context,\n" +
                "    song: SongItem,"
        )
        val batchCleanupBody = methodBodyFromSignature(
            source,
            "internal suspend fun GlobalDownloadManager.cleanupCancelledPendingDownloadArtifacts(\n" +
                "    context: Context,\n" +
                "    operationRequests: Collection<DownloadExecutionRequest>,"
        )

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
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )

    private fun methodBodyFromSignature(source: String, signature: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBodyFromSignature(
            source = source,
            signature = signature
        )
}
