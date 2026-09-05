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
        val mutationPermit = source.substringAfter(
            "private fun <T> withNetworkPolicyMutationPermit("
        ).substringBefore("private fun deleteWorkingFileUnlessNetworkPolicyPaused")
        val coverCommit = source.substringAfter("commitCover = {")
            .substringBefore("rememberPartial = {")
        val lyricWrite = source.substringAfter("writeSidecar = {")
            .substringBefore("rememberPartial = {")

        assertTrue(mutationPermit.contains("requireActiveAttempt: Boolean = true"))
        assertTrue(mutationPermit.contains("requireActiveAttempt = requireActiveAttempt"))
        assertTrue(coverCommit.contains("requireActiveAttempt = active"))
        assertTrue(lyricWrite.contains("requireActiveAttempt = active"))
        assertTrue(source.contains("requireActiveAttempt = active,"))
        val trackedCall = source.substringAfter(
            "private inline fun <T> executeTrackedCall("
        ).substringBefore("internal fun consumeCompletedAudioReference")
        assertTrue(trackedCall.contains("requireActiveAttempt: Boolean = true"))
        assertTrue(trackedCall.contains("(_isCancelled.value && requireActiveAttempt) ||"))
        val cancellationGuard = source.substringAfter(
            "private fun ensureSongDownloadNotCancelled"
        ).substringBefore("private fun isDownloadClearFenceBlockingWork")
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
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
