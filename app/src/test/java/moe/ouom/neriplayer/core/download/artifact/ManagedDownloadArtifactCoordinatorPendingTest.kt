package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadArtifactCoordinatorPendingTest {
    @Test
    fun `artifact reconciliation keeps pending audio alongside finalized audio`() {
        val finalized = entry(
            name = "final.flac",
            reference = "content://downloads/final"
        )
        val pending = entry(
            name = "pending.flac.npdl_pending.operation.pending",
            reference = "content://downloads/pending"
        )
        val duplicatePending = pending.copy(
            name = "pending-copy.flac.npdl_pending.operation.pending"
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(finalized),
            pendingAudioEntries = listOf(pending, duplicatePending, pending)
        )

        assertEquals(
            listOf(finalized, pending),
            artifactReconciliationAudioEntries(snapshot)
        )
    }

    @Test
    fun `durable metadata state survives discovery before final promotion`() {
        assertEquals(
            ManagedDownloadArtifactState.CORE_COMMITTED,
            resolveDiscoveredManagedArtifactState(
                finalized = false,
                metadataArtifactState = "CORE_COMMITTED"
            )
        )
        assertEquals(
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            resolveDiscoveredManagedArtifactState(
                finalized = false,
                metadataArtifactState = "assets_enriching"
            )
        )
        assertEquals(
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            resolveDiscoveredManagedArtifactState(
                finalized = false,
                metadataArtifactState = "DEGRADED_COMPLETE"
            )
        )
        assertEquals(
            ManagedDownloadArtifactState.FINALIZED,
            resolveDiscoveredManagedArtifactState(
                finalized = true,
                metadataArtifactState = "CORE_COMMITTED"
            )
        )
        assertEquals(
            ManagedDownloadArtifactState.REPAIR_REQUIRED,
            resolveDiscoveredManagedArtifactState(
                finalized = false,
                metadataArtifactState = "COMMITTING"
            )
        )
    }

    @Test
    fun `catalog does not promote an existing legacy repair state`() {
        assertEquals(
            ManagedDownloadArtifactState.REPAIR_REQUIRED,
            resolveCatalogArtifactState(
                currentState = ManagedDownloadArtifactState.fromPersisted("LEGACY_FALLBACK"),
                hasAudioReference = true
            )
        )
        assertEquals(
            ManagedDownloadArtifactState.FINALIZED,
            resolveCatalogArtifactState(
                currentState = ManagedDownloadArtifactState.FINALIZED,
                hasAudioReference = true
            )
        )
        assertEquals(
            ManagedDownloadArtifactState.FINALIZED,
            resolveCatalogArtifactState(
                currentState = null,
                hasAudioReference = true
            )
        )
    }

    private fun entry(
        name: String,
        reference: String
    ): ManagedDownloadStorage.StoredEntry {
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
