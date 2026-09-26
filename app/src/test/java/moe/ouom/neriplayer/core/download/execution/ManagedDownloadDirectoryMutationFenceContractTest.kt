package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
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
        val finalizeBody = methodBody(source, "finalizeDownloadedAudio")

        val leaseIndex = finalizeBody.indexOf("acquireCommitLeaseOrNull(")
        val audioCommitIndex = finalizeBody.indexOf("saveAudioFromTemp(")

        assertTrue(leaseIndex >= 0)
        assertTrue(audioCommitIndex > leaseIndex)
    }

    @Test
    fun `migration closes and drains commit gate before source collection`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        )
        val migrationBody = methodBody(source, "runMigration")

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
        val migrationBody = methodBody(source, "runMigration")
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
        val workerBody = methodBody(workerSource, "runMigration")
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
        val recoveryBody = methodBody(
            managerSource,
            "reconcilePendingDownloadsBeforeMigrationDetailed"
        )
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
        val migrationBody = methodBody(source, "runMigration")
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
        val finalizationBody = methodBody(source, "finalizeCompletedDownload")
        val enrichmentBody = methodBody(source, "enrichCoreCommittedDownload")
        val enqueueBody = source.substringAfter(
            "val enrichmentJob = assetEnrichmentCoordinator.tryEnqueue("
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
        val body = methodBody(source, "releaseDownloadArtifactClaim")
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
        val finalizationBody = methodBody(source, "finalizeCompletedDownload")
        val enrichmentBody = methodBody(source, "enrichCoreCommittedDownload")

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
        val recoveryBody = methodBody(managerSource, "recoverPendingAudioWritesFromRoot")
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
    fun `owned core finalization preserves migration metadata promotion`() {
        val storageSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        )
        assertTrue(storageSource.contains("promotePendingMetadata: Boolean = false"))

        val managerSource = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val completionBody = methodBody(managerSource, "completeCoreDownloadAndEnqueueEnrichment")
        val promotion = completionBody.substringAfter(
            "val committedAudio = if (directoryMutationLeaseOwned"
        ).substringBefore("val artifactCommitted")

        assertTrue(
            completionBody.contains(
                "val committedAudio = if (directoryMutationLeaseOwned && storedAudio.isPendingAudioWrite)"
            )
        )
        assertTrue(
            promotion.contains(
                "promotePendingMetadata = directoryMutationLeaseOwned"
            )
        )
    }

    @Test
    fun `core publication records artifact before entering asset enrichment`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val completionBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val promotionIndex = completionBody.indexOf(
            "val publishedAudio = corePublicationCoordinator.promoteBeforePublication("
        )
        val artifactIndex = completionBody.indexOf("val artifactCommitted")
        val activeStatusIndex = completionBody.indexOf(
            "status = DownloadStatus.DOWNLOADING"
        )

        assertTrue(promotionIndex >= 0)
        assertTrue(artifactIndex > promotionIndex)
        assertTrue(activeStatusIndex > promotionIndex)
        assertFalse(completionBody.contains("publishOptimisticDownloadedSongs("))
    }

    @Test
    fun `stale admission retains core audio before skipping old publication`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val completionBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val coreResultIndex = completionBody.indexOf("val coreCommitResult =")
        val promotionIndex = completionBody.indexOf(
            "val publishedAudio = corePublicationCoordinator.promoteBeforePublication("
        )
        val staleReturnIndex = completionBody.indexOf(
            "core 提交后清空代次已失效"
        )

        assertTrue(coreResultIndex >= 0)
        assertTrue(promotionIndex > coreResultIndex)
        assertTrue(staleReturnIndex > promotionIndex)
    }

    @Test
    fun `clear fence keeps core pending evidence out of the delete transaction`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val completionBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val clearFenceIndex = completionBody.indexOf(
            "isDownloadClearFenceActive(context, stableKey = songKey, operationId = operationId)"
        )
        val promotionIndex = completionBody.indexOf(
            "val publishedAudio = corePublicationCoordinator.promoteBeforePublication("
        )

        assertTrue(clearFenceIndex >= 0)
        assertTrue(promotionIndex > clearFenceIndex)
    }

    @Test
    fun `migration promotion failure keeps a durable pending recovery entry`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val completionBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val migrationPromotionIndex = completionBody.indexOf(
            "迁移持有目录栅栏时先把 core 音频移出 .tmp"
        )
        val recoveryIndex = completionBody.indexOf(
            "reason = \"CORE_PUBLICATION_PENDING_DURING_DIRECTORY_MUTATION\""
        )
        val sharedRecoveryIndex = completionBody.indexOf(
            "deferPendingCorePublication("
        )

        assertTrue(migrationPromotionIndex >= 0)
        assertTrue(sharedRecoveryIndex > migrationPromotionIndex)
        assertTrue(recoveryIndex > sharedRecoveryIndex)
    }

    @Test
    fun `core deferral retains both artifact and durable staging evidence`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val recoveryBody = methodBody(source, "deferPendingCorePublication")
        assertTrue(recoveryBody.contains("managedDownloadArtifactCoordinator.markCoreCommitted("))
        assertTrue(recoveryBody.contains("markStagingPrepared("))
    }

    @Test
    fun `cancelled enrichment schedules pending artifact recovery`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val enrichmentBody = methodBody(source, "enrichCoreCommittedDownload")
        val cancellationIndex = enrichmentBody.indexOf(
            "catch (error: CancellationException)"
        )
        val degradedIndex = enrichmentBody.indexOf(
            "state = \"DEGRADED_COMPLETE\"",
            cancellationIndex
        )
        val recoveryIndex = enrichmentBody.indexOf(
            "scheduleStartupArtifactRecovery(context)",
            cancellationIndex
        )

        assertTrue(cancellationIndex >= 0)
        assertTrue(degradedIndex > cancellationIndex)
        assertTrue(recoveryIndex > degradedIndex)
    }

    @Test
    fun `source pending recovery settles operation journal before artifact promotion`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        )
        val body = methodBody(source, "recoverPendingAudioWritesFromRoot")
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
        assertTrue(afterPromotion.contains("val artifactCommitResult = runCatching"))
        assertTrue(afterPromotion.contains("artifactCommitResult?.isApplied == true"))
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
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate).readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $relativePath")
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )
}
