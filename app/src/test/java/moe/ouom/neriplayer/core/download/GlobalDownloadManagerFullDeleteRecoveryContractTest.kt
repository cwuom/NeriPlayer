package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDownloadManagerFullDeleteRecoveryContractTest {
    @Test
    fun `full delete writes intent before hiding catalog and clears after durable catalog`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val beginBody = source.substringAfter(
            "private fun beginDownloadedSongDeleteSession"
        ).substringBefore("private fun downloadedSongDeletionKeys")
        val deleteBody = source.substringAfter(
            "private suspend fun deleteDownloadedSongsOnIo"
        ).substringBefore("/**\n     * 旧目录路径")

        val intentIndex = beginBody.indexOf("PersistentDownloadedSongDeleteIntentStore.begin(")
        val publishIndex = beginBody.indexOf("publishDownloadedSongs(")
        assertTrue(intentIndex >= 0)
        assertTrue(publishIndex > intentIndex)
        assertTrue(deleteBody.contains("persistDownloadedSongsCatalog("))
        assertTrue(
            deleteBody.indexOf("PersistentDownloadedSongDeleteIntentStore.clear(") >
                deleteBody.indexOf("persistDownloadedSongsCatalog(")
        )
    }

    @Test
    fun `startup reactivates and replays a pending full delete even after fence release`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializeBody = source.substringAfter("fun initialize(context: Context)")
            .substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")
        val pendingIntentIndex = initializeBody.indexOf(
            "PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)"
        )
        val fenceCheckIndex = initializeBody.indexOf(
            "if (PersistentDownloadClearFenceStore.isActive(appContext))"
        )
        val replayIndex = initializeBody.indexOf(
            "scheduleDeferredFullLibraryDeleteRecovery(appContext)"
        )

        assertTrue(pendingIntentIndex >= 0)
        assertTrue(fenceCheckIndex > pendingIntentIndex)
        assertTrue(replayIndex > fenceCheckIndex)
        assertTrue(source.contains("private fun scheduleDeferredFullLibraryDeleteRecovery"))
        assertTrue(source.contains("ManagedDownloadStorage.currentSnapshotCacheKey(appContext)"))
    }

    @Test
    fun `full delete does not treat its intent as an already active fence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val cancellationBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        assertTrue(cancellationBody.contains("hasPersistedFence(appContext)"))
        assertTrue(
            source.contains("private suspend fun replayFullLibraryDeleteWithoutCatalog")
        )
        assertTrue(
            source.substringAfter("private suspend fun replayFullLibraryDeleteWithoutCatalog")
                .substringBefore("private suspend fun activateDownloadClearFence")
                .contains("buildFullLibraryDeletePlan(appContext)")
        )
    }

    @Test
    fun `full delete uses managed snapshot fallback and keeps intent for residual references`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val deleteBody = source.substringAfter(
            "private suspend fun deleteDownloadedSongsOnIo"
        ).substringBefore("/**\n     * 旧目录路径")

        assertTrue(deleteBody.contains("buildFullLibraryDeletePlan(appContext)"))
        val remainingIndex = deleteBody.indexOf(
            "val remainingReferences = requestedReferences - deletedReferences"
        )
        val clearIndex = deleteBody.indexOf(
            "PersistentDownloadedSongDeleteIntentStore.clear(appContext)"
        )
        assertTrue(remainingIndex >= 0)
        assertTrue(clearIndex > remainingIndex)
        assertTrue(deleteBody.contains("remainingReferences.isEmpty()"))
    }

    @Test
    fun `full delete deferred and failed paths schedule durable recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val recoveryBody = source.substringAfter(
            "private fun scheduleFullLibraryDeleteRecoveryIfNeeded"
        ).substringBefore("private fun restoreDeferredDownloadedSongDeleteSession")
        val asyncDeleteBody = source.substringAfter(
            "fun deleteDownloadedSongs(context: Context, songs: List<DownloadedSong>)"
        ).substringBefore("suspend fun deleteDownloadedSongsWithResult")
        val resultDeleteBody = source.substringAfter(
            "suspend fun deleteDownloadedSongsWithResult("
        ).substringBefore("private fun scheduleFullLibraryDeleteRecoveryIfNeeded")

        assertTrue(recoveryBody.contains("session.fullLibraryDelete"))
        assertTrue(recoveryBody.contains("session.deleteIntentDurable"))
        assertTrue(recoveryBody.contains("scheduleDeferredFullLibraryDeleteRecovery(context)"))
        assertTrue(asyncDeleteBody.contains("scheduleFullLibraryDeleteRecoveryIfNeeded("))
        assertTrue(resultDeleteBody.contains("scheduleFullLibraryDeleteRecoveryIfNeeded("))
        assertTrue(resultDeleteBody.contains("deleteLease.close()"))
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
