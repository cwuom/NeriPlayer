package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoreCommitPolicyTest {
    @Test
    fun `cancel before core commit may rollback operation owned audio`() {
        assertTrue(
            shouldRollbackCancelledAudio(
                DownloadCoreCommitPhase.STAGING
            )
        )
        assertFalse(
            shouldRollbackCancelledAudio(
                DownloadCoreCommitPhase.COMMITTING
            )
        )
    }

    @Test
    fun `late cancel after core commit preserves playable audio`() {
        assertFalse(
            shouldRollbackCancelledAudio(
                DownloadCoreCommitPhase.CORE_COMMITTED
            )
        )
    }

    @Test
    fun `metadata state protects durable audio from late cancellation`() {
        assertTrue(shouldPreserveAudioAfterCancellation(false, "CORE_COMMITTED"))
        assertTrue(shouldPreserveAudioAfterCancellation(false, "ASSETS_ENRICHING"))
        assertTrue(shouldPreserveAudioAfterCancellation(true, "COMMITTING"))
        assertFalse(shouldPreserveAudioAfterCancellation(false, "COMMITTING"))
        assertFalse(shouldPreserveAudioAfterCancellation(false, null))
    }

    @Test
    fun `unreadable non-pending audio is preserved during cancellation rollback`() {
        assertTrue(
            shouldPreserveAudioForCancellationRollback(
                audioIsPending = false,
                metadataReadable = false,
                downloadFinalized = null,
                artifactState = null,
                metadataOperationId = null,
                operationId = "operation-1"
            )
        )
    }

    @Test
    fun `operation ownership mismatch preserves audio during cancellation rollback`() {
        assertTrue(
            shouldPreserveAudioForCancellationRollback(
                audioIsPending = true,
                metadataReadable = true,
                downloadFinalized = false,
                artifactState = "COMMITTING",
                metadataOperationId = "operation-old",
                operationId = "operation-new"
            )
        )
    }

    @Test
    fun `committing final audio is preserved even when metadata is not finalized`() {
        assertTrue(
            shouldPreserveAudioForCancellationRollback(
                audioIsPending = false,
                metadataReadable = true,
                downloadFinalized = false,
                artifactState = "COMMITTING",
                metadataOperationId = null,
                operationId = null
            )
        )
    }

    @Test
    fun `owned pending audio remains eligible for pre-commit rollback`() {
        assertFalse(
            shouldPreserveAudioForCancellationRollback(
                audioIsPending = true,
                metadataReadable = true,
                downloadFinalized = false,
                artifactState = "COMMITTING",
                metadataOperationId = "operation-1",
                operationId = "operation-1"
            )
        )
    }

    @Test
    fun `core commit publication requires durable metadata`() {
        assertTrue(shouldPublishCoreCommit(true, false))
        assertTrue(shouldPublishCoreCommit(false, true))
        assertFalse(shouldPublishCoreCommit(false, false))
    }

    @Test
    fun `provider failure and permission loss are never missing evidence`() {
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.ProviderFailure(
                    IllegalStateException("provider offline")
                )
            )
        )
        assertFalse(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.PermissionLost(
                    SecurityException("grant revoked")
                )
            )
        )
        assertTrue(
            ManagedDownloadReferenceLookup.canMarkMissing(
                ManagedDownloadReferenceLookup.Result.Missing
            )
        )
    }

    @Test
    fun `core artifact states are durable even when final enrichment is incomplete`() {
        assertTrue(isDurableCoreArtifactState("CORE_COMMITTED"))
        assertTrue(isDurableCoreArtifactState("ASSETS_ENRICHING"))
        assertTrue(isDurableCoreArtifactState("DEGRADED_COMPLETE"))
        assertFalse(isDurableCoreArtifactState("COMMITTING"))
        assertFalse(isDurableCoreArtifactState(null))
    }

    @Test
    fun `only incomplete durable artifacts resume final enrichment`() {
        assertTrue(requiresDownloadFinalizationRecovery("CORE_COMMITTED"))
        assertTrue(requiresDownloadFinalizationRecovery("ASSETS_ENRICHING"))
        assertTrue(requiresDownloadFinalizationRecovery("DEGRADED_COMPLETE"))
        assertFalse(requiresDownloadFinalizationRecovery("FINALIZED"))
        assertFalse(requiresDownloadFinalizationRecovery("COMMITTING"))
        assertFalse(requiresDownloadFinalizationRecovery(null))
    }
}
