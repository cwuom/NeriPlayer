package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
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

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("source file not found: $path")
    }
}
