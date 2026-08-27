package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadDirectoryMutationFenceContractTest {
    @Test
    fun `download commit lease is acquired before managed root writes`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        )
        val finalizeBody = source.substringAfter("private suspend fun finalizeDownloadedAudio(")
            .substringBefore("internal suspend fun downloadSidecarsForCompletedAudio(")

        val leaseIndex = finalizeBody.indexOf("acquireCommitLeaseOrNull(")
        val metadataIndex = finalizeBody.indexOf("writePendingAudioMetadata(")
        val audioCommitIndex = finalizeBody.indexOf("saveAudioFromTemp(")

        assertTrue(leaseIndex >= 0)
        assertTrue(metadataIndex > leaseIndex)
        assertTrue(audioCommitIndex > metadataIndex)
    }

    @Test
    fun `migration closes and drains commit gate before source collection`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        )
        val migrationBody = source.substringAfter("private suspend fun runMigration(): Result")
            .substringBefore("private fun createForegroundInfo(")

        val drainIndex = migrationBody.indexOf("closeAndDrain()")
        val migrationIndex = migrationBody.indexOf("migrateManagedDownloads(")

        assertTrue(drainIndex >= 0)
        assertTrue(migrationIndex > drainIndex)
    }

    @Test
    fun `core metadata and sidecar writes also hold a directory commit lease`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val finalizationBody = source.substringAfter("private suspend fun finalizeCompletedDownload(")
            .substringBefore("private suspend fun completeCoreDownloadAndEnqueueEnrichment(")
        val enrichmentBody = source.substringAfter("private suspend fun enrichCoreCommittedDownload(")
            .substringBefore("private suspend fun preserveUnsupportedMetadataEmbedding(")

        assertTrue(
            finalizationBody.indexOf("acquireCommitLeaseOrNull(") in
                0 until finalizationBody.indexOf("demotePublishedAudioForFinalization(")
        )
        assertTrue(
            enrichmentBody.indexOf("acquireCommitLeaseOrNull(") in
                0 until enrichmentBody.indexOf("downloadSidecarsForCompletedAudio(")
        )
    }

    @Test
    fun `storage deferral preserves durable queue for later promotion`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val deferralCatch = source
            .substringAfter("catch (error: DownloadStorageMutationDeferredException)")
            .substringBefore("catch (_: CancellationException)")

        assertTrue(deferralCatch.contains("markDownloadArtifactRetryable("))
        assertTrue(deferralCatch.contains("removeDownloadTask("))
        assertFalse(deferralCatch.contains("forgetPendingDownloadQueueEntriesIfCurrent("))
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
