package moe.ouom.neriplayer.core.download.storage.queue

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadRecoveryRoomStoreRuntimeCharacterizationTest {
    @Test
    fun runtime_facade_methods_do_not_import_legacy_files() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/queue/DownloadRecoveryRoomStore.kt"
        ).readText()

        listOf(
            "upsertPendingDownloadQueue",
            "listPendingQueuedDownloads",
            "removePendingDownloadQueueEntries",
            "clearPendingDownloadQueue"
        ).forEach { methodName ->
            val body = methodBody(source, methodName)
            assertFalse(
                "$methodName must use the Room operation journal directly",
                body.contains("importQueueIfNeeded()") ||
                    body.contains("importCancelledIfNeeded()") ||
                    body.contains("markPrimary(") ||
                    body.contains("ManagedDownloadQueueStore") ||
                    body.contains("filesDir")
            )
        }
    }

    @Test
    fun recovery_facade_does_not_expose_legacy_cancelled_key_api() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadRecoveryFiles.kt"
        ).readText()

        listOf(
            "markCancelledDownloadKeys",
            "listCancelledDownloadKeys",
            "removeCancelledDownloadKeys",
            "discardCancelledDownloadKeys",
            "clearCancelledDownloadKeys"
        ).forEach { methodName ->
            assertFalse(
                "recovery facade must not reintroduce stable-key cancellation state",
                source.contains("fun $methodName(")
            )
        }
    }

    @Test
    fun production_recovery_has_no_legacy_file_mutation_api() {
        listOf(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/queue/ManagedDownloadQueueStore.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadRecoveryFiles.kt",
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).forEach { path ->
            val source = locateProjectFile(path).readText()
            assertFalse(
                "$path must not expose legacy queue file writers",
                source.contains("upsertPendingDownloadQueueInFile") ||
                    source.contains("removePendingDownloadQueueEntriesFromFile") ||
                    source.contains("clearPendingDownloadQueueFile") ||
                    source.contains("markCancelledDownloadKeysInFile") ||
                    source.contains("removeCancelledDownloadKeysFromFile") ||
                    source.contains("clearCancelledDownloadKeysFile")
            )
        }
    }

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = source.indexOf("suspend fun $methodName")
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
        var attempts = 0
        while (attempts++ < 5) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: break
        }
        error("source file not found: $path")
    }
}
