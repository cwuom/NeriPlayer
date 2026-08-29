package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * locks down the remaining manager call-graph work before the runtime cleanup
 */
class GlobalDownloadManagerLegacyRuntimeCharacterizationTest {
    @Test
    fun `core commit publishes operation journal linearization points`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val committingIndex = indexOfOperationCall(body, "markCommitting")
        val metadataWriteIndex = body.indexOf("persistDownloadedMetadata")
        val coreCommittedIndex = indexOfOperationCall(body, "markCoreCommitted")
        val enrichmentDispatchIndex = body.indexOf("assetEnrichmentCoordinator.enqueue(")
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
    fun `core commit publishes a playable catalog entry before slow asset enrichment`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val coreCommittedIndex = body.indexOf("markCoreCommitted")
        val optimisticPublishIndex = body.indexOf("publishOptimisticDownloadedSongs")
        val enrichmentDispatchIndex = body.indexOf("assetEnrichmentCoordinator.enqueue(")

        assertTrue(
            "a core-committed audio must be visible before lyrics/cover processing",
            coreCommittedIndex >= 0 &&
                optimisticPublishIndex > coreCommittedIndex &&
                enrichmentDispatchIndex > optimisticPublishIndex
        )
    }

    @Test
    fun `core recovery registers the playback bridge before durable preview publication`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val bridgeIndex = body.indexOf("AudioDownloadManager.rememberCompletedAudioReference(")
        val roomPreviewIndex = body.indexOf("ManagedLibraryItemRoomStore.upsert(")
        val catalogIndex = body.indexOf("publishOptimisticDownloadedSongs(")

        assertTrue(
            "a recovered core audio must be reachable before Room or catalog publication",
            bridgeIndex >= 0 &&
                roomPreviewIndex > bridgeIndex &&
                catalogIndex > bridgeIndex
        )
    }

    @Test
    fun `final promotion keeps a playable in memory bridge until catalog publication`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "publishFinalizedDownload")
        val promotionIndex = body.indexOf("promoteFinalizedPendingAudio(")
        val bridgeIndex = body.indexOf("rememberCompletedAudioReference(")
        val markFinalizedIndex = body.indexOf("managedDownloadArtifactCoordinator.markFinalized(")
        val publishIndex = body.indexOf("publishCompletedDownloadOptimistically(")
        val releaseIndex = body.indexOf("releaseCompletedAudioReference(")

        assertTrue(
            "the final URI must replace the invalidated pending bridge immediately",
            promotionIndex >= 0 && bridgeIndex > promotionIndex
        )
        assertTrue(
            "artifact and catalog publication must happen before bridge cleanup",
            markFinalizedIndex > bridgeIndex &&
                publishIndex > markFinalizedIndex &&
                releaseIndex > publishIndex
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
        val fastIndexRemovalIndex = deleteBody.indexOf("removeFastIndexEntry(")

        assertTrue(resolvedDeletionIndex >= 0)
        assertTrue(fastIndexRemovalIndex > resolvedDeletionIndex)
        assertFalse(deleteBody.contains("persistFastIndex("))
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
        val body = methodBody(source, "stageAndPromotePendingDownloadQueue")
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

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = Regex(
            "(?:private|internal|public)?\\s*(?:suspend\\s+)?fun\\s+$methodName\\b"
        ).find(source)?.range?.first
            ?: error("method not found: $methodName")
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unterminated method body: $methodName")
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
