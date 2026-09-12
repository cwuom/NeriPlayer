package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadExecutionHostCharacterizationTest {
    @Test
    fun `batch production path does not bypass the OS execution host`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()

        assertFalse(
            "batch path still calls the legacy playlist transfer loop",
            source.contains("AudioDownloadManager.downloadPlaylist")
        )
    }

    @Test
    fun `batch reuses the durable queue operation instead of creating a second identity`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val batchPath = source.substringAfter("private fun startBatchDownloadConfirmed")
            .substringBefore("private fun scheduleCatalogReconcile")

        assertTrue(
            "batch path must resolve its host request from the persisted queue operation",
            batchPath.contains("operationIdsBySongKey") &&
                batchPath.contains("val operationId = request.operationId")
        )
        assertFalse(
            "batch path must not create a second operation after queue persistence",
            batchPath.contains("ensureQueuedOperationForSong") ||
                batchPath.contains("val operationId = UUID.randomUUID().toString()")
        )
    }

    @Test
    fun `batch artifact lease is owned by the durable queue operation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val artifactBody = methodBody(source, "claimAndPrepareBatchArtifact")

        assertTrue(
            "batch claim must use the durable artifact lease identity",
            artifactBody.contains(
                "val operationId = session.operationIdsBySongKey[songKey]"
            ) &&
                artifactBody.contains(
                    "leaseOwnerId = operationRequest.artifactLeaseId"
                ) &&
                artifactBody.contains("operationId = operationId")
        )
    }

    @Test
    fun `batch does not cache in flight artifact ownership across preparation retries`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val artifactBody = methodBody(source, "claimAndPrepareBatchArtifact")

        assertTrue(
            "an in-flight lease must be rechecked instead of surviving for the whole batch",
            artifactBody.contains("takeUnless { claim -> claim is ManagedDownloadArtifactClaim.InFlight }") &&
                artifactBody.contains("session.artifactClaims.remove(songKey)")
        )
        assertTrue(
            "a lease with no durable or in-memory owner must be reclaimed explicitly",
            artifactBody.contains("reclaimOrphanedTransferLeaseIfSafe(")
        )
    }

    @Test
    fun `orphan reclaim does not let the current retry operation self lock`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val reclaimBody = methodBody(source, "reclaimOrphanedTransferLeaseIfSafe")

        assertTrue(reclaimBody.contains("candidateId == normalizedOperationId"))
        assertTrue(reclaimBody.contains("readOperationHeaders("))
        assertTrue(reclaimBody.contains("explicitlyCancelledOperationIds"))
    }

    @Test
    fun `terminal callbacks settle batches by operation identity when task state is gone`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val taskStatusStart = source.indexOf("    fun updateTaskStatus(")
        require(taskStatusStart >= 0) { "method not found: updateTaskStatus" }
        val taskStatusBody = source.substring(
            taskStatusStart,
            source.indexOf("    private fun publishDownloadStage(", taskStatusStart)
        )
        val terminalBody = methodBody(source, "persistBatchMemberTerminal")
        val presentationBody = methodBody(source, "markBatchDownloadPresentationTerminal")

        assertTrue(taskStatusBody.contains("operationId: String? = null"))
        assertTrue(taskStatusBody.contains("!updated && operationId.isNullOrBlank()"))
        assertTrue(terminalBody.contains("markBatchMembersForOperation("))
        assertTrue(presentationBody.contains("val effectiveOperationId"))
    }

    @Test
    fun `batch launch delegates preparation and scheduling out of its coroutine lambda`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val launchBody = methodBody(source, "startBatchDownloadConfirmed")

        assertTrue(
            "the launch lambda must delegate instead of inlining a large batch state machine",
            launchBody.contains("runBatchDownloadSession(")
        )
        assertFalse(
            "the launch body must not own artifact claims directly",
            launchBody.contains("managedDownloadArtifactCoordinator.claim(")
        )
        assertFalse(
            "the launch body must not own individual OS scheduling directly",
            launchBody.contains("DownloadExecutionHosts.default.schedule(")
        )
    }

    @Test
    fun `schedule rejection keeps the durable operation journal`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val rejectionBlocks = Regex(
            "DownloadExecutionSchedule\\.Rejected[\\s\\S]{0,700}"
        ).findAll(source).map { it.value }.toList()

        assertTrue("expected OS host rejection handling", rejectionBlocks.isNotEmpty())
        assertTrue(
            "rejected scheduling must leave the operation journal for retry",
            rejectionBlocks.all { block -> !block.contains("DownloadExecutionRoomStore.delete") }
        )
    }

    @Test
    fun `queued host stages publish through the durable task projection`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val stageBody = methodBody(source, "publishDownloadStage")

        assertTrue(stageBody.contains("updateDownloadProgress("))
        assertTrue(stageBody.contains("AudioDownloadManager.DownloadProgress("))
        assertFalse(stageBody.contains("AudioDownloadManager.publishStageProgress("))
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

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = source.indexOf("private fun $methodName(").takeIf { it >= 0 }
            ?: source.indexOf("private suspend fun $methodName(")
        require(signatureStart >= 0) { "method not found: $methodName" }
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
}
