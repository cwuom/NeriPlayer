package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedPipelineRemovalTest {
    @Test
    fun `runtime download managers do not reference the prepared pipeline`() {
        val sourceFiles = listOf(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        )
        sourceFiles.forEach { path ->
            val source = locateProjectFile(path).readText()
            assertFalse(
                "$path still references the removed prepared pipeline",
                source.contains("PreparedDownloadArtifacts")
            )
        }
    }

    @Test
    fun `production sources do not reference the removed prepared artifact types`() {
        val sourceRoot = locateProjectDirectory()
            .resolve("app/src/main/java")
        val references = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readText().contains("PreparedDownloadArtifacts")
            }
            .toList()

        assertTrue(
            "removed PreparedDownloadArtifacts types are still referenced: $references",
            references.isEmpty()
        )
    }

    @Test
    fun `production download sources do not retain prepared runtime naming`() {
        val sourceRoot = locateProjectDirectory()
            .resolve("app/src/main/java/moe/ouom/neriplayer/core/download")
        val references = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().contains("PreparedDownload") }
            .toList()

        assertTrue(
            "prepared runtime naming is still referenced: $references",
            references.isEmpty()
        )
    }

    @Test
    fun `sidecar references do not retain the removed prepared marker`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()

        assertFalse(
            "DownloadedSidecarReferences still exposes the removed prepared marker",
            source.contains("val prepared: Boolean") ||
                source.contains("prepared =")
        )
    }

    @Test
    fun `production download manager does not persist stable key cancellation markers`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()

        assertFalse(
            "new cancellation ownership must stay on download_operation",
            source.contains("markCancelledDownloadKeys") ||
                source.contains("listCancelledDownloadKeys")
        )
    }

    @Test
    fun `download execution mutex does not use a 32 bit stable key hash`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()

        assertFalse(
            "execution ownership must not use String.hashCode()",
            source.contains("songKey.hashCode()")
        )
    }

    @Test
    fun `working file creation does not use the legacy 32 bit hash`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/working/" +
                "ManagedDownloadWorkingStore.kt"
        ).readText()

        assertFalse(
            "new staging ownership must not call the legacy hash helper",
            source.substringAfter("fun buildWorkingFileName(")
                .substringBefore("fun buildWorkingSongKeyHash(")
                .contains("legacyWorkingSongKeyHash")
        )
    }

    private fun locateProjectDirectory(): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        var attempts = 0
        while (attempts++ < 5) {
            if (File(directory, "app/src/main/java").isDirectory) return directory
            val parent = directory.parentFile ?: break
            directory = parent
        }
        error("project source directory not found")
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        var attempts = 0
        while (attempts++ < 5) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            val parent = directory.parentFile ?: break
            directory = parent
        }
        error("source file not found: $path")
    }
}
