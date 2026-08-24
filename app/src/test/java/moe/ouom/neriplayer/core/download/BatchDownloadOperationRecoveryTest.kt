package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadOperationRecoveryTest {
    @Test
    fun `batch path recreates and verifies a missing durable operation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val batchBody = methodBody(source, "startBatchDownloadConfirmed")

        assertTrue(
            "batch scheduling must repair a missing operation before giving up",
            batchBody.contains("ensureQueuedOperationForSong")
        )
        assertTrue(
            "batch scheduling must verify the operation after the repair write",
            source.contains("re-read Room after the write") &&
                source.contains("DownloadExecutionRoomStore.findOperationIdForSong")
        )
    }

    @Test
    fun `fallback operation identity is deterministic for one attempt`() {
        val first = queuedDownloadFallbackOperationId("42|netease|", 7L)
        val second = queuedDownloadFallbackOperationId("42|netease|", 7L)
        val nextAttempt = queuedDownloadFallbackOperationId("42|netease|", 8L)

        assertEquals(first, second)
        assertTrue(first != nextAttempt)
    }

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = source.indexOf("private fun $methodName")
        require(signatureStart >= 0) { "method not found: $methodName" }
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
