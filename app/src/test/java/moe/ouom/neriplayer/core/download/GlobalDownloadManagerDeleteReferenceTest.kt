package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
