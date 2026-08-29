package moe.ouom.neriplayer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppStartupGlassGatePolicyTest {
    @Test
    fun appContentMountGateMountsImmediatelyWithoutStartupAnimation() {
        val source = source("app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt")
        val mountGate = source
            .substringAfter("internal fun AppContentMountGate(")
            .substringBefore("@Composable\nprivate fun NeriAppContent")

        assertTrue(mountGate.contains("content()"))
        assertFalse(mountGate.contains("withFrameNanos"))
        assertFalse(mountGate.contains("AnimatedVisibility("))
        assertFalse(mountGate.contains("fadeIn("))
        assertFalse(mountGate.contains("withTimeoutOrNull"))
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
