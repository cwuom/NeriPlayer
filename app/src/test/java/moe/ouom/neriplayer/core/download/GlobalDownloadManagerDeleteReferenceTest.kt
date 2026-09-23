package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteResult
import moe.ouom.neriplayer.core.download.model.isCompleteDownloadedSongSelection
import moe.ouom.neriplayer.core.download.model.mergeDownloadedSongsAfterDelete
import moe.ouom.neriplayer.core.download.model.resolveConfirmedFullLibraryDeleteResult
import moe.ouom.neriplayer.core.download.model.resolveDownloadedSongDeleteResult
import moe.ouom.neriplayer.core.download.model.resolveFullLibraryRemainingReferences
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.core.download.policy.shouldDeleteEntireDownloadedLibrary
import moe.ouom.neriplayer.core.download.cleanup.requiresManagedDownloadDeleteSnapshotRefresh
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDownloadManagerDeleteReferenceTest {

    @Test
    fun `full delete does not infer ownership from enumerated foreign sidecars`() {
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            knownReferences = setOf("/library/Covers/foreign.jpg", "/library/Lyrics/foreign.lrc")
        )
        assertTrue(ManagedDownloadArtifactPlanner.collectFullLibraryArtifactReferences(snapshot).isEmpty())
    }

    @Test
    fun `metadata delete reference must already exist in trusted snapshot`() {
        val trustedReference =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg"
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            knownReferences = setOf(trustedReference)
        )

        assertEquals(
            trustedReference,
            GlobalDownloadManager.trustedManagedMetadataReference(trustedReference, snapshot)
        )
        assertNull(
            GlobalDownloadManager.trustedManagedMetadataReference(
                "/tmp/outside/song.jpg",
                snapshot
            )
        )
        assertNull(
            GlobalDownloadManager.trustedManagedMetadataReference(
                "content://com.example.documents/tree/primary%3AMusic%2FNeriPlayer/document/primary%3AMusic%2FNeriPlayer%2FCovers%2Fsong.jpg",
                snapshot
            )
        )
    }

    @Test
    fun `artifact planner keeps sidecars owned by other downloads`() {
        val sharedCoverReference = "content://downloads/covers/shared.jpg"
        val currentAudio = ManagedDownloadStorage.StoredEntry(
            name = "artist - current.mp3",
            reference = "content://downloads/audio/current.mp3",
            mediaUri = "content://downloads/audio/current.mp3",
            localFilePath = null,
            sizeBytes = 1024L,
            lastModifiedMs = 1L
        )
        val currentMetadataReference = ManagedDownloadStorage.metadataReferenceForAudio(currentAudio)
            ?: error("missing current metadata reference")
        val currentMetadata = ManagedDownloadStorage.StoredEntry(
            name = "${currentAudio.name}.npmeta.json",
            reference = currentMetadataReference,
            mediaUri = currentMetadataReference,
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
        val otherMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            coverPath = sharedCoverReference
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            metadataEntriesByAudioName = mapOf(currentAudio.name to currentMetadata),
            metadataByAudioName = mapOf("artist - other.mp3" to otherMetadata),
            knownReferences = setOf(
                currentAudio.reference,
                currentMetadataReference,
                sharedCoverReference
            )
        )

        val references = moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadArtifactPlanner.collectArtifactReferences(
            snapshot = snapshot,
            storedAudio = currentAudio,
            uniqueAudioReferencesByName = mapOf(currentAudio.logicalName to currentAudio.reference),
            explicitReferences = listOf(sharedCoverReference)
        )

        assertEquals(setOf(currentAudio.reference, currentMetadataReference), references)
    }

    @Test
    fun `artifact planner keeps romanized lyric owned by other download`() {
        val sharedRomanizedReference = "content://downloads/lyrics/shared_roma.lrc"
        val currentAudio = ManagedDownloadStorage.StoredEntry(
            name = "artist - current.mp3",
            reference = "content://downloads/audio/current.mp3",
            mediaUri = "content://downloads/audio/current.mp3",
            localFilePath = null,
            sizeBytes = 1024L,
            lastModifiedMs = 1L
        )
        val currentMetadataReference = ManagedDownloadStorage.metadataReferenceForAudio(currentAudio)
            ?: error("missing current metadata reference")
        val currentMetadata = ManagedDownloadStorage.StoredEntry(
            name = "${currentAudio.name}.npmeta.json",
            reference = currentMetadataReference,
            mediaUri = currentMetadataReference,
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            metadataEntriesByAudioName = mapOf(currentAudio.name to currentMetadata),
            metadataByAudioName = mapOf(
                "artist - other.mp3" to ManagedDownloadStorage.DownloadedAudioMetadata(
                    romanizedLyricPath = sharedRomanizedReference
                )
            ),
            knownReferences = setOf(
                currentAudio.reference,
                currentMetadataReference,
                sharedRomanizedReference
            )
        )

        val references = moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadArtifactPlanner.collectArtifactReferences(
            snapshot = snapshot,
            storedAudio = currentAudio,
            uniqueAudioReferencesByName = mapOf(currentAudio.logicalName to currentAudio.reference),
            explicitReferences = listOf(sharedRomanizedReference)
        )

        assertEquals(setOf(currentAudio.reference, currentMetadataReference), references)
    }

    @Test
    fun `artifact planner deletes stable identity cover sidecar`() {
        val currentAudio = ManagedDownloadStorage.StoredEntry(
            name = "Artist - current.mp3",
            reference = "content://downloads/audio/current.mp3",
            mediaUri = "content://downloads/audio/current.mp3",
            localFilePath = null,
            sizeBytes = 1024L,
            lastModifiedMs = 1L
        )
        val currentMetadataReference = ManagedDownloadStorage.metadataReferenceForAudio(currentAudio)
            ?: error("missing current metadata reference")
        val currentMetadata = ManagedDownloadStorage.StoredEntry(
            name = "${currentAudio.name}.npmeta.json",
            reference = currentMetadataReference,
            mediaUri = currentMetadataReference,
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
        val stableKey = "42|netease|"
        val stableCoverName = ManagedDownloadStorageNaming
            .buildStableCoverCandidateNames(currentAudio.nameWithoutExtension, stableKey)
            .first()
        val stableCover = ManagedDownloadStorage.StoredEntry(
            name = stableCoverName,
            reference = "content://downloads/covers/$stableCoverName",
            mediaUri = "content://downloads/covers/$stableCoverName",
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(currentAudio),
            audioEntriesByLookupKey = mapOf(currentAudio.reference to currentAudio),
            metadataEntriesByAudioName = mapOf(currentAudio.name to currentMetadata),
            metadataByAudioName = mapOf(
                currentAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = stableKey,
                    coverPath = stableCover.reference
                )
            ),
            coverEntriesByName = mapOf(stableCover.name to stableCover),
            knownReferences = setOf(
                currentAudio.reference,
                currentMetadata.reference,
                stableCover.reference
            )
        )

        val references = moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadArtifactPlanner.collectArtifactReferences(
            snapshot = snapshot,
            storedAudio = currentAudio,
            uniqueAudioReferencesByName = mapOf(currentAudio.logicalName to currentAudio.reference)
        )

        assertEquals(
            setOf(currentAudio.reference, currentMetadata.reference, stableCover.reference),
            references
        )
    }

    @Test
    fun `download deletion result retains songs whose required audio was not deleted`() {
        val deletedSong = downloadedSong(id = 1L, name = "deleted")
        val retainedSong = downloadedSong(id = 2L, name = "retained")

        val result = resolveDownloadedSongDeleteResult(
            deletePlans = listOf(
                ManagedDownloadSongDeletePlan(
                    song = deletedSong,
                    requestedReferences = setOf("audio-deleted", "cover-deleted"),
                    requiredReferences = setOf("audio-deleted")
                ),
                ManagedDownloadSongDeletePlan(
                    song = retainedSong,
                    requestedReferences = setOf("audio-retained", "cover-retained"),
                    requiredReferences = setOf("audio-retained")
                )
            ),
            deletedReferences = setOf("audio-deleted", "cover-deleted")
        )

        assertEquals(listOf(deletedSong), result.deletedSongs)
        assertEquals(listOf(retainedSong), result.failedSongs)
    }

    @Test
    fun `complete full library snapshot settles stale catalog rows from physical deletion`() {
        val firstSong = downloadedSong(id = 1L, name = "first")
        val secondSong = downloadedSong(id = 2L, name = "second")
        val stalePerSongResult = DownloadedSongDeleteResult(
            deletedSongs = emptyList(),
            failedSongs = listOf(firstSong, secondSong)
        )

        val result = resolveConfirmedFullLibraryDeleteResult(
            targetSongs = listOf(firstSong, secondSong),
            snapshotComplete = true,
            requestedReferences = setOf("current-audio", "current-metadata"),
            deletedReferences = setOf("current-audio", "current-metadata"),
            fallback = stalePerSongResult
        )

        assertEquals(listOf(firstSong, secondSong), result.deletedSongs)
        assertTrue(result.failedSongs.isEmpty())
    }

    @Test
    fun `complete physical rescan settles rows when another idempotent delete consumed references`() {
        val song = downloadedSong(id = 7L, name = "already gone")
        val fallback = DownloadedSongDeleteResult(
            deletedSongs = emptyList(),
            failedSongs = listOf(song)
        )

        val result = resolveConfirmedFullLibraryDeleteResult(
            targetSongs = listOf(song),
            snapshotComplete = true,
            requestedReferences = setOf("stale-audio", "stale-meta"),
            deletedReferences = emptySet(),
            remainingReferences = emptySet(),
            fallback = fallback
        )

        assertEquals(listOf(song), result.deletedSongs)
        assertTrue(result.failedSongs.isEmpty())
    }

    @Test
    fun `full delete ignores stale references already removed in the first pass`() {
        assertTrue(
            resolveFullLibraryRemainingReferences(
                verificationReferences = setOf("stale-audio", "still-present"),
                deletedReferences = setOf("stale-audio"),
                residualDeletedReferences = emptySet()
            ) == setOf("still-present")
        )
    }

    @Test
    fun `incomplete full library snapshot retains stale catalog rows for recovery`() {
        val song = downloadedSong(id = 1L, name = "retained")
        val fallback = DownloadedSongDeleteResult(
            deletedSongs = emptyList(),
            failedSongs = listOf(song)
        )

        val result = resolveConfirmedFullLibraryDeleteResult(
            targetSongs = listOf(song),
            snapshotComplete = false,
            requestedReferences = emptySet(),
            deletedReferences = emptySet(),
            fallback = fallback
        )

        assertEquals(fallback, result)
    }

    @Test
    fun `incomplete full delete never restores audio already physically deleted`() {
        val deleted = downloadedSong(id = 1L, name = "deleted")
        val retained = downloadedSong(id = 2L, name = "retained")
        val result = resolveConfirmedFullLibraryDeleteResult(
            targetSongs = listOf(deleted, retained),
            snapshotComplete = false,
            requestedReferences = setOf(deleted.filePath, retained.filePath),
            deletedReferences = setOf(deleted.filePath),
            fallback = DownloadedSongDeleteResult(emptyList(), listOf(deleted, retained))
        )

        assertEquals(listOf(deleted), result.deletedSongs)
        assertEquals(listOf(retained), result.failedSongs)
    }

    @Test
    fun `partial full delete matches complete document identity instead of name`() {
        val deleted = downloadedSong(id = 1L, name = "same").copy(
            mediaUri = "content://provider/document/opaque%2Ffirst"
        )
        val retained = downloadedSong(id = 2L, name = "same").copy(
            mediaUri = "content://provider/document/opaque%2Fsecond"
        )
        val deletedReference = "content://provider/tree/root/document/opaque%2Ffirst"
        val result = resolveConfirmedFullLibraryDeleteResult(
            targetSongs = listOf(deleted, retained),
            snapshotComplete = false,
            requestedReferences = setOf(deletedReference),
            deletedReferences = setOf(deletedReference),
            fallback = DownloadedSongDeleteResult(emptyList(), listOf(deleted, retained))
        )

        assertEquals(listOf(deleted), result.deletedSongs)
        assertEquals(listOf(retained), result.failedSongs)
    }

    @Test
    fun `missing audio remains deleted when complete enumeration leaves a failed sidecar`() {
        val missing = downloadedSong(id = 1L, name = "missing").copy(
            stableKey = "same-key", mediaUri = "content://provider/document/opaque%2Ffirst"
        )
        val retained = missing.copy(id = 2L, mediaUri = "content://provider/document/opaque%2Fsecond")
        val result = resolveConfirmedFullLibraryDeleteResult(
            targetSongs = listOf(missing, retained),
            snapshotComplete = true,
            requestedReferences = setOf("content://provider/document/receipt"),
            deletedReferences = emptySet(),
            remainingReferences = setOf("content://provider/document/receipt"),
            confirmedMissingAudioReferences = setOf("content://provider/tree/root/document/opaque%2Ffirst"),
            fallback = DownloadedSongDeleteResult(emptyList(), listOf(missing, retained))
        )
        assertEquals(listOf(missing), result.deletedSongs)
        assertEquals(listOf(retained), result.failedSongs)
    }

    @Test
    fun `deletion result merge keeps concurrent downloads and restores failed entries`() {
        val deletedSong = downloadedSong(id = 1L, name = "deleted", downloadTime = 1L)
        val failedSong = downloadedSong(id = 2L, name = "failed", downloadTime = 2L)
        val concurrentSong = downloadedSong(id = 3L, name = "concurrent", downloadTime = 3L)

        val merged = mergeDownloadedSongsAfterDelete(
            currentSongs = listOf(concurrentSong),
            previousSongs = listOf(deletedSong, failedSong),
            deletedSongs = listOf(deletedSong),
            restoredSongs = listOf(failedSong)
        )

        assertEquals(listOf(concurrentSong, failedSong), merged)
    }

    @Test
    fun `complete catalog selection is required before active downloads are cancelled`() {
        val firstSong = downloadedSong(id = 1L, name = "first")
        val secondSong = downloadedSong(id = 2L, name = "second")
        val availableSongs = listOf(firstSong, secondSong)

        assertTrue(
            isCompleteDownloadedSongSelection(
                selectedSongs = availableSongs,
                availableSongs = availableSongs
            )
        )
        assertFalse(
            isCompleteDownloadedSongSelection(
                selectedSongs = listOf(firstSong),
                availableSongs = availableSongs
            )
        )
        assertFalse(
            isCompleteDownloadedSongSelection(
                selectedSongs = listOf(firstSong, downloadedSong(id = 3L, name = "stale")),
                availableSongs = availableSongs
            )
        )
    }

    @Test
    fun `explicit select all keeps full library intent when catalog preview is incomplete`() {
        assertTrue(
            shouldDeleteEntireDownloadedLibrary(
                explicitlyRequested = true,
                pendingDeleteIntentExists = false
            )
        )
        assertFalse(
            shouldDeleteEntireDownloadedLibrary(
                explicitlyRequested = false,
                pendingDeleteIntentExists = false
            )
        )
        assertTrue(
            shouldDeleteEntireDownloadedLibrary(
                explicitlyRequested = false,
                pendingDeleteIntentExists = true
            )
        )
    }

    @Test
    fun `downloaded song creates a playback item from its managed media reference`() {
        val downloaded = downloadedSong(id = 42L, name = "managed").copy(
            filePath = "content://downloads/audio/managed.mp3",
            mediaUri = "content://downloads/audio/managed.mp3",
            localFileName = "managed.mp3",
            durationMs = 180_000L,
            stableKey = "42|netease|"
        )

        val playbackItem = downloaded.toPlaybackSongItem()

        assertEquals(downloaded.mediaUri, playbackItem.mediaUri)
        assertEquals(downloaded.durationMs, playbackItem.durationMs)
        assertEquals(downloaded.stableKey, playbackItem.sourceStableKey)
        assertEquals("managed.mp3", playbackItem.localFileName)
        assertNull(playbackItem.localFilePath)
    }

    @Test
    fun `delete planner refreshes a snapshot that misses a selected download`() {
        val downloaded = downloadedSong(id = 42L, name = "managed")
        val emptySnapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot()
        val storedAudio = ManagedDownloadStorage.StoredEntry(
            name = "managed.mp3",
            reference = downloaded.filePath,
            mediaUri = downloaded.filePath,
            localFilePath = downloaded.filePath,
            sizeBytes = downloaded.fileSize,
            lastModifiedMs = downloaded.downloadTime
        )
        val matchingSnapshot = emptySnapshot.copy(
            audioEntriesByLookupKey = mapOf(downloaded.filePath to storedAudio)
        )

        assertTrue(
            requiresManagedDownloadDeleteSnapshotRefresh(
                snapshot = emptySnapshot,
                songs = listOf(downloaded)
            )
        )
        assertFalse(
            requiresManagedDownloadDeleteSnapshotRefresh(
                snapshot = matchingSnapshot,
                songs = listOf(downloaded)
            )
        )
    }

    @Test
    fun `full-library artifact fallback includes managed audio absent from catalog`() {
        val catalogAudio = ManagedDownloadStorage.StoredEntry(
            name = "catalog.mp3",
            reference = "content://downloads/audio/catalog.mp3",
            mediaUri = "content://downloads/audio/catalog.mp3",
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
        val orphanAudio = ManagedDownloadStorage.StoredEntry(
            name = "orphan.mp3",
            reference = "content://downloads/audio/orphan.mp3",
            mediaUri = "content://downloads/audio/orphan.mp3",
            localFilePath = null,
            sizeBytes = 128L,
            lastModifiedMs = 1L
        )
        val catalogMetadata = ManagedDownloadStorage.StoredEntry(
            name = "catalog.mp3.npmeta.json",
            reference = "content://downloads/meta/catalog.mp3.npmeta.json",
            mediaUri = "content://downloads/meta/catalog.mp3.npmeta.json",
            localFilePath = null,
            sizeBytes = 64L,
            lastModifiedMs = 1L
        )
        val orphanMetadata = ManagedDownloadStorage.StoredEntry(
            name = "orphan.mp3.npmeta.json",
            reference = "content://downloads/meta/orphan.mp3.npmeta.json",
            mediaUri = "content://downloads/meta/orphan.mp3.npmeta.json",
            localFilePath = null,
            sizeBytes = 64L,
            lastModifiedMs = 1L
        )
        val orphanSidecar = ManagedDownloadStorage.StoredEntry(
            name = "orphan-cover.jpg",
            reference = "content://downloads/covers/orphan-cover.jpg",
            mediaUri = "content://downloads/covers/orphan-cover.jpg",
            localFilePath = null,
            sizeBytes = 64L,
            lastModifiedMs = 1L
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(catalogAudio, orphanAudio),
            audioEntriesByLookupKey = mapOf(
                catalogAudio.reference to catalogAudio,
                orphanAudio.reference to orphanAudio
            ),
            metadataEntriesByAudioName = mapOf(
                catalogAudio.name to catalogMetadata,
                orphanAudio.name to orphanMetadata
            ),
            coverEntriesByName = mapOf(orphanSidecar.name to orphanSidecar),
            metadataByAudioName = mapOf(
                catalogAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = "catalog", audioFileName = catalogAudio.name, downloadFinalized = true),
                orphanAudio.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = "orphan", audioFileName = orphanAudio.name, downloadFinalized = true,
                    coverPath = orphanSidecar.reference)
            ),
            knownReferences = setOf(
                catalogAudio.reference,
                orphanAudio.reference,
                catalogMetadata.reference,
                orphanMetadata.reference,
                orphanSidecar.reference
            )
        )

        val references = ManagedDownloadArtifactPlanner
            .collectFullLibraryArtifactReferences(snapshot)

        assertTrue(references.contains(catalogAudio.reference))
        assertTrue(references.contains(orphanAudio.reference))
        assertTrue(references.contains(catalogMetadata.reference))
        assertTrue(references.contains(orphanMetadata.reference))
        assertTrue(references.contains(orphanSidecar.reference))
    }

    private fun downloadedSong(
        id: Long,
        name: String,
        downloadTime: Long = 1L
    ): DownloadedSong {
        return DownloadedSong(
            id = id,
            name = name,
            artist = "artist",
            album = "album",
            filePath = "/downloads/$name.mp3",
            fileSize = 1024L,
            downloadTime = downloadTime
        )
    }
}
