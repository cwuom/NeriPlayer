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

    @Test
    fun `durable core committed pending pair is protected from cancellation cleanup`() {
        val audioName = "Artist - Song.mp3"
        val metadata = entry("$audioName.npmeta.pending.json", "metadata-core")
        val pendingAudio = entry("$audioName.npdl_pending.core.pending", "audio-core")

        val plan = ManagedDownloadPendingArtifactCleanupPlanner.planCancelledOperation(
            rootEntries = listOf(metadata, pendingAudio),
            parsedMetadataEntries = listOf(
                parsed(
                    entry = metadata,
                    stableKey = "song-key",
                    operationId = "operation-current",
                    audioName = audioName,
                    artifactState = "core_committed"
                )
            ),
            stableKey = "song-key",
            operationId = "operation-current"
        )

        assertTrue(plan.referencesToDelete.isEmpty())
        assertEquals(setOf("metadata-core", "audio-core"), plan.protectedReferences)
    }

    @Test
    fun `finalized flag protects pending pair even with legacy committing state`() {
        val audioName = "Artist - Song.mp3"
        val metadata = entry("$audioName.npmeta.pending.json", "metadata-finalized")
        val pendingAudio = entry("$audioName.npdl_pending.finalized.pending", "audio-finalized")

        val plan = ManagedDownloadPendingArtifactCleanupPlanner.planCancelledOperation(
            rootEntries = listOf(metadata, pendingAudio),
            parsedMetadataEntries = listOf(
                parsed(
                    entry = metadata,
                    stableKey = "song-key",
                    operationId = "operation-current",
                    audioName = audioName,
                    downloadFinalized = true,
                    artifactState = "COMMITTING"
                )
            ),
            stableKey = "song-key",
            operationId = "operation-current"
        )

        assertTrue(plan.referencesToDelete.isEmpty())
        assertEquals(
            setOf("metadata-finalized", "audio-finalized"),
            plan.protectedReferences
        )
    }

    @Test
    fun `pending scan exposes blocking count without changing legacy count`() {
        val result = ManagedDownloadStorage.PendingArtifactScanResult(
            count = 5,
            isComplete = true,
            protectedCount = 2
        )

        assertEquals(5, result.count)
        assertTrue(result.isComplete)
        assertEquals(3, result.blockingCount)
    }

    @Test
    fun `unreadable metadata blocks same name pending audio from clear deletion`() {
        val audioName = "Artist - Song.mp3"
        val unreadableMetadata = entry(
            "$audioName.npmeta.pending.json",
            "metadata-unreadable"
        )
        val pendingAudio = entry(
            "$audioName.npdl_pending.operation.pending",
            "audio-pending"
        )
        val unrelatedAudio = entry(
            "Other - Song.npdl_pending.operation.pending",
            "audio-unrelated"
        )

        assertEquals(
            setOf("metadata-unreadable", "audio-pending"),
            ManagedDownloadStorage.resolveUnreadablePendingArtifactReferences(
                pendingEntries = listOf(unreadableMetadata, pendingAudio, unrelatedAudio),
                metadataEntries = listOf(unreadableMetadata),
                unreadableMetadataReferences = setOf("metadata-unreadable")
            )
        )
    }

    @Test
    fun `failed cleanup reports only songs owning failed references`() {
        val failedSongs = ManagedDownloadStorage.resolveFailedStableKeys(
            referencesByStableKey = mapOf(
                "song-a" to setOf("metadata-a", "audio-a"),
                "song-b" to setOf("metadata-b"),
                "song-c" to setOf("metadata-c")
            ),
            failedReferences = setOf("audio-a", "not-owned")
        )

        assertEquals(setOf("song-a"), failedSongs)
    }

    @Test
    fun `empty failed cleanup does not create residual song keys`() {
        assertTrue(
            ManagedDownloadStorage.resolveFailedStableKeys(
                referencesByStableKey = mapOf("song-a" to setOf("metadata-a")),
                failedReferences = emptySet()
            ).isEmpty()
        )
    }

    @Test
    fun `root core seed protects tmp committing pair during clear`() {
        val audioName = "Artist - Song.mp3"
        val seedMetadata = entry("$audioName.npmeta.json", "metadata-seed")
        val pendingMetadata = entry("$audioName.npmeta.pending.json", "metadata-pending")
        val pendingAudio = entry("$audioName.npdl_pending.core.pending", "audio-core")

        val plan = ManagedDownloadPendingArtifactCleanupPlanner.planCancelledOperation(
            rootEntries = listOf(seedMetadata, pendingMetadata, pendingAudio),
            parsedMetadataEntries = listOf(
                parsed(
                    entry = seedMetadata,
                    stableKey = "song-key",
                    operationId = "operation-current",
                    audioName = audioName,
                    artifactState = "CORE_COMMITTED"
                ),
                parsed(
                    entry = pendingMetadata,
                    stableKey = "song-key",
                    operationId = "operation-current",
                    audioName = audioName,
                    artifactState = "COMMITTING"
                )
            ),
            stableKey = "song-key",
            operationId = "operation-current"
        )

        assertTrue(plan.referencesToDelete.isEmpty())
        assertEquals(
            setOf("metadata-pending", "audio-core"),
            plan.protectedReferences
        )
    }

    @Test
    fun `explicit clear removes legacy root sentinel without metadata credential`() {
        val pending = entry(
            "old.mp3.npdl_pending.legacy.pending",
            "root-old"
        )
        val ordinary = entry("kept.mp3", "root-kept")

        val plan = ManagedDownloadPendingArtifactCleanupPlanner
            .planUnownedForExplicitClear(
                entries = listOf(pending, ordinary),
                temporaryReferences = emptySet(),
                parsedMetadataEntries = emptyList(),
                unreadableMetadataReferences = emptySet()
            )

        assertEquals(setOf("root-old"), plan.referencesToDelete)
        assertTrue(plan.protectedReferences.isEmpty())
    }

    @Test
    fun `explicit clear preserves root sentinel paired with unreadable metadata`() {
        val audioName = "recoverable.mp3"
        val pending = entry(
            "$audioName.npdl_pending.legacy.pending",
            "root-pending"
        )
        val metadata = entry(
            "$audioName.npmeta.pending.json",
            "root-metadata"
        )

        val plan = ManagedDownloadPendingArtifactCleanupPlanner
            .planUnownedForExplicitClear(
                entries = listOf(pending, metadata),
                temporaryReferences = emptySet(),
                parsedMetadataEntries = emptyList(),
                unreadableMetadataReferences = setOf("root-metadata")
            )

        assertTrue(plan.referencesToDelete.isEmpty())
        assertTrue(plan.protectedReferences.isEmpty())
    }

    @Test
    fun `explicit clear protects root sentinel when durable metadata is present`() {
        val audioName = "core.mp3"
        val pending = entry(
            "$audioName.npdl_pending.core.pending",
            "root-core-pending"
        )
        val metadata = entry(
            "$audioName.npmeta.json",
            "root-core-metadata"
        )

        val plan = ManagedDownloadPendingArtifactCleanupPlanner
            .planUnownedForExplicitClear(
                entries = listOf(pending, metadata),
                temporaryReferences = emptySet(),
                parsedMetadataEntries = listOf(
                    parsed(
                        entry = metadata,
                        stableKey = "core",
                        operationId = "operation-core",
                        audioName = audioName,
                        artifactState = "CORE_COMMITTED"
                    )
                ),
                unreadableMetadataReferences = emptySet()
            )

        assertTrue(plan.referencesToDelete.isEmpty())
        assertEquals(setOf("root-core-pending"), plan.protectedReferences)
    }

    @Test
    fun `explicit clear preserves committing pending pair`() {
        val audioName = "committing.mp3"
        val pending = entry(
            "$audioName.npdl_pending.commit.pending",
            "root-committing-pending"
        )
        val metadata = entry(
            "$audioName.npmeta.pending.json",
            "root-committing-metadata"
        )

        val plan = ManagedDownloadPendingArtifactCleanupPlanner
            .planUnownedForExplicitClear(
                entries = listOf(pending, metadata),
                temporaryReferences = emptySet(),
                parsedMetadataEntries = listOf(
                    parsed(
                        entry = metadata,
                        stableKey = "committing",
                        operationId = "operation-committing",
                        audioName = audioName,
                        artifactState = "COMMITTING"
                    )
                ),
                unreadableMetadataReferences = emptySet()
            )

        assertTrue(plan.referencesToDelete.isEmpty())
        assertEquals(
            setOf("root-committing-pending", "root-committing-metadata"),
            plan.protectedReferences
        )
    }

    @Test
    fun `explicit clear removes known transient tmp pair but preserves unknown tmp audio`() {
        val transientAudio = entry(
            "transient.mp3.npdl_pending.one.pending",
            "tmp-transient"
        )
        val transientMetadata = entry(
            "transient.mp3.npmeta.pending.json",
            "tmp-transient-metadata"
        )
        val unknownAudio = entry(
            "unknown.mp3.npdl_pending.two.pending",
            "tmp-unknown"
        )

        val plan = ManagedDownloadPendingArtifactCleanupPlanner
            .planUnownedForExplicitClear(
                entries = listOf(transientAudio, transientMetadata, unknownAudio),
                temporaryReferences = setOf(
                    "tmp-transient",
                    "tmp-transient-metadata",
                    "tmp-unknown"
                ),
                parsedMetadataEntries = listOf(
                    parsed(
                        entry = transientMetadata,
                        stableKey = "transient",
                        operationId = "operation-transient",
                        audioName = "transient.mp3",
                        artifactState = "DOWNLOADING"
                    )
                ),
                unreadableMetadataReferences = emptySet()
            )

        assertEquals(
            setOf("tmp-transient", "tmp-transient-metadata"),
            plan.referencesToDelete
        )
        assertFalse(plan.referencesToDelete.contains("tmp-unknown"))
    }

    private fun parsed(
        entry: ManagedDownloadStorage.StoredEntry,
        stableKey: String,
        operationId: String,
        audioName: String,
        downloadFinalized: Boolean? = false,
        artifactState: String? = "COMMITTING"
    ): ManagedDownloadParsedMetadataEntry {
        return ManagedDownloadParsedMetadataEntry(
            entry = entry,
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = stableKey,
                operationId = operationId,
                audioFileName = audioName,
                downloadFinalized = downloadFinalized,
                artifactState = artifactState
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
