package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertEquals
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
    fun `pending migration preflight recovery runs only after lease release`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        )
        val migrationBody = source.substringAfter(
            "private suspend fun runMigration(): Result"
        ).substringBefore("private fun createForegroundInfo(")
        val closeIndex = migrationBody.indexOf("val leaseClosed = runCatching")
        val bypassIndex = migrationBody.indexOf(
            "reconcilePendingDownloadsAfterMigrationBlocked("
        )
        val genericRecoveryIndex = migrationBody.indexOf(
            "recoverPendingDownloadsAfterStorageMutation("
        )

        assertTrue(closeIndex >= 0)
        assertTrue(bypassIndex > closeIndex)
        assertTrue(genericRecoveryIndex > bypassIndex)
        assertTrue(
            migrationBody.contains("MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE")
        )
    }

    @Test
    fun `migration performs pending recovery before entering processing state`() {
        val workerSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        )
        val workerBody = workerSource.substringAfter(
            "private suspend fun runMigration(): Result"
        ).substringBefore("private fun createForegroundInfo(")
        val recoveryIndex = workerBody.indexOf(
            "reconcilePendingDownloadsBeforeMigrationDetailed("
        )
        val processingRestoreIndex = workerBody.indexOf(
            "ManagedLibraryProcessingCoordinator.restore(applicationContext)"
        )

        assertTrue(recoveryIndex >= 0)
        assertTrue(processingRestoreIndex > recoveryIndex)

        val managerSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val recoveryBody = managerSource.substringAfter(
            "internal suspend fun reconcilePendingDownloadsBeforeMigrationDetailed("
        ).substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")
        assertTrue(recoveryBody.contains("closeAndDrain()"))
        assertTrue(recoveryBody.contains("directoryMutationLeaseOwned = true"))
        assertFalse(recoveryBody.contains("ManagedLibraryProcessingCoordinator.complete("))
        assertTrue(recoveryBody.contains("pendingScanComplete"))
    }

    @Test
    fun `migration holds the directory lease across pending preflight`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        )
        val migrationBody = source.substringAfter(
            "private suspend fun runMigration(): Result"
        ).substringBefore("private fun createForegroundInfo(")
        val leaseIndex = migrationBody.indexOf(
            "directoryMutationLease = ManagedDownloadDirectoryMutationFence.closeAndDrain()"
        )
        val preflightIndex = migrationBody.indexOf(
            "reconcilePendingDownloadsBeforeMigrationDetailed("
        )
        val ownedFlagIndex = migrationBody.indexOf(
            "directoryMutationLeaseOwned = true",
            preflightIndex
        )
        val migrationIndex = migrationBody.indexOf("migrateManagedDownloads(")

        assertTrue(leaseIndex >= 0)
        assertTrue(preflightIndex > leaseIndex)
        assertTrue(ownedFlagIndex > preflightIndex)
        assertTrue(migrationIndex > ownedFlagIndex)
        assertEquals(
            1,
            migrationBody.windowed(
                "directoryMutationLease = ManagedDownloadDirectoryMutationFence".length,
                1
            ).count { window ->
                window == "directoryMutationLease = ManagedDownloadDirectoryMutationFence"
            }
        )
    }

    @Test
    fun `owned migration recovery does not reacquire a blocked commit lease`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val finalizationBody = source.substringAfter(
            "private suspend fun finalizeCompletedDownload("
        ).substringBefore("private suspend fun completeCoreDownloadAndEnqueueEnrichment(")
        val enrichmentBody = source.substringAfter(
            "private suspend fun enrichCoreCommittedDownload("
        ).substringBefore("private suspend fun preserveUnsupportedMetadataEmbedding(")
        val enqueueBody = source.substringAfter(
            "val enrichmentJob = assetEnrichmentCoordinator.enqueue("
        ).substringBefore("private suspend fun settlePostCoreEnrichmentFailure(")

        assertTrue(finalizationBody.contains("if (directoryMutationLeaseOwned)"))
        assertTrue(enrichmentBody.contains("if (directoryMutationLeaseOwned)"))
        assertTrue(enrichmentBody.contains("directoryCommitLease?.close()"))
        assertFalse(enqueueBody.contains("enrichmentJob.join()"))
        assertTrue(enqueueBody.contains("directoryMutationLeaseOwned = false"))
        assertTrue(enqueueBody.contains("acquireCommitLeaseOrNull("))
    }

    @Test
    fun `artifact lease cancellation is propagated without failure logging`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val body = source.substringAfter("private suspend fun releaseDownloadArtifactClaim(")
            .substringBefore("private suspend fun releaseDownloadArtifactAfterExecutionOwnershipLoss(")
        val cancellationIndex = body.indexOf("catch (error: CancellationException)")
        val exceptionIndex = body.indexOf("catch (error: Exception)")

        assertTrue(cancellationIndex >= 0)
        assertTrue(exceptionIndex > cancellationIndex)
        assertTrue(body.substring(cancellationIndex, exceptionIndex).contains("throw error"))
        assertFalse(body.substringBefore("try {").contains("runCatching"))
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
    fun `migration pending recovery reads and promotes inside the source root`() {
        val storageSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        )
        assertTrue(storageSource.contains("readDownloadedMetadataFromRoot("))
        assertTrue(storageSource.contains("promotePendingMetadata: Boolean = false"))

        val managerSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val recoveryBody = managerSource.substringAfter(
            "private suspend fun recoverPendingAudioWritesFromRoot("
        ).substringBefore("private suspend fun recoverUnfinalizedPublishedAudioFromRoot(")
        val sourceRootIndex = recoveryBody.indexOf("sourceRootRecovery")
        val rootReadIndex = recoveryBody.indexOf("readDownloadedMetadataFromRoot(")
        val promotionIndex = recoveryBody.indexOf("promoteCoreCommittedPendingAudio(")
        val normalFinalizeIndex = recoveryBody.indexOf("finalizeCompletedDownload(")

        assertTrue(sourceRootIndex >= 0)
        assertTrue(rootReadIndex > sourceRootIndex)
        assertTrue(promotionIndex > rootReadIndex)
        assertTrue(normalFinalizeIndex > promotionIndex)
        assertTrue(
            recoveryBody.substring(promotionIndex, normalFinalizeIndex)
                .contains("promotePendingMetadata = true")
        )
    }

    @Test
    fun `source pending recovery settles operation journal before artifact promotion`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val body = source.substringAfter(
            "private suspend fun recoverPendingAudioWritesFromRoot("
        ).substringBefore("private suspend fun recoverUnfinalizedPublishedAudioFromRoot(")
        val sourceBranch = body.substringAfter("if (sourceRootRecovery) {")
            .substringBefore("val promoted = ManagedDownloadStorage.promoteCoreCommittedPendingAudio(")

        assertTrue(sourceBranch.contains("DownloadExecutionRoomStore.state("))
        assertTrue(sourceBranch.contains("CANCEL_REQUESTED"))
        assertTrue(sourceBranch.contains("DownloadExecutionRoomStore.markCommitting("))
        assertTrue(sourceBranch.contains("reconcileCoreCommitJournal("))
        assertTrue(sourceBranch.contains("coreMetadataDurable = true"))

        val afterPromotion = body.substringAfter(
            "val promoted = ManagedDownloadStorage.promoteCoreCommittedPendingAudio("
        )
        assertTrue(afterPromotion.contains("managedDownloadArtifactCoordinator.markCoreCommitted("))
        assertTrue(afterPromotion.contains("val artifactCommitted = runCatching"))
        assertTrue(afterPromotion.contains("if (artifactCommitted)"))
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
