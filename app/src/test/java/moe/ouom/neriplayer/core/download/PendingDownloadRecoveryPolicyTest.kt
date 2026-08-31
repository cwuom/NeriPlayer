package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDownloadRecoveryPolicyTest {
    @Test
    fun `incomplete pending scan never allows migration`() {
        val summary = PendingDownloadRecoverySummary(
            leaseAcquired = true,
            initialScanComplete = false,
            pendingScanComplete = false
        )

        assertFalse(summary.isConverged)
    }

    @Test
    fun `remaining pending artifacts keep migration blocked`() {
        val summary = PendingDownloadRecoverySummary(
            leaseAcquired = true,
            initialScanComplete = true,
            pendingScanComplete = true,
            remainingArtifactCount = 1
        )

        assertFalse(summary.isConverged)
    }

    @Test
    fun `complete pending scan allows migration`() {
        val summary = PendingDownloadRecoverySummary(
            leaseAcquired = true,
            initialScanComplete = true,
            pendingScanComplete = true,
            failedAudioCount = 0
        )

        assertTrue(summary.isConverged)
    }

    @Test
    fun `failed pending audio keeps migration blocked even when final scan is empty`() {
        val summary = PendingDownloadRecoverySummary(
            leaseAcquired = true,
            initialScanComplete = true,
            pendingScanComplete = true,
            failedAudioCount = 1,
            remainingArtifactCount = 0
        )

        assertFalse(summary.isConverged)
    }

    @Test
    fun `migration preflight persists waiting state and keeps retry recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        ).readText()
        val runBody = source.substringAfter("private suspend fun runMigration(): Result")
            .substringBefore("private fun createForegroundInfo(")

        assertTrue(runBody.contains("ensureWaitingForRetry("))
        assertTrue(runBody.contains("reconcilePendingDownloadsBeforeMigrationDetailed("))
        assertTrue(runBody.contains("MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE"))
        assertTrue(runBody.contains("markRequestRetryable(migrationWorkId)"))
        assertTrue(runBody.contains("markRequestTerminal(migrationWorkId)"))
    }

    @Test
    fun `waiting migration operation is not cleared during pending recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = source.substringAfter(
            "internal suspend fun reconcilePendingDownloadsBeforeMigrationDetailed("
        ).substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")

        assertTrue(body.contains("WaitingForRetry"))
        assertFalse(body.contains("ManagedLibraryProcessingCoordinator.complete("))
    }

    @Test
    fun `source recovery cleans only durable cancelled operations before rescanning`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = source.substringAfter(
            "private suspend fun recoverPendingAudioWritesFromRoot("
        ).substringBefore("private suspend fun recoverUnfinalizedPublishedAudioFromRoot")

        assertTrue(body.contains("listByStatesAnyLibrary("))
        assertTrue(body.contains("CANCEL_REQUESTED"))
        assertTrue(body.contains("CANCELLED"))
        assertTrue(body.contains("useDefaultRootWhenDirectoryUriMissing = true"))
        assertTrue(
            body.indexOf("cleanupCancelledPendingDownloadArtifacts(") <
                body.indexOf("scanPendingAudioWrites(")
        )
        assertFalse(body.contains("states = listOf(\"STOPPED\")"))
    }

    @Test
    fun `private migration source keeps recovery on the default root`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = source.substringAfter(
            "private suspend fun recoverPendingAudioWritesFromRoot("
        ).substringBefore("private suspend fun recoverUnfinalizedPublishedAudioFromRoot")

        assertTrue(body.contains("val sourceRootRecovery = directoryMutationLeaseOwned"))
        assertTrue(body.contains("useDefaultRootWhenDirectoryUriMissing = sourceRootRecovery"))
        assertTrue(body.contains("directoryUri = directoryUri"))
    }

    private fun locateProjectFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $relativePath")
    }
}
