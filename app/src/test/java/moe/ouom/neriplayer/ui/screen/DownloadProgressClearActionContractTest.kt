package moe.ouom.neriplayer.ui.screen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressClearActionContractTest {

    @Test
    fun `clear action stays disabled while durable fence is active`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/DownloadProgressScreen.kt"
        ).readText()
        val topBar = source.substringAfter("TopAppBar(")
            .substringBefore("when (pagePresentation)")

        assertTrue(topBar.contains("enabled = !clearFenceActive"))
        assertFalse(topBar.contains("enabled = !effectiveIsClearing"))
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
