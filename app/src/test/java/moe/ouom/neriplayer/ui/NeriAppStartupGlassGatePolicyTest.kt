package moe.ouom.neriplayer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppStartupGlassGatePolicyTest {
    @Test
    fun appContentMountGateRetainsOriginalFrameAndFade() {
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val mountGate = source
            .substringAfter("internal fun AppContentMountGate(")
            .substringBefore("@Composable\nprivate fun NeriAppContent")

        assertTrue(source.contains("APP_CONTENT_FRAME_TIMEOUT_MS = 2_000L"))
        assertTrue(mountGate.contains("withFrameNanos"))
        assertTrue(mountGate.contains("AnimatedVisibility("))
        assertTrue(mountGate.contains("fadeIn("))
        assertTrue(mountGate.contains("tween(280"))
    }

    private fun source(path: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
