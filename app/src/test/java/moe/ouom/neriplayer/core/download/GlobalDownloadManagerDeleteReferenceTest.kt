package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.cleanup.requiresManagedDownloadDeleteSnapshotRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalDownloadManagerDeleteReferenceTest {

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

        val references = ManagedDownloadArtifactPlanner.collectArtifactReferences(
            snapshot = snapshot,
            storedAudio = currentAudio,
            songId = 1L,
            candidateBaseNames = listOf("artist - current"),
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

        val references = ManagedDownloadArtifactPlanner.collectArtifactReferences(
            snapshot = snapshot,
            storedAudio = currentAudio,
            songId = 1L,
            candidateBaseNames = listOf("artist - current"),
            explicitReferences = listOf(sharedRomanizedReference)
        )

        assertEquals(setOf(currentAudio.reference, currentMetadataReference), references)
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
    fun `downloaded song creates a playback item from its managed media reference`() {
        val downloaded = downloadedSong(id = 42L, name = "managed").copy(
            filePath = "content://downloads/audio/managed.mp3",
            mediaUri = "content://downloads/audio/managed.mp3",
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
