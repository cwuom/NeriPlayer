package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCorePublicationCoordinatorTest {
    @Test
    fun `core publication retries legacy pending metadata before catalog exposure`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/DownloadCorePublicationCoordinator.kt"
        ).readText()
        val fastPath = source.indexOf("promotePendingMetadata = false")
        val legacyPath = source.indexOf("promotePendingMetadata = true")
        val rescanPath = source.indexOf("ManagedDownloadStorage.findDownloadedAudio(")

        assertTrue(fastPath >= 0)
        assertTrue(legacyPath > fastPath)
        assertTrue(rescanPath > legacyPath)
        assertTrue(source.contains("takeUnless { it.isPendingAudioWrite }"))
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
