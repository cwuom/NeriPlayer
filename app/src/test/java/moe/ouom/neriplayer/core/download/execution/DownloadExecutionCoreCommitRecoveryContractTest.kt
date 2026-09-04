package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionCoreCommitRecoveryContractTest {
    @Test
    fun `recovery promotes only waiting or retryable operations`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadExecutionRoomStore.kt"
        )
        val body = source.substringAfter(
            "suspend fun reconcileCoreCommitJournal("
        ).substringBefore("suspend fun markCommitting(")

        assertTrue(source.contains("CORE_COMMIT_RECOVERY_SOURCE_STATES"))
        assertTrue(source.contains("WAITING_STORAGE_MUTATION_OPERATION_STATE"))
        assertTrue(source.contains("\"RETRYABLE\""))
        assertTrue(body.contains("state = \"COMMITTING\""))
        assertTrue(body.contains("stopRequestedByUser"))
        assertFalse(body.contains("dao.upsert("))
        assertFalse(body.contains("dao.insert("))
    }

    @Test
    fun `core recovery validates callback identity and preserves blocked state details`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val body = source.substringAfter(
            "private suspend fun completeCoreDownloadAndEnqueueEnrichment("
        ).substringBefore("private suspend fun enrichCoreCommittedDownload(")

        assertTrue(body.contains("stableKey = songKey"))
        assertTrue(body.contains("expectedAttemptId = expectedAttemptId"))
        assertTrue(body.contains("coreMetadataDurable = coreMetadataReady || coreMetadataWritten"))
        assertTrue(body.contains("state=\${recovery.state}"))
        assertTrue(body.contains("stopRequested=\${recovery.stopRequestedByUser}"))
        assertFalse(body.contains("DownloadExecutionRoomStore.upsert("))
    }

    private fun readSource(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $relativePath")
    }
}
