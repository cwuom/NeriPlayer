package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadParsedMetadataEntry
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadUnfinalizedCleanupPlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadUnfinalizedCleanupPlannerTest {

    @Test
    fun `unfinalized cleanup deletes its unshared romanized lyric`() {
        val metadataReference = "content://downloads/audio/pending.mp3.npmeta.json"
        val audioReference = "content://downloads/audio/pending.mp3"
        val romanizedReference = "content://downloads/lyrics/pending_roma.lrc"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("pending.mp3.npmeta.json", metadataReference),
                entry("pending.mp3", audioReference, sizeBytes = 0L)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("pending.mp3.npmeta.json", metadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        downloadFinalized = false,
                        romanizedLyricPath = romanizedReference
                    )
                )
            ),
            managedSidecarReferences = setOf(romanizedReference)
        )

        assertTrue(references.contains(romanizedReference))
    }

    @Test
    fun `unfinalized cleanup preserves romanized lyric shared by finalized download`() {
        val pendingMetadataReference = "content://downloads/audio/pending.mp3.npmeta.json"
        val pendingAudioReference = "content://downloads/audio/pending.mp3"
        val completedMetadataReference = "content://downloads/audio/completed.mp3.npmeta.json"
        val sharedRomanizedReference = "content://downloads/lyrics/shared_roma.lrc"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("pending.mp3.npmeta.json", pendingMetadataReference),
                entry("pending.mp3", pendingAudioReference, sizeBytes = 0L),
                entry("completed.mp3.npmeta.json", completedMetadataReference)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("pending.mp3.npmeta.json", pendingMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        downloadFinalized = false,
                        romanizedLyricPath = sharedRomanizedReference
                    )
                ),
                ManagedDownloadParsedMetadataEntry(
                    entry("completed.mp3.npmeta.json", completedMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        downloadFinalized = true,
                        romanizedLyricPath = sharedRomanizedReference
                    )
                )
            ),
            managedSidecarReferences = setOf(sharedRomanizedReference)
        )

        assertFalse(references.contains(sharedRomanizedReference))
    }

    private fun entry(
        name: String,
        reference: String,
        sizeBytes: Long = 1L
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = 1L
        )
    }
}
