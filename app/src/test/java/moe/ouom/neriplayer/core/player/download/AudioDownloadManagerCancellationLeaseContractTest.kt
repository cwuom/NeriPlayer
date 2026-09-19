package moe.ouom.neriplayer.core.player.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadManagerCancellationLeaseContractTest {

    @Test
    fun `cancelled download routes pending cleanup through one guarded helper`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val executionBody = methodBody(source, "executeDownloadSong")
        val attemptFailureBody = methodBody(source, "handleDownloadAttemptFailure")
        val cancellationBody = methodBody(source, "handleDownloadSongCancellation")

        assertFalse(
            executionBody.contains(
                "ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts("
            )
        )
        assertEquals(
            2,
            (attemptFailureBody + cancellationBody).windowed(
                "cleanupCancelledPendingArtifactsWithLease(".length,
                1
            ).count { window ->
                window == "cleanupCancelledPendingArtifactsWithLease("
            }
        )
        assertTrue(source.contains("cancellationCleanupAttempted: Boolean = false"))
        assertTrue(cancellationBody.contains("if (!state.cancellationCleanupAttempted)"))
    }

    @Test
    fun `durable clear fence owns cancelled artifact convergence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val attemptFailureBody = methodBody(source, "handleDownloadAttemptFailure")
        val cancellationBody = methodBody(source, "handleDownloadSongCancellation")
        val ownershipBody = methodBody(source, "isCancellationCleanupOwnedByClearFence")

        listOf(attemptFailureBody, cancellationBody).forEach { body ->
            assertTrue(body.contains("isCancellationCleanupOwnedByClearFence("))
            assertTrue(body.contains("!clearFenceOwnsCancellationCleanup"))
            assertTrue(
                body.contains(
                    "if (!preserveCancellationArtifacts && " +
                        "!clearFenceOwnsCancellationCleanup)"
                )
            )
        }
        assertTrue(ownershipBody.contains("!preserveCancellationArtifacts"))
        assertTrue(ownershipBody.contains("isDownloadClearFenceBlockingWork("))
    }

    @Test
    fun `pending cleanup holds a non cancellable delete lease`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val helperBody = methodBody(source, "cleanupCancelledPendingArtifactsWithLease")
        val leaseIndex = helperBody.indexOf("acquireDeleteLeaseOrNull(appContext)")
        val cleanupIndex = helperBody.indexOf(
            "ManagedDownloadStorage.cleanupCancelledPendingDownloadArtifacts("
        )
        val closeIndex = helperBody.indexOf("deleteLease.close()")

        assertTrue(
            source.contains(
                "internal suspend fun cleanupCancelledPendingArtifactsWithLease("
            )
        )
        assertTrue(
            source.contains(
                "): ManagedDownloadStorage.StartupRecoveryResult = withContext(NonCancellable)"
            )
        )
        assertTrue(leaseIndex >= 0)
        assertTrue(cleanupIndex > leaseIndex)
        assertTrue(closeIndex > cleanupIndex)
        assertTrue(helperBody.contains("finally"))
        assertTrue(helperBody.contains("failedCount = 1"))
    }

    @Test
    fun `provider and cancellation failures keep recovery evidence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val helperBody = methodBody(source, "cleanupCancelledPendingArtifactsWithLease")
        val cancellationIndex = helperBody.indexOf(
            "catch (cancellation: CancellationException)"
        )
        val exceptionIndex = helperBody.indexOf("catch (error: Exception)")

        assertTrue(cancellationIndex >= 0)
        assertTrue(exceptionIndex > cancellationIndex)
        assertTrue(
            helperBody.substring(cancellationIndex, exceptionIndex)
                .contains("throw cancellation")
        )
        assertTrue(helperBody.contains("保留恢复凭据"))
        assertFalse(helperBody.contains("runCatching"))
    }

    @Test
    fun `expected lease contention is sampled while provider failures stay visible`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/" +
                "AudioDownloadManager.kt"
        ).readText()
        val helperBody = methodBody(source, "cleanupCancelledPendingArtifactsWithLease")
        val contentionIndex = helperBody.indexOf(
            "目录迁移或其他目录变更进行中，延后取消下载 pending 清理"
        )
        val providerCatchIndex = helperBody.lastIndexOf("catch (error: Exception)")
        assertTrue(contentionIndex >= 0)
        assertTrue(providerCatchIndex > contentionIndex)
        assertTrue(
            helperBody.substring(0, contentionIndex)
                .substringAfterLast("NPLogger.")
                .startsWith("d(")
        )
        assertTrue(
            helperBody.substring(providerCatchIndex)
                .contains("NPLogger.w(")
        )
        assertTrue(
            helperBody.substring(providerCatchIndex)
                .contains("error.javaClass.simpleName")
        )
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
