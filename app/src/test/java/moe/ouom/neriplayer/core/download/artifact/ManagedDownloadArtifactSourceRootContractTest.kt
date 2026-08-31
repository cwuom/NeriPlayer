package moe.ouom.neriplayer.core.download.artifact

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadArtifactSourceRootContractTest {
    @Test
    fun `core commit accepts the durable source root identity`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/artifact/" +
                "ManagedDownloadArtifactCoordinator.kt"
        )
        val body = source.substringAfter(
            "suspend fun markCoreCommitted("
        ).substringBefore("suspend fun markAssetsEnriching(")

        assertTrue(body.contains("rootKeyOverride: String? = null"))
        assertTrue(body.contains("rootKeyOverride"))
        assertTrue(body.contains("currentSnapshotRootKey"))
    }

    @Test
    fun `pending recovery derives source identity before updating artifact`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/" +
                "GlobalDownloadManager.kt"
        )
        val body = source.substringAfter(
            "private suspend fun recoverPendingAudioWritesFromRoot("
        ).substringBefore("private suspend fun recoverUnfinalizedPublishedAudioFromRoot(")
        val commitIndex = body.indexOf(
            "managedDownloadArtifactCoordinator.markCoreCommitted("
        )

        assertTrue(body.contains("snapshotRootKeyForOperation("))
        assertTrue(commitIndex >= 0)
        assertTrue(
            body.substring(commitIndex).contains(
                "rootKeyOverride = sourceArtifactRootKey"
            )
        )
        assertTrue(
            body.contains(
                "sourceArtifactRootKey == null"
            )
        )
    }

    @Test
    fun `source identity uses the requested directory instead of current settings`() {
        val source = readSource(
            "app/src/main/java/moe/ouom/neriplayer/core/download/" +
                "ManagedDownloadStorage.kt"
        )
        val body = source.substringAfter(
            "internal suspend fun snapshotRootKeyForOperation("
        ).substringBefore("suspend fun readText(")

        assertTrue(body.contains("directoryUri = directoryUri"))
        assertTrue(body.contains("useDefaultRootWhenDirectoryUriMissing"))
        assertTrue(body.contains("rootKeyForResolvedRoot(root)"))
    }

    private fun readSource(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $relativePath")
    }
}
