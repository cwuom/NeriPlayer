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
