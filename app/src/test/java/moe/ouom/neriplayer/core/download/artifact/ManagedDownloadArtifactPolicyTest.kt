package moe.ouom.neriplayer.core.download.artifact

import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.model.isAcceptedDownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.local.database.entity.ManagedDownloadArtifactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadArtifactPolicyTest {
    @Test
    fun `missing identity obtains the only lease`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = null,
                nowMs = 100L
            )
        )
    }

    @Test
    fun `finalized identity settles without another lease`() {
        assertEquals(
            ManagedDownloadArtifactDecision.AlreadyDownloaded,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.FINALIZED, 100L),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `finalized artifact settles only with matching strict metadata`() {
        listOf(
            DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            DownloadedAudioEmbeddingState.USER_DISABLED
        ).forEach { embeddingState ->
            assertEquals(
                ManagedDownloadArtifactFinalizationDisposition.SETTLED,
                resolveFinalizedArtifactCompletionDisposition(
                    artifactState = ManagedDownloadArtifactState.FINALIZED,
                    snapshotIsComplete = true,
                    matchingAudioFound = true,
                    metadataIdentity = ManagedDownloadArtifactMetadataIdentity.MATCHING,
                    metadataHasStrictCompletion =
                        isAcceptedDownloadedAudioEmbeddingState(embeddingState)
                )
            )
        }
        listOf(
            DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED,
            DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
        ).forEach { embeddingState ->
            val hasStrictCompletion = isAcceptedDownloadedAudioEmbeddingState(embeddingState)
            assertFalse(hasStrictCompletion)
            assertEquals(
                ManagedDownloadArtifactFinalizationDisposition.FINALIZATION_REQUIRED,
                resolveFinalizedArtifactCompletionDisposition(
                    artifactState = ManagedDownloadArtifactState.FINALIZED,
                    snapshotIsComplete = true,
                    matchingAudioFound = true,
                    metadataIdentity = ManagedDownloadArtifactMetadataIdentity.MATCHING,
                    metadataHasStrictCompletion = hasStrictCompletion
                )
            )
        }
        assertEquals(
            ManagedDownloadArtifactFinalizationDisposition.FINALIZATION_REQUIRED,
            resolveFinalizedArtifactCompletionDisposition(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                snapshotIsComplete = true,
                matchingAudioFound = true,
                metadataIdentity = ManagedDownloadArtifactMetadataIdentity.MISSING,
                metadataHasStrictCompletion = false
            )
        )
    }

    @Test
    fun `finalized artifact keeps recovery pending when live evidence is incomplete`() {
        listOf(
            resolveFinalizedArtifactCompletionDisposition(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                snapshotIsComplete = false,
                matchingAudioFound = true,
                metadataIdentity = ManagedDownloadArtifactMetadataIdentity.MATCHING,
                metadataHasStrictCompletion = true
            ),
            resolveFinalizedArtifactCompletionDisposition(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                snapshotIsComplete = true,
                matchingAudioFound = false,
                metadataIdentity = ManagedDownloadArtifactMetadataIdentity.MISSING,
                metadataHasStrictCompletion = false
            ),
            resolveFinalizedArtifactCompletionDisposition(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                snapshotIsComplete = true,
                matchingAudioFound = true,
                metadataIdentity = ManagedDownloadArtifactMetadataIdentity.MISMATCHED,
                metadataHasStrictCompletion = true
            )
        ).forEach { disposition ->
            assertEquals(
                ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE,
                disposition
            )
        }
    }

    @Test
    fun `legacy finalized recovery yields one lease owner at a time`() {
        val recoveryArtifact = artifact(
            state = ManagedDownloadArtifactState.DOWNLOADING,
            updatedAtMs = 900L,
            leaseId = "operation-a"
        )

        assertEquals(
            ManagedDownloadArtifactFinalizationDisposition.FINALIZATION_REQUIRED,
            resolveFinalizedArtifactCompletionDisposition(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                snapshotIsComplete = true,
                matchingAudioFound = true,
                metadataIdentity = ManagedDownloadArtifactMetadataIdentity.MATCHING,
                metadataHasStrictCompletion = false
            )
        )
        assertEquals(
            ManagedDownloadArtifactDecision.InFlight,
            ManagedDownloadArtifactPolicy.decide(
                existing = recoveryArtifact,
                nowMs = 1_000L,
                staleLeaseMs = 500L,
                leaseOwnerId = "operation-b"
            )
        )
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = recoveryArtifact,
                nowMs = 1_000L,
                staleLeaseMs = 500L,
                leaseOwnerId = "operation-a"
            )
        )
    }

    @Test
    fun `active fresh lease rejects a concurrent request`() {
        assertEquals(
            ManagedDownloadArtifactDecision.InFlight,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.DOWNLOADING, 900L),
                nowMs = 1_000L,
                staleLeaseMs = 500L
            )
        )
    }

    @Test
    fun `active fresh lease can resume through its durable operation owner`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(
                    state = ManagedDownloadArtifactState.DOWNLOADING,
                    updatedAtMs = 900L,
                    leaseId = "operation-42"
                ),
                nowMs = 1_000L,
                staleLeaseMs = 500L,
                leaseOwnerId = "operation-42"
            )
        )
    }

    @Test
    fun `active fresh lease still rejects a different operation owner`() {
        assertEquals(
            ManagedDownloadArtifactDecision.InFlight,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(
                    state = ManagedDownloadArtifactState.DOWNLOADING,
                    updatedAtMs = 900L,
                    leaseId = "operation-42"
                ),
                nowMs = 1_000L,
                staleLeaseMs = 500L,
                leaseOwnerId = "operation-43"
            )
        )
    }

    @Test
    fun `storage waiting keeps its lease for the same operation after restart`() {
        val waiting = artifact(
            state = ManagedDownloadArtifactState.WAITING_STORAGE,
            updatedAtMs = 900L,
            leaseId = "operation-42"
        )

        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = waiting,
                nowMs = 1_000L,
                staleLeaseMs = 10_000L,
                leaseOwnerId = "operation-42"
            )
        )
        assertEquals(
            ManagedDownloadArtifactDecision.InFlight,
            ManagedDownloadArtifactPolicy.decide(
                existing = waiting,
                nowMs = 1_000L,
                staleLeaseMs = 10_000L,
                leaseOwnerId = "operation-43"
            )
        )
    }

    @Test
    fun `stale active lease can be reclaimed after a process crash`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.COMMITTING, 100L),
                nowMs = 1_000L,
                staleLeaseMs = 500L
            )
        )
    }

    @Test
    fun `repair state never falls through to a new network download`() {
        assertEquals(
            ManagedDownloadArtifactDecision.RepairRequired,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(
                    state = ManagedDownloadArtifactState.REPAIR_REQUIRED,
                    updatedAtMs = 100L,
                    audioReference = "content://downloads/song.mp3"
                ),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `repair state without an audio reference can acquire a fresh file`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.REPAIR_REQUIRED, 100L),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `post core state without an audio reference can acquire a fresh file`() {
        listOf(
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE
        ).forEach { state ->
            assertEquals(
                state.name,
                ManagedDownloadArtifactDecision.Acquire,
                ManagedDownloadArtifactPolicy.decide(
                    existing = artifact(state, updatedAtMs = 100L),
                    nowMs = 1_000L
                )
            )
        }
    }

    @Test
    fun `explicit retry can reclaim an unavailable post core reference without stealing another lease`() {
        assertTrue(
            shouldReclaimUnavailableArtifactForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.CORE_COMMITTED,
                referenceState = ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED,
                userInitiated = true,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-42"
            )
        )
        assertTrue(
            shouldReclaimUnavailableArtifactForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                referenceState = ManagedDownloadArtifactReferenceState.MISSING,
                userInitiated = true,
                currentLeaseId = null,
                leaseOwnerId = "operation-42"
            )
        )
        assertFalse(
            shouldReclaimUnavailableArtifactForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.CORE_COMMITTED,
                referenceState = ManagedDownloadArtifactReferenceState.PRESENT,
                userInitiated = true,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-42"
            )
        )
        assertFalse(
            shouldReclaimUnavailableArtifactForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.CORE_COMMITTED,
                referenceState = ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED,
                userInitiated = false,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-42"
            )
        )
        assertFalse(
            shouldReclaimUnavailableArtifactForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.CORE_COMMITTED,
                referenceState = ManagedDownloadArtifactReferenceState.REPAIR_REQUIRED,
                userInitiated = true,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-43"
            )
        )
    }

    @Test
    fun `explicit retry forces fresh transfer for a post core row with a present reference`() {
        assertTrue(
            shouldForceFreshTransferForUser(
                artifactState = ManagedDownloadArtifactState.CORE_COMMITTED,
                userInitiated = true,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-42"
            )
        )
        assertTrue(
            shouldForceFreshTransferForUser(
                artifactState = ManagedDownloadArtifactState.DEGRADED_COMPLETE,
                userInitiated = true,
                currentLeaseId = null,
                leaseOwnerId = "operation-42"
            )
        )
        assertFalse(
            shouldForceFreshTransferForUser(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                userInitiated = true,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-42"
            )
        )
        assertFalse(
            shouldForceFreshTransferForUser(
                artifactState = ManagedDownloadArtifactState.CORE_COMMITTED,
                userInitiated = false,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-42"
            )
        )
        assertTrue(
            shouldForceFreshTransferForUser(
                artifactState = ManagedDownloadArtifactState.CORE_COMMITTED,
                userInitiated = true,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-43"
            )
        )
        assertFalse(
            shouldForceFreshTransferForUser(
                artifactState = ManagedDownloadArtifactState.REPAIR_REQUIRED,
                userInitiated = true,
                currentLeaseId = "operation-42",
                leaseOwnerId = "operation-43"
            )
        )
    }

    @Test
    fun `explicit retry can reclaim finalized rows when completion evidence is unavailable`() {
        assertTrue(
            shouldReclaimUnavailableFinalizationForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                disposition = ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE,
                userInitiated = true,
                currentLeaseId = null,
                leaseOwnerId = "operation-42"
            )
        )
        assertFalse(
            shouldReclaimUnavailableFinalizationForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                disposition = ManagedDownloadArtifactFinalizationDisposition.FINALIZATION_REQUIRED,
                userInitiated = true,
                currentLeaseId = null,
                leaseOwnerId = "operation-42"
            )
        )
        assertFalse(
            shouldReclaimUnavailableFinalizationForFreshTransfer(
                artifactState = ManagedDownloadArtifactState.FINALIZED,
                disposition = ManagedDownloadArtifactFinalizationDisposition.UNAVAILABLE,
                userInitiated = true,
                currentLeaseId = "operation-41",
                leaseOwnerId = "operation-42"
            )
        )
    }

    @Test
    fun `confirmed missing artifact can be acquired again`() {
        assertEquals(
            ManagedDownloadArtifactDecision.Acquire,
            ManagedDownloadArtifactPolicy.decide(
                existing = artifact(ManagedDownloadArtifactState.MISSING_CONFIRMED, 100L),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `retryable write cannot downgrade a repair required artifact`() {
        assertEquals(
            ManagedDownloadArtifactState.REPAIR_REQUIRED,
            resolveArtifactStateUpdate(
                current = ManagedDownloadArtifactState.REPAIR_REQUIRED,
                requested = ManagedDownloadArtifactState.FAILED_RETRYABLE
            )
        )
    }

    @Test
    fun `cancellation releases a stale lease without downgrading committed audio`() {
        listOf(
            ManagedDownloadArtifactState.CORE_COMMITTED,
            ManagedDownloadArtifactState.ASSETS_ENRICHING,
            ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            ManagedDownloadArtifactState.FINALIZED
        ).forEach { current ->
            assertTrue(
                shouldReleaseLeaseAfterMonotonicArtifactUpdate(
                    current = current,
                    requested = ManagedDownloadArtifactState.CANCELLED,
                    clearLease = true
                )
            )
            assertEquals(
                current,
                resolveArtifactStateUpdate(
                    current = current,
                    requested = ManagedDownloadArtifactState.CANCELLED
                )
            )
        }
    }

    @Test
    fun `a non terminal update never clears a committed artifact lease`() {
        assertFalse(
            shouldReleaseLeaseAfterMonotonicArtifactUpdate(
                current = ManagedDownloadArtifactState.CORE_COMMITTED,
                requested = ManagedDownloadArtifactState.REPAIR_REQUIRED,
                clearLease = true
            )
        )
        assertFalse(
            shouldReleaseLeaseAfterMonotonicArtifactUpdate(
                current = ManagedDownloadArtifactState.CORE_COMMITTED,
                requested = ManagedDownloadArtifactState.CANCELLED,
                clearLease = false
            )
        )
    }

    @Test
    fun `missing expected lease cannot mutate an owned artifact`() {
        assertEquals(
            false,
            matchesManagedDownloadArtifactLease(
                currentLeaseId = "operation-42",
                expectedLeaseId = null
            )
        )
    }

    @Test
    fun `stale cancellation lease cannot clear a newer owner`() {
        assertEquals(
            false,
            matchesManagedDownloadArtifactLease(
                currentLeaseId = "new-operation",
                expectedLeaseId = "old-operation"
            )
        )
    }

    @Test
    fun `expected lease selects its owner instead of a stale current root row`() {
        val staleCurrent = artifact(
            state = ManagedDownloadArtifactState.DEGRADED_COMPLETE,
            updatedAtMs = 200L,
            audioReference = "content://current/song.mp3",
            leaseId = "new-operation"
        ).copy(rootKey = "current-root")
        val expectedOwner = artifact(
            state = ManagedDownloadArtifactState.CORE_COMMITTED,
            updatedAtMs = 100L,
            audioReference = "content://source/song.mp3",
            leaseId = "operation-42"
        ).copy(rootKey = "source-root")

        assertEquals(
            expectedOwner,
            selectManagedDownloadArtifactForLeaseMutation(
                current = staleCurrent,
                sameKeyArtifacts = listOf(staleCurrent, expectedOwner),
                expectedLeaseId = "operation-42"
            )
        )
    }

    @Test
    fun `missing expected lease never falls back to a different owner`() {
        val current = artifact(
            state = ManagedDownloadArtifactState.CORE_COMMITTED,
            updatedAtMs = 100L,
            leaseId = "new-operation"
        )

        assertNull(
            selectManagedDownloadArtifactForLeaseMutation(
                current = current,
                sameKeyArtifacts = listOf(current),
                expectedLeaseId = "old-operation"
            )
        )
    }

    @Test
    fun `only an acquired artifact exposes a mutation lease`() {
        val acquired = artifact(
            state = ManagedDownloadArtifactState.DOWNLOADING,
            updatedAtMs = 100L,
            leaseId = "operation-42"
        )
        val repair = artifact(
            state = ManagedDownloadArtifactState.REPAIR_REQUIRED,
            updatedAtMs = 100L,
            audioReference = "content://downloads/song.mp3"
        )

        assertEquals(
            "operation-42",
            ManagedDownloadArtifactClaim.Acquired(acquired).ownedLeaseIdOrNull()
        )
        assertNull(ManagedDownloadArtifactClaim.RepairRequired(repair).ownedLeaseIdOrNull())
        assertNull(ManagedDownloadArtifactClaim.AlreadyDownloaded(repair).ownedLeaseIdOrNull())
        assertNull(null.ownedLeaseIdOrNull())
    }

    @Test
    fun `final publication accepts owned or lease-free artifacts but not another owner`() {
        val owned = artifact(
            state = ManagedDownloadArtifactState.DOWNLOADING,
            updatedAtMs = 100L,
            leaseId = "operation-42"
        )
        val leaseFree = artifact(
            state = ManagedDownloadArtifactState.CORE_COMMITTED,
            updatedAtMs = 200L,
            audioReference = "content://downloads/song.mp3"
        ).copy(leaseId = null)

        assertEquals(
            "operation-42",
            ManagedDownloadArtifactClaim.Acquired(owned)
                .finalizedPublicationLeaseOrNull()
                ?.leaseId
        )
        val leaseFreePublication = ManagedDownloadArtifactClaim.AlreadyDownloaded(leaseFree)
            .finalizedPublicationLeaseOrNull()
        assertNotNull(leaseFreePublication)
        assertNull(leaseFreePublication?.leaseId)
        assertNull(
            ManagedDownloadArtifactClaim.InFlight(owned)
                .finalizedPublicationLeaseOrNull()
        )
    }

    @Test
    fun `lease-free state accepts a lease-free transition`() {
        assertEquals(
            true,
            matchesManagedDownloadArtifactLease(
                currentLeaseId = null,
                expectedLeaseId = null
            )
        )
    }

    @Test
    fun `lease-free update cannot overwrite an active owned artifact`() {
        assertEquals(
            false,
            canApplyLeaseFreeArtifactTransition(
                currentState = ManagedDownloadArtifactState.DOWNLOADING,
                currentLeaseId = "operation-42",
                requestedState = ManagedDownloadArtifactState.MISSING_CONFIRMED
            )
        )
    }

    @Test
    fun `lease-free update cannot downgrade an unowned active transfer`() {
        assertEquals(
            false,
            canApplyLeaseFreeArtifactTransition(
                currentState = ManagedDownloadArtifactState.COMMITTING,
                currentLeaseId = null,
                requestedState = ManagedDownloadArtifactState.MISSING_CONFIRMED
            )
        )
    }

    @Test
    fun `post core enrichment transitions remain lease free`() {
        assertEquals(
            true,
            canApplyLeaseFreeArtifactTransition(
                currentState = ManagedDownloadArtifactState.CORE_COMMITTED,
                currentLeaseId = null,
                requestedState = ManagedDownloadArtifactState.ASSETS_ENRICHING
            )
        )
        assertEquals(
            true,
            canApplyLeaseFreeArtifactTransition(
                currentState = ManagedDownloadArtifactState.ASSETS_ENRICHING,
                currentLeaseId = null,
                requestedState = ManagedDownloadArtifactState.DEGRADED_COMPLETE
            )
        )
    }

    private fun artifact(
        state: ManagedDownloadArtifactState,
        updatedAtMs: Long,
        audioReference: String? = null,
        leaseId: String = "lease"
    ): ManagedDownloadArtifactEntity {
        return ManagedDownloadArtifactEntity(
            rootKey = "root",
            stableKey = "netease|1|",
            artifactId = "managed:root:netease|1|",
            state = state.name,
            leaseId = leaseId,
            audioReference = audioReference,
            audioName = null,
            fileSize = null,
            contentHash = null,
            libraryAddedAtMs = null,
            sourceCreatedAtMs = null,
            sourceModifiedAtMs = null,
            downloadedAtMs = null,
            migratedAtMs = null,
            finalizedAtMs = null,
            updatedAtMs = updatedAtMs,
            needsReconcile = state != ManagedDownloadArtifactState.FINALIZED,
            lastErrorCode = null
        )
    }
}
