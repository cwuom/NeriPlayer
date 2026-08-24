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
            batchPath.contains("findQueuedOperationIdForSong")
        )
        assertFalse(
            "batch path must not create a second operation after queue persistence",
            batchPath.contains("val operationId = UUID.randomUUID().toString()")
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
}
