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
