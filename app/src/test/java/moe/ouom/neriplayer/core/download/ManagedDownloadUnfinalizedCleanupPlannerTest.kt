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
                        metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
                        romanizedLyricPath = sharedRomanizedReference
                    )
                )
            ),
            managedSidecarReferences = setOf(sharedRomanizedReference)
        )

        assertFalse(references.contains(sharedRomanizedReference))
    }

    @Test
    fun `unfinalized canonical metadata preserves nonempty pending audio and sidecars`() {
        val metadataReference = "content://downloads/audio/song.mp3.npmeta.json"
        val pendingAudioReference = "content://downloads/audio/song.mp3.npdl_pending.1.pending"
        val coverReference = "content://downloads/covers/song.jpg"
        val lyricReference = "content://downloads/lyrics/song.lrc"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("song.mp3.npmeta.json", metadataReference),
                entry("song.mp3.npdl_pending.1.pending", pendingAudioReference)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("song.mp3.npmeta.json", metadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        downloadFinalized = false,
                        coverPath = coverReference,
                        lyricPath = lyricReference
                    )
                )
            ),
            managedSidecarReferences = setOf(coverReference, lyricReference)
        )

        assertFalse(references.contains(metadataReference))
        assertFalse(references.contains(pendingAudioReference))
        assertFalse(references.contains(coverReference))
        assertFalse(references.contains(lyricReference))
    }

    @Test
    fun `unfinalized pending metadata preserves nonempty pending audio and sidecars`() {
        val metadataReference = "content://downloads/audio/song.mp3.npmeta.pending.json"
        val pendingAudioReference = "content://downloads/audio/song.mp3.npdl_pending.2.pending"
        val translatedLyricReference = "content://downloads/lyrics/song_trans.lrc"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("song.mp3.npmeta.pending.json", metadataReference),
                entry("song.mp3.npdl_pending.2.pending", pendingAudioReference)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("song.mp3.npmeta.pending.json", metadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        downloadFinalized = false,
                        translatedLyricPath = translatedLyricReference
                    )
                )
            ),
            managedSidecarReferences = setOf(translatedLyricReference)
        )

        assertFalse(references.contains(metadataReference))
        assertFalse(references.contains(pendingAudioReference))
        assertFalse(references.contains(translatedLyricReference))
    }

    @Test
    fun `durable core metadata protects zero sized SAF pending audio`() {
        val metadataReference = "content://downloads/audio/core.mp3.npmeta.json"
        val pendingAudioReference =
            "content://downloads/tmp/core.mp3.npdl_pending.1.pending"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("core.mp3.npmeta.json", metadataReference),
                entry(
                    "core.mp3.npdl_pending.1.pending",
                    pendingAudioReference,
                    sizeBytes = 0L
                )
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("core.mp3.npmeta.json", metadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = "song:core",
                        operationId = "operation:core",
                        audioFileName = "core.mp3",
                        downloadFinalized = false,
                        artifactState = "CORE_COMMITTED"
                    )
                )
            ),
            managedSidecarReferences = emptySet()
        )

        assertTrue(references.isEmpty())
    }

    @Test
    fun `durable core sidecar is protected from another empty unfinalized entry`() {
        val durableMetadataReference = "content://downloads/audio/core.mp3.npmeta.json"
        val durableAudioReference = "content://downloads/tmp/core.mp3.npdl_pending.core.pending"
        val staleMetadataReference = "content://downloads/audio/stale.mp3.npmeta.json"
        val staleAudioReference = "content://downloads/tmp/stale.mp3.npdl_pending.stale.pending"
        val sharedSidecarReference = "content://downloads/covers/shared.jpg"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("core.mp3.npmeta.json", durableMetadataReference),
                entry("core.mp3.npdl_pending.core.pending", durableAudioReference, sizeBytes = 0L),
                entry("stale.mp3.npmeta.json", staleMetadataReference),
                entry("stale.mp3.npdl_pending.stale.pending", staleAudioReference, sizeBytes = 0L)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("core.mp3.npmeta.json", durableMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = "song:core",
                        operationId = "operation:core",
                        audioFileName = "core.mp3",
                        downloadFinalized = false,
                        artifactState = "CORE_COMMITTED",
                        coverPath = sharedSidecarReference
                    )
                ),
                ManagedDownloadParsedMetadataEntry(
                    entry("stale.mp3.npmeta.json", staleMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        downloadFinalized = false,
                        coverPath = sharedSidecarReference
                    )
                )
            ),
            managedSidecarReferences = setOf(sharedSidecarReference)
        )

        assertTrue(references.contains(staleMetadataReference))
        assertTrue(references.contains(staleAudioReference))
        assertFalse(references.contains(durableMetadataReference))
        assertFalse(references.contains(durableAudioReference))
        assertFalse(references.contains(sharedSidecarReference))
    }

    @Test
    fun `unknown provider size preserves unfinalized audio`() {
        val metadataReference = "content://downloads/audio/unknown.mp3.npmeta.json"
        val audioReference = "content://downloads/audio/unknown.mp3"
        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("unknown.mp3.npmeta.json", metadataReference),
                entry("unknown.mp3", audioReference, sizeBytes = 0L, sizeKnown = false)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("unknown.mp3.npmeta.json", metadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(downloadFinalized = false)
                )
            ),
            managedSidecarReferences = emptySet()
        )

        assertFalse(references.contains(metadataReference))
        assertFalse(references.contains(audioReference))
    }

    @Test
    fun `collision orphan pending metadata is removed only after matching final audio`() {
        val orphanMetadataReference = "content://downloads/audio/song.mp3.npmeta.pending.json"
        val existingAudioReference = "content://downloads/audio/song.mp3"
        val finalizedMetadataReference =
            "content://downloads/audio/song (1).mp3.npmeta.json"
        val finalizedAudioReference = "content://downloads/audio/song (1).mp3"
        val stableKey = "netease:42"
        val operationId = "operation-42"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("song.mp3.npmeta.pending.json", orphanMetadataReference),
                entry("song.mp3", existingAudioReference),
                entry("song (1).mp3.npmeta.json", finalizedMetadataReference),
                entry("song (1).mp3", finalizedAudioReference)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("song.mp3.npmeta.pending.json", orphanMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = stableKey,
                        operationId = operationId,
                        audioFileName = "song.mp3",
                        downloadFinalized = false
                    )
                ),
                ManagedDownloadParsedMetadataEntry(
                    entry("song (1).mp3.npmeta.json", finalizedMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = stableKey,
                        operationId = operationId,
                        audioFileName = "song (1).mp3",
                        downloadFinalized = true,
                        metadataEmbeddingState = DownloadedAudioEmbeddingState.USER_DISABLED
                    )
                )
            ),
            managedSidecarReferences = emptySet()
        )

        assertTrue(references.contains(orphanMetadataReference))
        assertFalse(references.contains(existingAudioReference))
        assertFalse(references.contains(finalizedMetadataReference))
        assertFalse(references.contains(finalizedAudioReference))
    }

    @Test
    fun `collision orphan pending metadata is retained without matching operation identity`() {
        val orphanMetadataReference = "content://downloads/audio/song.mp3.npmeta.pending.json"
        val finalizedMetadataReference =
            "content://downloads/audio/song (1).mp3.npmeta.json"
        val finalizedAudioReference = "content://downloads/audio/song (1).mp3"

        val references = ManagedDownloadUnfinalizedCleanupPlanner.planReferencesToDelete(
            rootEntries = listOf(
                entry("song.mp3.npmeta.pending.json", orphanMetadataReference),
                entry("song.mp3", "content://downloads/audio/song.mp3"),
                entry("song (1).mp3.npmeta.json", finalizedMetadataReference),
                entry("song (1).mp3", finalizedAudioReference)
            ),
            parsedMetadataEntries = listOf(
                ManagedDownloadParsedMetadataEntry(
                    entry("song.mp3.npmeta.pending.json", orphanMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = "netease:42",
                        operationId = "operation-old",
                        audioFileName = "song.mp3",
                        downloadFinalized = false
                    )
                ),
                ManagedDownloadParsedMetadataEntry(
                    entry("song (1).mp3.npmeta.json", finalizedMetadataReference),
                    ManagedDownloadStorage.DownloadedAudioMetadata(
                        stableKey = "netease:42",
                        operationId = "operation-new",
                        audioFileName = "song (1).mp3",
                        downloadFinalized = true,
                        metadataEmbeddingState = DownloadedAudioEmbeddingState.USER_DISABLED
                    )
                )
            ),
            managedSidecarReferences = emptySet()
        )

        assertFalse(references.contains(orphanMetadataReference))
    }

    private fun entry(
        name: String,
        reference: String,
        sizeBytes: Long = 1L,
        sizeKnown: Boolean = true
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = 1L,
            sizeKnown = sizeKnown
        )
    }
}
