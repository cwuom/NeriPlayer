package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.catalog.ManagedLibraryItemRoomStore
import moe.ouom.neriplayer.core.download.manager.admission.scheduleStartupArtifactRecovery
import moe.ouom.neriplayer.core.download.manager.admission.stageAndPromotePendingDownloadQueuePage
import moe.ouom.neriplayer.core.download.manager.catalog.deleteDownloadedSongsOnIo
import moe.ouom.neriplayer.core.download.manager.catalog.markDownloadArtifactRepairRequired
import moe.ouom.neriplayer.core.download.manager.catalog.persistConfirmedEmptyDownloadedSongsCatalog
import moe.ouom.neriplayer.core.download.manager.catalog.reloadDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.restoreDeferredDownloadedSongDeleteSession
import moe.ouom.neriplayer.core.download.manager.commit.completeCoreDownloadAndEnqueueEnrichment
import moe.ouom.neriplayer.core.download.manager.commit.enrichCoreCommittedDownload
import moe.ouom.neriplayer.core.download.manager.commit.ensureCoreRecoveryOperation
import moe.ouom.neriplayer.core.download.manager.commit.handleCancelledCompletedDownload
import moe.ouom.neriplayer.core.download.manager.commit.preserveUnsupportedMetadataEmbedding
import moe.ouom.neriplayer.core.download.manager.commit.publishFinalizedDownload
import moe.ouom.neriplayer.core.download.manager.commit.schedulePostCoreEnrichmentRetry
import moe.ouom.neriplayer.core.download.manager.commit.settlePostCoreEnrichmentFailure
import moe.ouom.neriplayer.core.download.manager.commit.verifyFinalizedDownloadedArtifactForPublication
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingAudioWritesFromRoot
import moe.ouom.neriplayer.core.download.manager.recovery.recoverUnfinalizedPublishedAudioFromRoot
import moe.ouom.neriplayer.core.download.manager.runtime.executionResultForOperation
import moe.ouom.neriplayer.core.download.manager.runtime.publishCompletedDownloadOptimistically
import moe.ouom.neriplayer.core.download.manager.runtime.publishOptimisticDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.runtime.scheduleUserDownload
import moe.ouom.neriplayer.core.download.manager.runtime.songExecutionMutex
import moe.ouom.neriplayer.core.download.manager.runtime.updateFastIndexAfterMetadataEdit
import moe.ouom.neriplayer.core.download.manager.runtime.upsertCompletedFastIndexEntry
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.resolveDownloadedSongDeleteResult
import moe.ouom.neriplayer.core.download.policy.FinalizedDownloadPublicationResult
import moe.ouom.neriplayer.core.download.policy.isDurableCoreArtifactState
import moe.ouom.neriplayer.core.download.policy.resolvePostCoreEnrichmentTaskStatus
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定运行时清理前下载管理器调用链的关键行为
 */
