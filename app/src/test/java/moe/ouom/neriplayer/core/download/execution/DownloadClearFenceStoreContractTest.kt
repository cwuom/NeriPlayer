package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadClearFenceStoreContractTest {

    @Test
    fun `begin clear reuses a pending in process epoch`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "beginClear")

        assertTrue(body.contains("currentEpoch > clearedRequestEpoch.get()"))
        assertTrue(body.contains("return@synchronized currentEpoch"))
        assertTrue(body.contains("requestedPurpose = purpose"))
    }

    @Test
    fun `unpersisted epoch is abandoned only after conservative checks`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "abandonUnpersistedRequestIfCurrent")

        assertTrue(body.contains("clearRequestEpoch.get() != expectedEpoch"))
        assertTrue(body.contains("clearedRequestEpoch.get() >= expectedEpoch"))
        assertTrue(body.contains("isPersistedFenceActive(context)"))
        assertTrue(
            body.contains("PersistentDownloadedSongDeleteIntentStore.hasPending(context)")
        )
        assertTrue(body.contains("clearedRequestEpoch.accumulateAndGet"))
    }

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = Regex(
            "(?:private|internal|public)?\\s*(?:suspend\\s+)?fun\\s+$methodName\\b"
        ).find(source)?.range?.first
            ?: error("method not found: $methodName")
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
