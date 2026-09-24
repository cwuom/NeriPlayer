package moe.ouom.neriplayer.core.player.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadManagerPostCoreEnrichmentContractTest {
    @Test
    fun `post core sidecar writes keep the relaxed attempt requirement`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/player/download/AudioDownloadManager.kt"
        ).readText()
        val mutationPermit = methodBody(source, "withNetworkPolicyMutationPermit")
        val coverCommit = source.substringAfter("commitCover = {")
            .substringBefore("rememberPartial = {")
        val lyricWrite = source.substringAfter("writeSidecar = {")
            .substringBefore("rememberPartial = {")

        assertTrue(source.contains("requireActiveAttempt: Boolean = true"))
        assertTrue(mutationPermit.contains("requireActiveAttempt = requireActiveAttempt"))
        assertTrue(coverCommit.contains("requireActiveAttempt = active"))
        assertTrue(lyricWrite.contains("requireActiveAttempt = active"))
        assertTrue(source.contains("requireActiveAttempt = active,"))
        val trackedCall = methodBody(source, "executeTrackedCall")
        assertTrue(source.contains("internal inline fun <T> AudioDownloadManager.executeTrackedCall("))
        assertTrue(source.contains("requireActiveAttempt: Boolean = true"))
        assertTrue(trackedCall.contains("(_isCancelled.value && requireActiveAttempt) ||"))
        val cancellationGuard = methodBody(source, "ensureSongDownloadNotCancelled")
        assertTrue(
            cancellationGuard.contains(
                "allDownloadsCancelled = _isCancelled.value && requireActiveAttempt"
            )
        )
        assertTrue(cancellationGuard.contains("clearFenceAllowsWork"))
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate)
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source = source,
            methodName = methodName
        )
}