class GlobalDownloadManagerLegacyRuntimeCharacterizationTest {
    @Test
    fun `completed transfer passes its exact audio reference into finalization`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "startDownloadConfirmed")
        val transferIndex = body.indexOf("val transferredAudio = try")
        val downloadIndex = body.indexOf(
            "AudioDownloadManager.downloadSongWithResult(",
            transferIndex
        )
        val hintIndex = body.indexOf("storedAudioHint = transferredAudio", downloadIndex)

        assertTrue(transferIndex >= 0)
        assertTrue(downloadIndex > transferIndex)
        assertTrue(hintIndex > downloadIndex)
    }

    @Test
    fun `temporarily missing completed audio remains retryable and keeps its queue entry`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "finalizeCompletedDownload")
        val missingBranch = body
            .substringAfter("CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO ->")
            .substringBefore("CompletedDownloadFinalizationAction.COMPLETE ->")

        assertTrue(missingBranch.contains("DownloadStatus.QUEUED"))
        assertTrue(missingBranch.contains("state = \"RETRYABLE\""))
        assertTrue(missingBranch.contains("markDownloadArtifactRetryable("))
        assertTrue(missingBranch.contains("scheduleStartupArtifactRecovery("))
        assertTrue(missingBranch.contains("wakeDownloadExecutionPump("))
        assertFalse(missingBranch.contains("DownloadStatus.FAILED"))
        assertFalse(missingBranch.contains("markDownloadArtifactMissingConfirmed("))
        assertFalse(missingBranch.contains("forgetPendingDownloadQueueEntries"))
    }

    @Test
    fun `artifact commit keeps core work active until final publication`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val artifactCallIndex = body.indexOf(
            "managedDownloadArtifactCoordinator.markCoreCommitted("
        )
        val artifactResultIndex = body.lastIndexOf(
            "val artifactCommitResult",
            artifactCallIndex
        )
        val artifactCommittedIndex = body.indexOf(
            "val artifactCommitted",
            artifactCallIndex
        )
        val rejectionIndex = body.indexOf("if (!artifactCommitted)", artifactCommittedIndex)
        val bridgeIndex = body.indexOf(
            "AudioDownloadManager.rememberCompletedAudioReference(",
            rejectionIndex
        )
        val activeTaskIndex = body.indexOf(
            "status = DownloadStatus.DOWNLOADING",
            rejectionIndex
        )
        val enrichmentStageIndex = body.indexOf(
            "stage = AudioDownloadManager.DownloadStage.ASSETS_ENRICHING",
            rejectionIndex
        )
        val enrichmentIndex = body.indexOf("assetEnrichmentCoordinator.tryEnqueue(", rejectionIndex)

        assertTrue(artifactResultIndex >= 0)
        assertTrue(artifactCommittedIndex > artifactCallIndex)
        assertTrue(rejectionIndex > artifactCommittedIndex)
        assertTrue(
            body.substring(artifactResultIndex, artifactCommittedIndex).contains("getOrNull")
        )
        assertTrue(
            body.substring(artifactCommittedIndex, rejectionIndex).contains("isApplied")
        )
        assertTrue(bridgeIndex > rejectionIndex)
        assertTrue(activeTaskIndex > bridgeIndex)
        assertTrue(enrichmentStageIndex > activeTaskIndex)
        assertTrue(enrichmentIndex > rejectionIndex)
        assertFalse(body.contains("publishOptimisticDownloadedSongs("))
        assertFalse(body.contains("status = DownloadStatus.COMPLETED"))
        val rejectionBody = body.substring(rejectionIndex, bridgeIndex)
        assertTrue(rejectionBody.contains("ensureCoreRecoveryOperation"))
        assertTrue(rejectionBody.contains("return"))
        assertFalse(rejectionBody.contains("DownloadStatus.FAILED"))
        assertFalse(rejectionBody.contains("markDownloadArtifactRepairRequired"))
    }

    @Test
    fun `migration core artifact commit uses the source root identity`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val sourceRootIndex = body.indexOf(
            "val sourceArtifactRootKey = if (directoryMutationLeaseOwned)"
        )
        val snapshotIndex = body.indexOf(
            "ManagedDownloadStorage.snapshotRootKeyForOperation(",
            sourceRootIndex
        )
        val currentLeaseIndex = body.indexOf(
            "managedDownloadArtifactCoordinator.currentLeaseId(",
            snapshotIndex
        )
        val artifactCallIndex = body.indexOf(
            "managedDownloadArtifactCoordinator.markCoreCommitted(",
            sourceRootIndex
        )

        assertTrue(sourceRootIndex >= 0)
        assertTrue(snapshotIndex > sourceRootIndex)
        assertTrue(currentLeaseIndex > snapshotIndex)
        assertTrue(artifactCallIndex > currentLeaseIndex)
        val sourceRootBody = body.substring(sourceRootIndex, artifactCallIndex)
        assertTrue(sourceRootBody.contains("directoryUri = directoryUri"))
        assertTrue(sourceRootBody.contains("useDefaultRootWhenDirectoryUriMissing = true"))
        val artifactBody = body.substring(
            artifactCallIndex,
            body.indexOf("if (!artifactCommitted)", artifactCallIndex)
        )
        assertTrue(artifactBody.contains("rootKeyOverride = sourceArtifactRootKey"))
    }

    @Test
    fun `core commit publishes operation journal linearization points`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val committingIndex = indexOfOperationCall(body, "markCommitting")
        val metadataWriteIndex = body.indexOf("persistDownloadedMetadata")
        val coreCommittedIndex = indexOfOperationCall(body, "markCoreCommitted")
        val enrichmentDispatchIndex = body.indexOf("assetEnrichmentCoordinator.tryEnqueue(")
        val enrichmentBody = methodBody(source, "enrichCoreCommittedDownload")
        val finalizedBody = methodBody(source, "publishFinalizedDownload")
        val completedIndex = finalizedBody.indexOf("DownloadStatus.COMPLETED")
        val journalFailureIndex = body.indexOf("if (!journalCommitted)")
        val journalFailureReturnIndex = body.indexOf(
            "return@withContext false",
            startIndex = journalFailureIndex
        )

        assertTrue(
            "core commit must mark the durable operation COMMITTING before metadata I/O",
            committingIndex >= 0 && metadataWriteIndex >= 0 && committingIndex < metadataWriteIndex
        )
        assertTrue(
            "core commit must mark the durable operation CORE_COMMITTED after metadata I/O",
            coreCommittedIndex > metadataWriteIndex
        )
        assertTrue(
            "enrichment must be dispatched only after the operation core commit",
            enrichmentDispatchIndex > coreCommittedIndex &&
                enrichmentBody.contains("publishFinalizedDownload(") &&
                completedIndex >= 0
        )
        assertFalse(
            "core commit must not keep the network host waiting for asset enrichment",
            body.contains("enqueueAndAwait") || body.contains(".join()")
        )
        assertTrue(
            "a failed core journal commit must stop final completion",
            journalFailureIndex > coreCommittedIndex &&
                journalFailureReturnIndex > journalFailureIndex
        )
    }

    @Test
    fun `core commit defers catalog publication until slow asset enrichment is complete`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val coreCommittedIndex = body.indexOf("markCoreCommitted")
        val activeTaskIndex = body.indexOf("status = DownloadStatus.DOWNLOADING")
        val enrichmentStageIndex = body.indexOf(
            "stage = AudioDownloadManager.DownloadStage.ASSETS_ENRICHING"
        )
        val enrichmentDispatchIndex = body.indexOf("assetEnrichmentCoordinator.tryEnqueue(")

        assertTrue(
            "a core-committed audio must remain an active enrichment task",
            coreCommittedIndex >= 0 &&
                activeTaskIndex > coreCommittedIndex &&
                enrichmentStageIndex > activeTaskIndex &&
                enrichmentDispatchIndex > enrichmentStageIndex
        )
        assertFalse(body.contains("publishOptimisticDownloadedSongs"))
    }

    @Test
    fun `core commit wake is best effort and cannot turn durable audio into failure`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val wakeBody = methodBody(source, "wakeDownloadExecutionPumpAfterCoreCommit")
        assertTrue(wakeBody.contains("runCatching"))
        assertTrue(wakeBody.contains("wakeDownloadExecutionPump("))
        assertTrue(wakeBody.contains("保留持久队列"))
        assertFalse(wakeBody.contains("throw error"))
    }

    @Test
    fun `core recovery registers the playback bridge before active enrichment presentation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val bridgeIndex = body.indexOf("AudioDownloadManager.rememberCompletedAudioReference(")
        val activeTaskIndex = body.indexOf("status = DownloadStatus.DOWNLOADING")
        val stageIndex = body.indexOf(
            "stage = AudioDownloadManager.DownloadStage.ASSETS_ENRICHING"
        )

        assertTrue(
            "a recovered core audio must be reachable before the active enrichment task",
            bridgeIndex >= 0 &&
                activeTaskIndex > bridgeIndex &&
                stageIndex > activeTaskIndex
        )
        assertFalse(body.contains("ManagedLibraryItemRoomStore.upsert("))
        assertFalse(body.contains("publishOptimisticDownloadedSongs("))
    }

    @Test
    fun `final promotion keeps a playable in memory bridge until catalog publication`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "publishFinalizedDownload")
        val promotionIndex = body.indexOf("promoteFinalizedPendingAudio(")
        val integrityIndex = body.indexOf("verifyFinalizedDownloadedArtifactForPublication(")
        val bridgeIndex = body.indexOf("rememberCompletedAudioReference(")
        val markFinalizedIndex = body.indexOf("managedDownloadArtifactCoordinator.markFinalized(")
        val publishIndex = body.indexOf("publishCompletedDownloadOptimistically(")
        val releaseIndex = body.indexOf(
            "releaseCompletedAudioReference(",
            publishIndex
        )

        assertTrue(
            "the final URI must replace the invalidated pending bridge immediately",
            promotionIndex >= 0 && integrityIndex > promotionIndex && bridgeIndex > integrityIndex
        )
        assertTrue(
            "artifact and catalog publication must happen before bridge cleanup",
            markFinalizedIndex > bridgeIndex &&
                publishIndex > markFinalizedIndex &&
                releaseIndex > publishIndex
        )
    }

    @Test
    fun `final publication does not close the operation when artifact finalization is unconfirmed`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "publishFinalizedDownload")
        val artifactResultIndex = body.indexOf("val artifactFinalized")
        val rejectionIndex = body.indexOf("if (!artifactFinalized)", artifactResultIndex)
        val catalogIndex = body.indexOf("publishCompletedDownloadOptimistically(", rejectionIndex)
        val taskCompletionIndex = body.indexOf("DownloadStatus.COMPLETED", rejectionIndex)
        val operationFinalizedIndex = body.indexOf("state = \"FINALIZED\"", rejectionIndex)

        assertTrue(artifactResultIndex >= 0)
        assertTrue(rejectionIndex > artifactResultIndex)
        assertTrue(catalogIndex > rejectionIndex)
        assertTrue(taskCompletionIndex > catalogIndex)
        assertTrue(operationFinalizedIndex > taskCompletionIndex)
        val rejectionBody = body.substring(rejectionIndex, catalogIndex)
        assertTrue(rejectionBody.contains("scheduleStartupArtifactRecovery"))
        assertTrue(
            rejectionBody.contains(
                "return FinalizedDownloadPublicationResult.RECOVERY_REQUIRED"
            )
        )
    }

    @Test
    fun `detached enrichment keeps process death recovery entry points after initial scan`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val coreCommitBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val initializeBody = methodBody(source, "initialize")
        val scanIndex = initializeBody.indexOf("scanLocalFilesAwait(")
        val deferredRecoveryIndex = initializeBody.indexOf(
            "scheduleStartupArtifactRecovery(appContext)",
            startIndex = scanIndex
        )

        assertTrue(coreCommitBody.contains("ManagedDownloadArtifactState.CORE_COMMITTED.name"))
        assertTrue(coreCommitBody.contains("markCoreCommitted"))
        assertTrue(
            "artifact recovery must be dispatched after the initial scan publishes",
            scanIndex >= 0 && deferredRecoveryIndex > scanIndex
        )
        assertFalse(initializeBody.contains("recoverPendingAudioWritesFromRoot(appContext)"))
        assertFalse(initializeBody.contains("recoverUnfinalizedPublishedAudioFromRoot(appContext)"))
    }

    @Test
    fun `detached enrichment settles its network host without completing the operation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val resultBody = methodBody(source, "executionResultForOperation")

        assertTrue(
            Regex(
                "\"CORE_COMMITTED\"\\s*,\\s*" +
                    "\"ASSETS_ENRICHING\"\\s*->\\s*" +
                    "return\\s+DownloadExecutionResult\\.AlreadyHandled"
            ).containsMatchIn(resultBody)
        )
        assertFalse(
            Regex(
                "\"CORE_COMMITTED\"\\s*,\\s*" +
                    "\"ASSETS_ENRICHING\"\\s*->\\s*" +
                    "return\\s+DownloadExecutionResult\\.Retry"
            ).containsMatchIn(resultBody)
        )
    }

    @Test
    fun `post core enrichment failures keep a durable bounded retry handoff`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val coreBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val enrichmentBody = methodBody(source, "enrichCoreCommittedDownload")
        val unsupportedBody = methodBody(source, "preserveUnsupportedMetadataEmbedding")
        val settleBody = methodBody(source, "settlePostCoreEnrichmentFailure")

        assertTrue(coreBody.contains("settlePostCoreEnrichmentFailure"))
        assertTrue(enrichmentBody.contains("settlePostCoreEnrichmentFailure"))
        assertTrue(unsupportedBody.contains("settlePostCoreEnrichmentFailure"))
        assertTrue(settleBody.contains("resolvePostCoreEnrichmentTaskStatus"))
        assertTrue(settleBody.contains("DownloadStatus.QUEUED"))
        assertTrue(
            settleBody.contains("stage = AudioDownloadManager.DownloadStage.WAITING_RETRY")
        )
        assertTrue(settleBody.contains("schedulePostCoreEnrichmentRetry"))
        assertFalse(enrichmentBody.contains("DownloadStatus.FAILED"))
        assertFalse(unsupportedBody.contains("DownloadStatus.FAILED"))
    }

    @Test
    fun `post core cancellation retains its artifact lease before retry handoff`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "enrichCoreCommittedDownload")
        val cancellationIndex = body.indexOf(
            "} catch (error: CancellationException)"
        )
        val currentStateIndex = body.indexOf(
            "val currentState =",
            cancellationIndex
        )
        val retryEligibilityIndex = body.indexOf(
            "val canRetry =",
            cancellationIndex
        )
        val leaseRetentionIndex = body.indexOf(
            "retainLease = true",
            cancellationIndex
        )
        val leaseSettlementIndex = body.indexOf("settleLeaseAnyRoot(", cancellationIndex)

        assertTrue(cancellationIndex >= 0)
        assertTrue(currentStateIndex > cancellationIndex)
        assertTrue(retryEligibilityIndex > currentStateIndex)
        assertTrue(leaseRetentionIndex > retryEligibilityIndex)
        assertTrue(leaseSettlementIndex > leaseRetentionIndex)
        assertTrue(
            body.substring(leaseRetentionIndex, leaseSettlementIndex)
                .contains("ASSET_ENRICHMENT_CANCELLED")
        )
    }

    @Test
    fun `stale final publication does not enter post core failure retry`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "enrichCoreCommittedDownload")
        val staleIndex = body.indexOf("FinalizedDownloadPublicationResult.STALE")
        val returnIndex = body.indexOf("return", staleIndex)
        val failureCatchIndex = body.indexOf("} catch (error: Throwable)", staleIndex)

        assertTrue(staleIndex >= 0)
        assertTrue(returnIndex > staleIndex)
        assertTrue(failureCatchIndex > returnIndex)
        assertFalse(
            body.substring(staleIndex, returnIndex)
                .contains("settlePostCoreEnrichmentFailure")
        )
    }

    @Test
    fun `final metadata keeps the durable operation identity for restart recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val enrichmentBody = methodBody(source, "enrichCoreCommittedDownload")
        val finalizedIndex = enrichmentBody.indexOf("downloadFinalized = true")
        val nextCallEnd = enrichmentBody.indexOf("\n                )", finalizedIndex)

        assertTrue(finalizedIndex >= 0)
        assertTrue(nextCallEnd > finalizedIndex)
        assertTrue(
            enrichmentBody.substring(finalizedIndex, nextCallEnd)
                .contains("operationId = operationId")
        )
    }

    @Test
    fun `cancelled completion consults durable operation state before rollback`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "handleCancelledCompletedDownload")

        assertTrue(
            "late cancellation must consult the operation journal, not only an in-memory flag",
            body.contains("currentState") && body.contains("DownloadExecution")
        )
        assertTrue(
            "late cancellation must recognize a durable core-committed state",
            body.contains("CORE_COMMITTED") || body.contains("isDurableCoreArtifactState")
        )
    }

    @Test
    fun `reload path does not run the removed legacy finalization branch`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "reloadDownloadedSongs")

        assertFalse(
            "v15 compatibility data must not re-enter the old runtime finalization flow",
            body.contains("finalizeUnfinalizedDownloadedAudio") ||
                body.contains("isUnfinalizedDownloadedMetadata")
        )
    }

    @Test
    fun `cancel runtime does not retain the legacy cancelled artifact recovery branch`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()

        assertFalse(
            "cancel must use the operation-owned cleanup path only",
            source.contains("recoverCancelledArtifacts") ||
                source.contains("scheduleCancelledArtifactRecovery") ||
                source.contains("recoverUnfinalizedDownloadArtifact")
        )
    }

    @Test
    fun `song execution lock does not derive ownership from a 32 bit hash`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "songExecutionMutex")

        assertFalse(
            "persistent or correctness ownership must not use String.hashCode",
            body.contains("hashCode")
        )
    }

    @Test
    fun `download completion and metadata edits update the fast index incrementally`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val publishBody = methodBody(source, "publishCompletedDownloadOptimistically")
        val metadataSyncBody = methodBody(source, "syncDownloadedSongMetadataNow")
        val metadataEditBody = methodBody(source, "updateFastIndexAfterMetadataEdit")
        val fastIndexBody = methodBody(source, "upsertCompletedFastIndexEntry")

        assertTrue(publishBody.contains("upsertCompletedFastIndexEntry("))
        assertTrue(metadataSyncBody.contains("updateFastIndexAfterMetadataEdit("))
        assertTrue(metadataEditBody.contains("updateExistingFastIndexEntry("))
        assertTrue(metadataEditBody.contains("upsertCompletedFastIndexEntry("))
        assertTrue(fastIndexBody.contains("upsertCompleteFastIndexEntry("))
        assertFalse(fastIndexBody.contains("persistFastIndex("))
    }

    @Test
    fun `confirmed physical deletion removes only the matching fast index entry`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val deleteBody = methodBody(source, "deleteDownloadedSongsOnIo")
        val resolvedDeletionIndex = deleteBody.indexOf("resolveDownloadedSongDeleteResult(")
        val fastIndexRemovalIndex = deleteBody.indexOf("removeFastIndexEntries(")

        assertTrue(resolvedDeletionIndex >= 0)
        assertTrue(fastIndexRemovalIndex > resolvedDeletionIndex)
        assertFalse(deleteBody.contains("persistFastIndex("))
    }

    @Test
    fun `full delete uses one batch artifact cleanup before catalog persistence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val deleteBody = methodBody(source, "deleteDownloadedSongsOnIo")
        val artifactIndex = deleteBody.indexOf("deleteAllAfterCancellationSettled(")
        val catalogIndex = deleteBody.indexOf("persistConfirmedEmptyDownloadedSongsCatalog(")

        assertTrue(artifactIndex >= 0)
        assertTrue(catalogIndex > artifactIndex)
        assertFalse(
            "全库清理不能退化为逐首打开 artifact 事务",
            deleteBody.contains("deleteByStableKey(")
        )
    }

    @Test
    fun `download deletion is fenced against directory migration`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val asyncBody = methodBody(source, "deleteDownloadedSongs")
        val resultBody = methodBody(source, "deleteDownloadedSongsWithResult")

        assertTrue(
            "asynchronous deletion must acquire the directory mutation lease",
            asyncBody.contains("acquireDeleteLeaseOrNull")
        )
        assertTrue(
            "synchronous deletion must acquire the directory mutation lease",
            resultBody.contains("acquireDeleteLeaseOrNull")
        )
        assertTrue(
            "deferred deletion must restore the hidden catalog entries",
            source.contains("restoreDeferredDownloadedSongDeleteSession")
        )
    }

    @Test
    fun `manual resume keeps the task until a durable operation can be staged`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "resumeDownloadTask")
        val launchIndex = body.indexOf("scope.launch")
        val scheduleIndex = body.indexOf("scheduleUserDownload(")

        assertTrue(
            "resume must schedule asynchronously after cancellation settles",
            launchIndex >= 0 && scheduleIndex > launchIndex
        )
        assertFalse(
            "resume must not remove the only visible task before Room staging succeeds",
            body.contains("removeDownloadTask(")
        )
        assertTrue(
            "a cancelled operation must be purged before its deterministic id is reused",
            body.contains("purgeCancelled")
        )
    }

    @Test
    fun `directory mutation defers existing retryable operations instead of hiding them`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "stageAndPromotePendingDownloadQueuePage")
        val lookupIndex = body.indexOf("existingReusableOperationIds")
        val deferIndex = body.indexOf("markWaitingForStorageMutation")
        val returnedIdsIndex = body.indexOf("allWaitingOperationIds")

        assertTrue(
            "storage mutation must include existing retryable operations",
            lookupIndex >= 0 && deferIndex > lookupIndex && returnedIdsIndex > deferIndex
        )
    }

    private fun indexOfOperationCall(source: String, methodName: String): Int {
        return Regex(
            "(?:DownloadExecutionRoomStore|DownloadExecutionOperationStore)[^\\n{}]*\\b$methodName\\b"
        ).find(source)?.range?.first ?: -1
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
