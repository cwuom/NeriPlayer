package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.catalog.isLatestDownloadedPlaybackRequest
import moe.ouom.neriplayer.core.download.manager.catalog.publishDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.catalog.publishScannedDownloadedSongsIfCurrent
import moe.ouom.neriplayer.core.download.manager.catalog.reloadDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.commit.publishFinalizedDownload
import moe.ouom.neriplayer.core.download.manager.runtime.publishCompletedDownloadOptimistically
import moe.ouom.neriplayer.core.download.manager.runtime.publishOptimisticDownloadedSongs
import moe.ouom.neriplayer.core.download.policy.shouldApplyDownloadedPlaybackRequest
import moe.ouom.neriplayer.core.download.policy.shouldTrustDirectPresentDownloadedSongReference
import java.io.File
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定下载 catalog 与异步扫描之间的发布顺序 */
class GlobalDownloadManagerCatalogRaceTest {
    @Test
    fun `formal present audio remains usable while snapshot is incomplete`() {
        val reference = "content://provider/downloads/Song.flac"
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "Song.flac",
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
        val staleSnapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            rootEntriesComplete = false,
            audioEntries = listOf(audio),
            audioEntriesByLookupKey = mapOf(reference to audio),
            metadataByAudioName = mapOf(
                audio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    downloadFinalized = false,
                    artifactState = "REPAIR_REQUIRED"
                )
            )
        )

        assertTrue(
            shouldTrustDirectPresentDownloadedSongReference(
                reference = reference,
                evidence = ManagedDownloadReferenceLookup.Result.Present,
                snapshot = null,
                cachedAudio = null,
                recordedSizeBytes = 1L,
                observedSizeBytes = 1L
            )
        )
        assertTrue(
            shouldTrustDirectPresentDownloadedSongReference(
                reference = reference,
                evidence = ManagedDownloadReferenceLookup.Result.Present,
                snapshot = staleSnapshot,
                cachedAudio = audio,
                recordedSizeBytes = 1L,
                observedSizeBytes = 1L
            )
        )
        assertFalse(
            shouldTrustDirectPresentDownloadedSongReference(
                reference = "$reference.npdl_pending",
                evidence = ManagedDownloadReferenceLookup.Result.Present,
                snapshot = null,
                cachedAudio = null
            )
        )
        assertFalse(
            shouldTrustDirectPresentDownloadedSongReference(
                reference = reference,
                evidence = ManagedDownloadReferenceLookup.Result.Missing,
                snapshot = staleSnapshot,
                cachedAudio = audio
            )
        )
    }

    @Test
    fun `optimistic catalog merge reads and publishes under one mutation lock`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "publishOptimisticDownloadedSongs")
        val lockIndex = body.indexOf("synchronized(downloadedSongCatalogMutationLock)")
        val readIndex = body.indexOf("downloadedSongsMutable.value")
        val publishIndex = body.indexOf("publishDownloadedSongs(")

        assertTrue(lockIndex >= 0)
        assertTrue(readIndex > lockIndex)
        assertTrue(publishIndex > readIndex)
    }

    @Test
    fun `optimistic catalog publication uses a bounded delta instead of a full rebuild`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "publishOptimisticDownloadedSongs")

        assertTrue(body.contains("CatalogPublishMode.DELTA"))
        assertTrue(body.contains("publishDownloadedSongs("))
        assertTrue(!body.contains("scheduleCatalogReconcile"))
    }

    @Test
    fun `finalized publication does not trigger a per-song full catalog reconcile`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "publishFinalizedDownload")

        assertTrue(body.contains("publishCompletedDownloadOptimistically"))
        assertTrue(!body.contains("scheduleCatalogReconcile(context, forceRefresh = false)"))
    }

    @Test
    fun `finalized publication reconciles a stale pending reference before promotion`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "publishFinalizedDownload")
        val reconcileIndex = body.indexOf("corePublicationCoordinator.promoteBeforePublication(")
        val promotionIndex = body.indexOf("ManagedDownloadStorage.promoteFinalizedPendingAudio(")

        assertTrue(reconcileIndex >= 0)
        assertTrue(promotionIndex > reconcileIndex)
        assertTrue(body.contains("pendingAudio = storedAudio"))
    }

    @Test
    fun `scanned catalog replacement rejects concurrent catalog or metadata mutations`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val reloadBody = methodBody(source, "reloadDownloadedSongs")
        val helperBody = methodBody(source, "publishScannedDownloadedSongsIfCurrent")

        assertTrue(reloadBody.contains("catalogRevisionAtScanStart"))
        assertTrue(reloadBody.contains("publishScannedDownloadedSongsIfCurrent("))
        assertTrue(helperBody.contains("synchronized(downloadedSongCatalogMutationLock)"))
        assertTrue(helperBody.contains("expectedCatalogRevision"))
        assertTrue(helperBody.contains("expectedMetadataRevision"))
        assertTrue(helperBody.contains("publishDownloadedSongs(context, songs"))
    }

    @Test
    fun `downloaded playback request accepts only the latest generation`() {
        assertTrue(shouldApplyDownloadedPlaybackRequest(7L, 7L))
        assertFalse(shouldApplyDownloadedPlaybackRequest(6L, 7L))
        assertFalse(shouldApplyDownloadedPlaybackRequest(8L, 7L))
    }

    @Test
    fun `downloaded playback cancels and guards an older asynchronous request`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/" +
                "GlobalDownloadManager.kt"
        ).readText()
        val body = methodBody(source, "playDownloadedSong")

        assertTrue(body.contains("downloadedPlaybackRequestGeneration.incrementAndGet()"))
        assertTrue(body.contains("downloadedPlaybackJob?.cancel()"))
        assertTrue(body.contains("isLatestDownloadedPlaybackRequest(requestGeneration)"))
        assertTrue(body.contains("Dispatchers.Main.immediate"))
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
