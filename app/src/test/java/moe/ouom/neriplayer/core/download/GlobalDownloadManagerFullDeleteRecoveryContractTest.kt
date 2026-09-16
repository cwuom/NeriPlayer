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
        val beginBody = methodBody(source, "beginDownloadedSongDeleteSession")
        val deleteBody = methodBody(source, "deleteDownloadedSongsOnIo")

        val intentIndex = beginBody.indexOf("PersistentDownloadedSongDeleteIntentStore.begin(")
        val publishIndex = beginBody.indexOf("publishDownloadedSongs(")
        assertTrue(intentIndex >= 0)
        assertTrue(publishIndex > intentIndex)
        assertTrue(deleteBody.contains("persistConfirmedEmptyDownloadedSongsCatalog("))
        assertTrue(
            deleteBody.indexOf("PersistentDownloadedSongDeleteIntentStore.clear(") >
                deleteBody.indexOf("persistConfirmedEmptyDownloadedSongsCatalog(")
        )
        assertTrue(deleteBody.contains("deleteAllAfterCancellationSettled("))
        assertTrue(deleteBody.contains("clearFastIndexForConfirmedEmptyLibrary("))
    }

    @Test
    fun `startup reactivates and replays a pending full delete even after fence release`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializeBody = methodBody(source, "initialize")
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
        assertTrue(
            source.contains(
                "internal fun GlobalDownloadManager.scheduleDeferredFullLibraryDeleteRecovery"
            )
        )
        assertTrue(source.contains("ManagedDownloadStorage.currentSnapshotCacheKey(appContext)"))
    }

    @Test
    fun `full delete does not treat its intent as an already active fence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val cancellationBody = methodBody(source, "requestAllDownloadTaskCancellation")
        val replayBody = methodBody(source, "replayFullLibraryDeleteWithoutCatalog")
        assertTrue(
            cancellationBody.contains(
                "val hadPersistedClearFence = PersistentDownloadClearFenceStore.hasPersistedFence("
            )
        )
        assertTrue(cancellationBody.contains("if (hadPersistedClearFence && !forceConvergence)"))
        assertTrue(
            source.contains(
                "internal suspend fun GlobalDownloadManager.replayFullLibraryDeleteWithoutCatalog"
            )
        )
        assertTrue(replayBody.contains("buildFullLibraryDeletePlan(appContext)"))
        assertTrue(source.contains("isFullLibraryDeleteCancellationSettled(appContext)"))
    }

    @Test
    fun `full delete uses managed snapshot fallback and keeps intent for residual references`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val deleteBody = methodBody(source, "deleteDownloadedSongsOnIo")

        assertTrue(deleteBody.contains("buildFullLibraryDeletePlan(appContext)"))
        val remainingIndex = deleteBody.indexOf(
            "val remainingReferences = verifiedRemainingReferences"
        )
        val clearIndex = deleteBody.indexOf(
            "PersistentDownloadedSongDeleteIntentStore.clear(appContext)"
        )
        assertTrue(remainingIndex >= 0)
        assertTrue(clearIndex > remainingIndex)
        assertTrue(deleteBody.contains("remainingReferences.isEmpty()"))
    }

    @Test
    fun `post core enrichment retry is gated by the clear fence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val enrichmentBody = methodBody(source, "enrichCoreCommittedDownload")
        assertTrue(enrichmentBody.contains("val clearBlocked = isDownloadClearFenceActive"))
        assertTrue(enrichmentBody.contains("val canRetry = !clearBlocked"))
        assertTrue(!enrichmentBody.contains("AudioDownloadManager.isAllDownloadsCancelled()"))
        assertTrue(!source.contains("allDownloadsCancelled = AudioDownloadManager.isAllDownloadsCancelled()"))
    }

    @Test
    fun `full delete deferred and failed paths schedule durable recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val recoveryBody = methodBody(source, "scheduleFullLibraryDeleteRecoveryIfNeeded")
        val asyncDeleteBody = methodBody(source, "deleteDownloadedSongs")
        val resultDeleteBody = methodBody(source, "deleteDownloadedSongsWithResult")

        assertTrue(recoveryBody.contains("session.fullLibraryDelete"))
        assertTrue(recoveryBody.contains("session.deleteIntentDurable"))
        assertTrue(recoveryBody.contains("scheduleDeferredFullLibraryDeleteRecovery(context)"))
        assertTrue(asyncDeleteBody.contains("scheduleFullLibraryDeleteRecoveryIfNeeded("))
        assertTrue(resultDeleteBody.contains("scheduleFullLibraryDeleteRecoveryIfNeeded("))
        assertTrue(resultDeleteBody.contains("deleteLease.close()"))
    }

    @Test
    fun `full delete cancellation skips duplicate provider cleanup before snapshot delete`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val cancellationBody = methodBody(source, "requestAllDownloadTaskCancellation")
        val cleanupBody = methodBody(source, "cancelDownloadTasksInBackground")

        assertTrue(
            cancellationBody.contains(
                "skipProviderArtifactCleanup =\n" +
                    "                                purpose == DownloadClearPurpose.FULL_LIBRARY_DELETE"
            )
        )
        assertTrue(
            cleanupBody.contains(
                "if (awaitProviderCleanup && !skipProviderArtifactCleanup)"
            )
        )
        assertTrue(cleanupBody.contains("if (skipProviderArtifactCleanup)"))
        assertTrue(
            cleanupBody.indexOf("if (skipProviderArtifactCleanup)") <
                cleanupBody.lastIndexOf("if (awaitProviderCleanup)")
        )
    }

    @Test
    fun `full delete uses trusted compacted directory deletion`() {
        val storageSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
        ).readText()
        val deleteSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/cleanup/ManagedDownloadDeletePlanner.kt"
        ).readText()
        val catalogSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()

        assertTrue(deleteSource.contains("DOWNLOAD_TEMPORARY_DIR_NAME"))
        assertTrue(deleteSource.contains("COVER_SUBDIRECTORY"))
        assertTrue(deleteSource.contains("LYRIC_SUBDIRECTORY"))
        assertTrue(storageSource.contains("deleteFullLibraryReferences("))
        assertTrue(catalogSource.contains("deleteFullLibraryReferences("))
    }

    @Test
    fun `durable full delete returns after background cleanup is accepted`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val deleteBody = methodBody(source, "deleteDownloadedSongsWithResultImpl")
        val launchIndex = deleteBody.indexOf("launchDurableFullLibraryDeleteSession(")
        val pendingResultIndex = deleteBody.indexOf("physicalCleanupPending = true")

        assertTrue(deleteBody.contains("session.fullLibraryDelete && session.deleteIntentDurable"))
        assertTrue(launchIndex >= 0)
        assertTrue(pendingResultIndex > launchIndex)
        assertTrue(source.contains("FULL_LIBRARY_DELETE_ACK_TARGET_MS = 5_000L"))
        assertTrue(source.contains("overBudget="))
        assertTrue(source.contains("deleteDownloadedSongsOnIo(context, session)"))
        assertTrue(source.contains("scheduleFullLibraryDeleteRecoveryIfNeeded("))
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
