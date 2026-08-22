package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
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
