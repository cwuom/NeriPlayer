package moe.ouom.neriplayer.core.download

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
                cachedAudio = null
            )
        )
        assertTrue(
            shouldTrustDirectPresentDownloadedSongReference(
                reference = reference,
                evidence = ManagedDownloadReferenceLookup.Result.Present,
                snapshot = staleSnapshot,
                cachedAudio = audio
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
        val readIndex = body.indexOf("_downloadedSongs.value")
        val publishIndex = body.indexOf("publishDownloadedSongs(")

        assertTrue(lockIndex >= 0)
        assertTrue(readIndex > lockIndex)
        assertTrue(publishIndex > readIndex)
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

    private fun methodBody(source: String, methodName: String): String {
        val signatureStart = Regex(
            "(?:private|internal|public)?\\s*(?:suspend\\s+)?fun\\s+$methodName\\b"
        ).find(source)?.range?.first
            ?: error("method not found: $methodName")
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
