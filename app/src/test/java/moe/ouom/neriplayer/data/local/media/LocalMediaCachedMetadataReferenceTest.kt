package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaCachedMetadataReferenceTest {
    private val audioName = "Song.mp3"
    private val audioReference = "content://documents/tree/music/document/audio"
    private val metadataReference = "content://documents/tree/music/document/metadata"

    private fun entry(name: String, reference: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = reference,
        mediaUri = reference,
        localFilePath = null,
        sizeBytes = 100,
        lastModifiedMs = 1
    )

    @Test
    fun `existing sidecar is selected only for the exact finalized audio`() {
        val audio = entry(audioName, audioReference)
        val metadata = entry("$audioName.npmeta.json", metadataReference)
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(audio),
            audioEntriesByLookupKey = mapOf(audioReference to audio),
            metadataEntriesByAudioName = mapOf(audioName to metadata)
        )

        assertEquals(
            metadataReference,
            LocalMediaSupport.selectCachedEditableMetadataReference(
                snapshot, audioReference, audioName
            )
        )
        assertNull(LocalMediaSupport.selectCachedEditableMetadataReference(
            snapshot, "content://documents/other", audioName
        ))
        assertNull(LocalMediaSupport.selectCachedEditableMetadataReference(
            snapshot.copy(rootEntriesComplete = false), audioReference, audioName
        ))
        assertNull(LocalMediaSupport.selectCachedEditableMetadataReference(
            snapshot.copy(audioEntriesByLookupKey = mapOf(
                audioReference to audio.copy(name = "$audioName.npdl_pending.write.pending")
            )), audioReference, audioName
        ))
        assertNull(LocalMediaSupport.selectCachedEditableMetadataReference(
            snapshot.copy(metadataEntriesByAudioName = mapOf(audioName to metadata.copy(name = "Other.npmeta.json"))),
            audioReference, audioName
        ))
    }
}
