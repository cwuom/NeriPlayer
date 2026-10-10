package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManagedDownloadFullLibraryArtifactReferencesTest {
    private val audio = entry("song.mp3")
    private val receipt = entry("song.mp3.npmeta.json")
    private val cover = entry("Covers/song.jpg")
    private val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
        audioEntries = listOf(audio),
        audioEntriesByLookupKey = mapOf(audio.reference to audio),
        metadataEntriesByAudioName = mapOf(audio.name to receipt),
        metadataByAudioName = mapOf(
            audio.name to DownloadedAudioMetadata(
                stableKey = "stable-song",
                audioFileName = audio.name,
                downloadFinalized = true,
                coverPath = cover.reference
            )
        ),
        coverEntriesByName = mapOf(cover.name to cover),
        knownReferences = setOf(audio.reference, receipt.reference, cover.reference)
    )

    @Test
    fun `a complete snapshot requests the receipt its audio and its owned cover`() {
        assertEquals(
            setOf(audio.reference, receipt.reference, cover.reference),
            ManagedDownloadArtifactPlanner.collectFullLibraryArtifactReferences(snapshot)
        )
    }

    @Test
    fun `an unparsed receipt cannot authorize deleting its cover`() {
        val references = ManagedDownloadArtifactPlanner.collectFullLibraryArtifactReferences(
            snapshot.copy(metadataByAudioName = emptyMap())
        )

        assertFalse(cover.reference in references)
    }

    @Test
    fun `incomplete root or sidecar enumeration requests nothing`() {
        assertEquals(
            emptySet<String>(),
            ManagedDownloadArtifactPlanner.collectFullLibraryArtifactReferences(
                snapshot.copy(rootEntriesComplete = false)
            )
        )
        assertEquals(
            emptySet<String>(),
            ManagedDownloadArtifactPlanner.collectFullLibraryArtifactReferences(
                snapshot.copy(sidecarEntriesComplete = false)
            )
        )
    }

    private fun entry(name: String) = StoredEntry(
        name = name.substringAfterLast('/'),
        reference = "/library/$name",
        mediaUri = "/library/$name",
        localFilePath = "/library/$name",
        sizeBytes = 10L,
        lastModifiedMs = 1L
    )
}
