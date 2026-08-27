package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadPendingArtifactCleanupPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadPendingArtifactCleanupPlannerTest {

    @Test
    fun `cancelled operation removes its unique pending metadata and audio`() {
        val audioName = "Artist - Song.mp3"
        val metadata = entry("$audioName.npmeta.pending.json", "metadata-current")
        val pendingAudio = entry("$audioName.npdl_pending.current.pending", "audio-current")

        val references = ManagedDownloadPendingArtifactCleanupPlanner
            .planCancelledOperationReferences(
                rootEntries = listOf(metadata, pendingAudio),
                parsedMetadataEntries = listOf(
                    parsed(
                        entry = metadata,
                        stableKey = "song-key",
                        operationId = "operation-current",
                        audioName = audioName
                    )
                ),
                stableKey = "song-key",
                operationId = "operation-current"
            )

        assertEquals(setOf("metadata-current", "audio-current"), references)
    }

    @Test
    fun `cancelled operation never removes a different operation residue`() {
        val audioName = "Artist - Song.mp3"
        val metadata = entry("$audioName.npmeta.pending.json", "metadata-other")
        val pendingAudio = entry("$audioName.npdl_pending.other.pending", "audio-other")

        val references = ManagedDownloadPendingArtifactCleanupPlanner
            .planCancelledOperationReferences(
                rootEntries = listOf(metadata, pendingAudio),
                parsedMetadataEntries = listOf(
                    parsed(
                        entry = metadata,
                        stableKey = "song-key",
                        operationId = "operation-other",
                        audioName = audioName
                    )
                ),
                stableKey = "song-key",
                operationId = "operation-current"
            )

        assertTrue(references.isEmpty())
    }

    @Test
    fun `ambiguous pending audio is preserved while owned metadata is removed`() {
        val audioName = "Artist - Song.mp3"
        val metadata = entry("$audioName.npmeta.pending.json", "metadata-current")
        val currentPendingAudio = entry("$audioName.npdl_pending.current.pending", "audio-current")
        val unknownPendingAudio = entry("$audioName.npdl_pending.unknown.pending", "audio-unknown")

        val references = ManagedDownloadPendingArtifactCleanupPlanner
            .planCancelledOperationReferences(
                rootEntries = listOf(metadata, currentPendingAudio, unknownPendingAudio),
                parsedMetadataEntries = listOf(
                    parsed(
                        entry = metadata,
                        stableKey = "song-key",
                        operationId = "operation-current",
                        audioName = audioName
                    )
                ),
                stableKey = "song-key",
                operationId = "operation-current"
            )

        assertTrue(references.contains("metadata-current"))
        assertFalse(references.contains("audio-current"))
        assertFalse(references.contains("audio-unknown"))
    }

    @Test
    fun `numbered pending metadata is matched by its logical audio name`() {
        val audioName = "Artist - Song.mp3"
        val metadata = entry("$audioName.npmeta.pending (1).json", "metadata-current")
        val pendingAudio = entry("$audioName.npdl_pending.current.pending", "audio-current")

        val references = ManagedDownloadPendingArtifactCleanupPlanner
            .planCancelledOperationReferences(
                rootEntries = listOf(metadata, pendingAudio),
                parsedMetadataEntries = listOf(
                    parsed(
                        entry = metadata,
                        stableKey = "song-key",
                        operationId = "operation-current",
                        audioName = audioName
                    )
                ),
                stableKey = "song-key",
                operationId = "operation-current"
            )

        assertEquals(setOf("metadata-current", "audio-current"), references)
    }

    @Test
    fun `duplicate pending metadata from the same cancelled operation cleans its unique audio`() {
        val audioName = "Artist - Song.mp3"
        val firstMetadata = entry("$audioName.npmeta.pending.json", "metadata-first")
        val secondMetadata = entry("$audioName.npmeta.pending (1).json", "metadata-second")
        val pendingAudio = entry("$audioName.npdl_pending.current.pending", "audio-current")

        val references = ManagedDownloadPendingArtifactCleanupPlanner
            .planCancelledOperationReferences(
                rootEntries = listOf(firstMetadata, secondMetadata, pendingAudio),
                parsedMetadataEntries = listOf(
                    parsed(firstMetadata, "song-key", "operation-current", audioName),
                    parsed(secondMetadata, "song-key", "operation-current", audioName)
                ),
                stableKey = "song-key",
                operationId = "operation-current"
            )

        assertEquals(
            setOf("metadata-first", "metadata-second", "audio-current"),
            references
        )
    }

    @Test
    fun `unparsed pending metadata keeps pending audio while removing proven metadata`() {
        val audioName = "Artist - Song.mp3"
        val knownMetadata = entry("$audioName.npmeta.pending.json", "metadata-current")
        val unreadableMetadata = entry("$audioName.npmeta.pending (1).json", "metadata-unreadable")
        val pendingAudio = entry("$audioName.npdl_pending.current.pending", "audio-current")

        val references = ManagedDownloadPendingArtifactCleanupPlanner
            .planCancelledOperationReferences(
                rootEntries = listOf(knownMetadata, unreadableMetadata, pendingAudio),
                parsedMetadataEntries = listOf(
                    parsed(knownMetadata, "song-key", "operation-current", audioName)
                ),
                stableKey = "song-key",
                operationId = "operation-current"
            )

        assertEquals(setOf("metadata-current"), references)
    }

    private fun parsed(
        entry: ManagedDownloadStorage.StoredEntry,
        stableKey: String,
        operationId: String,
        audioName: String
    ): ManagedDownloadParsedMetadataEntry {
        return ManagedDownloadParsedMetadataEntry(
            entry = entry,
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = stableKey,
                operationId = operationId,
                audioFileName = audioName,
                downloadFinalized = false,
                artifactState = "COMMITTING"
            )
        )
    }

    private fun entry(name: String, reference: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }
}
