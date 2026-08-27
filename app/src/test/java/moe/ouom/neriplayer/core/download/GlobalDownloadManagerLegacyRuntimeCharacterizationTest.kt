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
    fun `detached enrichment keeps process death recovery entry points`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val coreCommitBody = methodBody(source, "completeCoreDownloadAndEnqueueEnrichment")
        val initializeBody = methodBody(source, "initialize")

        assertTrue(coreCommitBody.contains("ManagedDownloadArtifactState.CORE_COMMITTED.name"))
        assertTrue(coreCommitBody.contains("markCoreCommitted"))
        assertTrue(initializeBody.contains("recoverPendingAudioWritesFromRoot(appContext)"))
        assertTrue(initializeBody.contains("recoverUnfinalizedPublishedAudioFromRoot(appContext)"))
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
