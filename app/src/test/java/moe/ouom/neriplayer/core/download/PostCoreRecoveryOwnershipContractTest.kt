package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCoreRecoveryOwnershipContractTest {
    @Test
    fun `post core recovery rechecks active enrichment after taking the song lock`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/manager/runtime/" +
                "GlobalDownloadManagerRequest.kt"
        ).readText()
        val body = source.substringAfter(
            "internal suspend fun GlobalDownloadManager.recoverPostCoreDownloadOperation("
        ).substringBefore(
            "internal fun GlobalDownloadManager.settleAndRemoveRecoveredTask("
        )
        val lockIndex = body.indexOf("withSongExecutionLock(song.stableKey())")
        val activeOwnerIndex = body.indexOf("assetEnrichmentCoordinator.isActive(operationId)")
        val artifactClaimIndex = body.indexOf("claimArtifactForRecovery(")

        assertTrue(lockIndex >= 0)
        assertTrue(activeOwnerIndex > lockIndex)
        assertTrue(artifactClaimIndex > activeOwnerIndex)
    }

    @Test
    fun `post core worker uses the shared durable recovery lease claim`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/manager/runtime/" +
                "GlobalDownloadManagerRequest.kt"
        ).readText()
        val body = source.substringAfter(
            "internal suspend fun GlobalDownloadManager.recoverPostCoreDownloadOperation("
        ).substringBefore(
            "internal fun GlobalDownloadManager.settleAndRemoveRecoveredTask("
        )

        assertTrue(body.contains("claimArtifactForRecovery("))
        assertTrue(!body.contains("managedDownloadArtifactCoordinator.claim("))
    }

    @Test
    fun `manual retry reaches the bounded post core overflow slot`() {
        val requestSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/manager/runtime/" +
                "GlobalDownloadManagerRequest.kt"
        ).readText()
        val finalizationSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/manager/commit/" +
                "GlobalDownloadManagerFinalization.kt"
        ).readText()
        val coreCommitSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/manager/commit/" +
                "GlobalDownloadManagerCoreCommit.kt"
        ).readText()
        val resumeSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/manager/facade/" +
                "GlobalDownloadManagerFacadeRuntime.kt"
        ).readText()

        assertTrue(resumeSource.contains("manualRetry = true"))
        assertTrue(requestSource.contains("expeditedAssetEnrichment = true"))
        assertTrue(finalizationSource.contains("expeditedAssetEnrichment = expeditedAssetEnrichment"))
        assertTrue(coreCommitSource.contains("allowSingleOverflow = expeditedAssetEnrichment"))
    }

    private fun locateProjectFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, path)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
