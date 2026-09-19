package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.download.model.shouldHandoffBlockedWifiRecoveryToSharedPump
import moe.ouom.neriplayer.core.download.policy.DOWNLOAD_CLEAR_MAX_CONVERGENCE_ROUNDS
import moe.ouom.neriplayer.core.download.policy.DownloadClearProviderCleanupCoordinator
import moe.ouom.neriplayer.core.download.policy.DownloadClearRoomTimeoutException
import moe.ouom.neriplayer.core.download.policy.DownloadedSongReferenceCoverage
import moe.ouom.neriplayer.core.download.policy.TerminalTemporaryWriteCleanupBatch
import moe.ouom.neriplayer.core.download.policy.awaitBatchDownloadJobsSettled
import moe.ouom.neriplayer.core.download.policy.awaitDownloadClearProviderCleanup
import moe.ouom.neriplayer.core.download.policy.cancellationConvergenceDelayMs
import moe.ouom.neriplayer.core.download.policy.finalizedTemporaryWriteTargetNames
import moe.ouom.neriplayer.core.download.policy.hasDownloadClearExceededDeadline
import moe.ouom.neriplayer.core.download.policy.nextDownloadOperationCreatedAtMs
import moe.ouom.neriplayer.core.download.policy.observeDownloadedSongReferencesFromSnapshot
import moe.ouom.neriplayer.core.download.policy.partitionForBoundedParallelism
import moe.ouom.neriplayer.core.download.policy.resolveDownloadClearRetainedTotalItemCount
import moe.ouom.neriplayer.core.download.policy.resolvePostCoreEnrichmentTaskStatus
import moe.ouom.neriplayer.core.download.policy.resolveRestorableCoverReference
import moe.ouom.neriplayer.core.download.policy.shouldApplyDownloadedPlaybackHydration
import moe.ouom.neriplayer.core.download.policy.shouldBlockDownloadClearForPendingArtifacts
import moe.ouom.neriplayer.core.download.policy.shouldContinueWifiRecoveryProbe
import moe.ouom.neriplayer.core.download.policy.shouldDeferDownloadClearAfterConvergenceRound
import moe.ouom.neriplayer.core.download.policy.shouldFinalizeDownloadedSidecars
import moe.ouom.neriplayer.core.download.policy.shouldPersistDownloadClearProgress
import moe.ouom.neriplayer.core.download.policy.shouldPurgeCancelledDownloadOperation
import moe.ouom.neriplayer.core.download.policy.shouldRebuildDownloadedLibrarySnapshot
import moe.ouom.neriplayer.core.download.policy.shouldRetainDownloadClearVisibility
import moe.ouom.neriplayer.core.download.policy.shouldRetainUnresolvedCancellationSnapshot
import moe.ouom.neriplayer.core.download.policy.shouldScheduleCancellationConvergence
import moe.ouom.neriplayer.core.download.policy.shouldSchedulePostCoreEnrichmentRetry
import moe.ouom.neriplayer.core.download.policy.withDownloadClearRoomTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import moe.ouom.neriplayer.core.download.policy.recoveryOperationIdsForKeys
import moe.ouom.neriplayer.core.download.policy.shouldRecoverDownloadCandidateWithBatch
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.SongItem


class GlobalDownloadManagerStartupPolicyTest : GlobalDownloadManagerStartupPolicyTestSupport() {

    @Test
    fun `cancellation convergence uses bounded monotonic backoff`() {
        assertEquals(
            listOf(0L, 150L, 300L, 1_000L, 2_000L, null),
            listOf(1, 2, 3, 5, 7, 8).map(::cancellationConvergenceDelayMs)
        )
    }

    @Test
    fun `replacement operation timestamp is strictly after every cancellation cutoff`() {
        assertEquals(
            1_001L,
            nextDownloadOperationCreatedAtMs(
                requestedAtMs = 1_000L,
                cancellationCutoffs = listOf(1_000L)
            )
        )
        assertEquals(
            2_001L,
            nextDownloadOperationCreatedAtMs(
                requestedAtMs = 1_500L,
                cancellationCutoffs = listOf(1_000L, 2_000L)
            )
        )
        assertEquals(
            1_500L,
            nextDownloadOperationCreatedAtMs(
                requestedAtMs = 1_500L,
                cancellationCutoffs = emptyList()
            )
        )
    }

    @Test
    fun `unresolved cancellation snapshot cannot fall back to stable key cleanup`() {
        assertTrue(
            shouldRetainUnresolvedCancellationSnapshot(
                snapshotBoundary = true,
                snapshotResolved = false,
                operationIds = emptySet()
            )
        )
        assertFalse(
            shouldRetainUnresolvedCancellationSnapshot(
                snapshotBoundary = true,
                snapshotResolved = true,
                operationIds = emptySet()
            )
        )
        assertFalse(
            shouldRetainUnresolvedCancellationSnapshot(
                snapshotBoundary = true,
                snapshotResolved = false,
                operationIds = setOf("old-operation")
            )
        )
        assertFalse(
            shouldRetainUnresolvedCancellationSnapshot(
                snapshotBoundary = false,
                snapshotResolved = false,
                operationIds = emptySet()
            )
        )
    }

    @Test
    fun `empty resolved cancellation snapshot still schedules convergence`() {
        assertTrue(
            shouldScheduleCancellationConvergence(
                operationIds = emptySet(),
                snapshotBoundary = true
            )
        )
        assertFalse(
            shouldScheduleCancellationConvergence(
                operationIds = emptySet(),
                snapshotBoundary = false
            )
        )
        assertTrue(
            shouldScheduleCancellationConvergence(
                operationIds = setOf("old-operation"),
                snapshotBoundary = false
            )
        )
    }

    @Test
    fun `only recovery with a working file enters batch`() {
        val key = "song-recovery"
        val antiJoined = setOf(key)

        assertFalse(
            shouldRecoverDownloadCandidateWithBatch(
                songKey = key,
                antiJoinedKeys = antiJoined,
                hasWorkingFile = false
            )
        )
        assertFalse(
            shouldRecoverDownloadCandidateWithBatch(
                songKey = key,
                antiJoinedKeys = antiJoined,
                hasWorkingFile = false
            )
        )
        assertTrue(
            shouldRecoverDownloadCandidateWithBatch(
                songKey = key,
                antiJoinedKeys = antiJoined,
                hasWorkingFile = true
            )
        )
    }

    @Test
    fun `clear hard deadline is inclusive and unknown timestamps are not expired`() {
        assertFalse(
            hasDownloadClearExceededDeadline(
                requestedAtMs = 1_000L,
                nowMs = 3_999L
            )
        )
        assertTrue(
            hasDownloadClearExceededDeadline(
                requestedAtMs = 1_000L,
                nowMs = 4_000L
            )
        )
        assertFalse(
            hasDownloadClearExceededDeadline(
                requestedAtMs = null,
                nowMs = 9_000L
            )
        )
    }

    @Test
    fun `clear progress persistence is throttled but flushes boundaries`() {
        assertTrue(
            shouldPersistDownloadClearProgress(
                completedItemCount = 0,
                totalItemCount = 100,
                lastPersistedItemCount = -1,
                nowMs = 0L,
                lastPersistedAtMs = Long.MIN_VALUE,
                minIntervalMs = 500L,
                batchSize = 32
            )
        )
        assertFalse(
            shouldPersistDownloadClearProgress(
                completedItemCount = 4,
                totalItemCount = 100,
                lastPersistedItemCount = 4,
                nowMs = 100L,
                lastPersistedAtMs = 0L,
                minIntervalMs = 500L,
                batchSize = 32
            )
        )
        assertTrue(
            shouldPersistDownloadClearProgress(
                completedItemCount = 36,
                totalItemCount = 100,
                lastPersistedItemCount = 4,
                nowMs = 100L,
                lastPersistedAtMs = 0L,
                minIntervalMs = 500L,
                batchSize = 32
            )
        )
        assertFalse(
            shouldPersistDownloadClearProgress(
                completedItemCount = 0,
                totalItemCount = 100,
                lastPersistedItemCount = 0,
                nowMs = 100L,
                lastPersistedAtMs = 0L,
                minIntervalMs = 500L,
                batchSize = 32
            )
        )
        assertTrue(
            shouldPersistDownloadClearProgress(
                completedItemCount = 5,
                totalItemCount = 100,
                lastPersistedItemCount = 4,
                nowMs = 500L,
                lastPersistedAtMs = 0L,
                minIntervalMs = 500L,
                batchSize = 32
            )
        )
        assertTrue(
            shouldPersistDownloadClearProgress(
                completedItemCount = 100,
                totalItemCount = 100,
                lastPersistedItemCount = 68,
                nowMs = 100L,
                lastPersistedAtMs = 0L,
                minIntervalMs = 500L,
                batchSize = 32
            )
        )
    }

    @Test
    fun `complete pending scan replaces stale task total`() {
        assertEquals(
            50,
            resolveDownloadClearRetainedTotalItemCount(
                currentTotalItemCount = 696,
                artifactTotalItemCount = 50,
                scanComplete = true
            )
        )
    }

    @Test
    fun `incomplete pending scan retains the larger durable watermark`() {
        assertEquals(
            696,
            resolveDownloadClearRetainedTotalItemCount(
                currentTotalItemCount = 696,
                artifactTotalItemCount = 50,
                scanComplete = false
            )
        )
        assertEquals(
            50,
            resolveDownloadClearRetainedTotalItemCount(
                currentTotalItemCount = null,
                artifactTotalItemCount = 50,
                scanComplete = false
            )
        )
    }

    @Test
    fun `protected pending artifacts do not block a complete clear`() {
        assertFalse(
            shouldBlockDownloadClearForPendingArtifacts(
                scanComplete = true,
                blockingArtifactCount = 0
            )
        )
        assertTrue(
            shouldBlockDownloadClearForPendingArtifacts(
                scanComplete = true,
                blockingArtifactCount = 1
            )
        )
        assertTrue(
            shouldBlockDownloadClearForPendingArtifacts(
                scanComplete = false,
                blockingArtifactCount = 0
            )
        )
    }

    @Test
    fun `clear visibility remains while durable cleanup still needs recovery`() {
        assertTrue(
            shouldRetainDownloadClearVisibility(
                retainInMemoryState = true,
                durableFenceActive = false
            )
        )
        assertTrue(
            shouldRetainDownloadClearVisibility(
                retainInMemoryState = false,
                durableFenceActive = true
            )
        )
        assertFalse(
            shouldRetainDownloadClearVisibility(
                retainInMemoryState = false,
                durableFenceActive = false
            )
        )
    }

    @Test
    fun `clear convergence defers only after its bounded round budget`() {
        assertFalse(
            shouldDeferDownloadClearAfterConvergenceRound(
                round = DOWNLOAD_CLEAR_MAX_CONVERGENCE_ROUNDS - 1
            )
        )
        assertTrue(
            shouldDeferDownloadClearAfterConvergenceRound(
                round = DOWNLOAD_CLEAR_MAX_CONVERGENCE_ROUNDS
            )
        )
        assertTrue(
            shouldDeferDownloadClearAfterConvergenceRound(
                round = DOWNLOAD_CLEAR_MAX_CONVERGENCE_ROUNDS + 1
            )
        )
    }

    @Test
    fun `clear Room timeout distinguishes a null query result from a blocked query`() = runBlocking {
        val absentState: String? = withDownloadClearRoomTimeout(
            operation = "read absent operation",
            timeoutMs = 50L
        ) {
            null
        }
        assertNull(absentState)

        val error = runCatching {
            withDownloadClearRoomTimeout(
                operation = "read blocked operation",
                timeoutMs = 20L
            ) {
                delay(100L)
                "unreachable"
            }
        }.exceptionOrNull()

        assertTrue(error is DownloadClearRoomTimeoutException)
    }

    @Test
    fun `provider cleanup wait timeout leaves the shared cleanup active`() = runBlocking {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val coordinator = DownloadClearProviderCleanupCoordinator<Long, String>(cleanupScope)
            val handle = coordinator.getOrStart(key = 1L) {
                started.complete(Unit)
                release.await()
                "settled"
            }
            started.await()

            assertNull(
                awaitDownloadClearProviderCleanup(
                    cleanup = handle.operation,
                    timeoutMs = 20L
                )
            )
            assertTrue(handle.operation.isActive)
            assertTrue(coordinator.activeOrNull()?.operation === handle.operation)

            release.complete(Unit)
            assertEquals("settled", handle.operation.await())
            val resumed = coordinator.getOrStart(key = 1L) {
                error("completed cleanup must not run again before acknowledgement")
            }
            assertTrue(resumed === handle)
            assertEquals("settled", awaitDownloadClearProviderCleanup(resumed.operation, 20L))
            coordinator.acknowledge(resumed)
            assertNull(coordinator.activeOrNull())
        } finally {
            cleanupScope.cancel()
        }
    }

    @Test
    fun `provider cleanup serializes a later recovery until the current cleanup completes`() = runBlocking {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondCleanupStarted = false
        try {
            val coordinator = DownloadClearProviderCleanupCoordinator<Long, String>(cleanupScope)
            val first = coordinator.getOrStart(key = 1L) {
                started.complete(Unit)
                release.await()
                "first"
            }
            started.await()

            val coalesced = coordinator.getOrStart(key = 2L) {
                secondCleanupStarted = true
                "second"
            }
            assertEquals(1L, coalesced.key)
            assertTrue(coalesced.operation === first.operation)
            assertFalse(secondCleanupStarted)

            release.complete(Unit)
            assertEquals("first", first.operation.await())

            val second = coordinator.getOrStart(key = 2L) {
                secondCleanupStarted = true
                "second"
            }
            assertEquals(2L, second.key)
            assertEquals("second", second.operation.await())
            assertTrue(secondCleanupStarted)
        } finally {
            cleanupScope.cancel()
        }
    }

    @Test
    fun `empty scan coverage reuses snapshot references instead of probing each song`() {
        val audio = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "content://downloads/song.mp3",
            mediaUri = "content://downloads/song.mp3",
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 10L
        )
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntries = listOf(audio),
            pendingAudioEntries = emptyList()
        )
        val existingSongs = listOf(
            DownloadedSong(
                id = 1L,
                name = "song",
                artist = "artist",
                album = "album",
                filePath = "/stale/path/song.mp3",
                fileSize = 1L,
                downloadTime = 10L,
                mediaUri = audio.mediaUri
            ),
            DownloadedSong(
                id = 2L,
                name = "missing",
                artist = "artist",
                album = "album",
                filePath = "content://downloads/missing.mp3",
                fileSize = 1L,
                downloadTime = 9L
            )
        )

        assertEquals(
            DownloadedSongReferenceCoverage(
                knownReferenceCount = 2,
                missingReferenceCount = 1
            ),
            observeDownloadedSongReferencesFromSnapshot(existingSongs, snapshot)
        )
    }

    @Test
    fun `large refresh plan is partitioned without creating one deferred per song`() {
        val items = (0 until 1_000).toList()
        val batches = partitionForBoundedParallelism(items, maxParallelism = 4)

        assertEquals(250, batches.size)
        assertTrue(batches.all { batch -> batch.size in 1..4 })
        assertEquals(items, batches.flatten())
    }

    @Test
    fun `wifi recovery probe retries only while wifi and candidates remain`() {
        assertTrue(
            shouldContinueWifiRecoveryProbe(
                networkType = TrafficNetworkType.WIFI,
                hasPendingCandidates = true,
                attempt = 0,
                maxAttempts = 6
            )
        )
        assertFalse(
            shouldContinueWifiRecoveryProbe(
                networkType = TrafficNetworkType.MOBILE,
                hasPendingCandidates = true,
                attempt = 0,
                maxAttempts = 6
            )
        )
        assertFalse(
            shouldContinueWifiRecoveryProbe(
                networkType = TrafficNetworkType.WIFI,
                hasPendingCandidates = false,
                attempt = 0,
                maxAttempts = 6
            )
        )
        assertFalse(
            shouldContinueWifiRecoveryProbe(
                networkType = TrafficNetworkType.WIFI,
                hasPendingCandidates = true,
                attempt = 5,
                maxAttempts = 6
            )
        )
    }

    @Test
    fun `cancelled operation is purged only after root cleanup succeeds`() {
        assertTrue(
            shouldPurgeCancelledDownloadOperation(
                keepCancellationOperation = false,
                cleanupSucceeded = true
            )
        )
        assertFalse(
            shouldPurgeCancelledDownloadOperation(
                keepCancellationOperation = false,
                cleanupSucceeded = false
            )
        )
        assertFalse(
            shouldPurgeCancelledDownloadOperation(
                keepCancellationOperation = true,
                cleanupSucceeded = true
            )
        )
    }

    @Test
    fun `finalized temporary cleanup keeps audio and both metadata targets together`() {
        assertEquals(
            listOf(
                "song.mp3",
                "song.mp3.npmeta.json",
                "song.mp3.npmeta.pending.json"
            ),
            finalizedTemporaryWriteTargetNames(" song.mp3 ")
        )
        assertTrue(finalizedTemporaryWriteTargetNames(" ").isEmpty())
    }

    @Test
    fun `terminal temporary cleanup batch coalesces valid targets without duplicates`() {
        val batch = TerminalTemporaryWriteCleanupBatch()

        batch.addAll(listOf(" song.mp3 ", "", "song.mp3", "song.mp3.npmeta.json"))
        assertEquals(
            listOf("song.mp3", "song.mp3.npmeta.json"),
            batch.takeAll()
        )
        assertTrue(batch.isEmpty())

        batch.addAll(listOf("song.mp3.npmeta.pending.json"))
        assertEquals(listOf("song.mp3.npmeta.pending.json"), batch.takeAll())
    }

    @Test
    fun `wifi restoration invalidates stale wifi-bound pause work`() {
        assertTrue(
            isWifiBoundNetworkPolicyObservationCurrent(
                snapshotEpoch = 8L,
                currentEpoch = 8L,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
        assertTrue(
            isWifiBoundNetworkPolicyObservationCurrent(
                snapshotEpoch = 8L,
                currentEpoch = 8L,
                currentNetworkType = TrafficNetworkType.ROAMING
            )
        )
        assertFalse(
            isWifiBoundNetworkPolicyObservationCurrent(
                snapshotEpoch = 8L,
                currentEpoch = 8L,
                currentNetworkType = TrafficNetworkType.WIFI
            )
        )
        assertFalse(
            isWifiBoundNetworkPolicyObservationCurrent(
                snapshotEpoch = 8L,
                currentEpoch = 9L,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
    }

    @Test
    fun `restorable cover reuses verified short name before legacy hash lookup`() = runBlocking {
        val shortReference = "content://downloads/Covers/Artist-Song-12345678.jpg"
        val assetHash = "a".repeat(64)
        var legacyLookupCalled = false
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "1|netease|",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                coverReference = "https://example.com/original.jpg"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            baselineCoverAssetHash = assetHash,
            baselineCoverAssetFileName = "Artist-Song-12345678.jpg"
        )

        val resolved = resolveRestorableCoverReference(
            metadata = metadata,
            baseline = true,
            fingerprintReference = { reference ->
                if (reference == shortReference) {
                    ManagedDownloadCoverAssetStore.MaterializedCover(
                        reference = reference,
                        assetHash = assetHash,
                        fileName = "Artist-Song-12345678.jpg"
                    )
                } else {
                    null
                }
            },
            findManagedReferenceByName = { shortReference },
            findContentAddressedReference = {
                legacyLookupCalled = true
                null
            }
        )

        assertEquals(shortReference, resolved)
        assertFalse(legacyLookupCalled)
    }

    @Test
    fun `restorable cover retains legacy pure sha lookup fallback`() = runBlocking {
        val assetHash = "b".repeat(64)
        val pureHashReference = "/downloads/Covers/$assetHash.jpg"
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "1|netease|",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                coverReference = "https://example.com/original.jpg"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            baselineCoverAssetHash = assetHash
        )

        val resolved = resolveRestorableCoverReference(
            metadata = metadata,
            baseline = true,
            fingerprintReference = { reference ->
                ManagedDownloadCoverAssetStore.MaterializedCover(
                    reference = reference,
                    assetHash = assetHash
                ).takeIf { reference == pureHashReference }
            },
            findManagedReferenceByName = { null },
            findContentAddressedReference = { hash ->
                pureHashReference.takeIf { hash == assetHash }
            }
        )

        assertEquals(pureHashReference, resolved)
    }

    @Test
    fun `corrupted legacy pure sha cover falls back to source`() = runBlocking {
        val assetHash = "b".repeat(64)
        val sourceReference = "https://example.com/original.jpg"
        val pureHashReference = "/downloads/Covers/$assetHash.jpg"
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "1|netease|",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                coverReference = sourceReference
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            baselineCoverAssetHash = assetHash
        )

        val resolved = resolveRestorableCoverReference(
            metadata = metadata,
            baseline = true,
            fingerprintReference = { reference ->
                ManagedDownloadCoverAssetStore.MaterializedCover(
                    reference = reference,
                    assetHash = "c".repeat(64)
                ).takeIf { reference == pureHashReference }
            },
            findManagedReferenceByName = { null },
            findContentAddressedReference = { pureHashReference }
        )

        assertEquals(sourceReference, resolved)
    }

    @Test
    fun `baseline falls back to source after its short file is overwritten`() = runBlocking {
        val sourceReference = "https://example.com/original.jpg"
        val shortReference = "content://downloads/Covers/Artist-Song-12345678.jpg"
        val baselineHash = "b".repeat(64)
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "1|netease|",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                coverReference = sourceReference
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            baselineCoverAssetHash = baselineHash,
            currentCoverAssetHash = "c".repeat(64),
            baselineCoverAssetFileName = "Artist-Song-12345678.jpg",
            currentCoverAssetFileName = "Artist-Song-12345678.jpg"
        )

        val resolved = resolveRestorableCoverReference(
            metadata = metadata,
            baseline = true,
            fingerprintReference = { reference ->
                if (reference == shortReference) {
                    ManagedDownloadCoverAssetStore.MaterializedCover(
                        reference = reference,
                        assetHash = "c".repeat(64)
                    )
                } else {
                    null
                }
            },
            findManagedReferenceByName = { shortReference },
            findContentAddressedReference = { null }
        )

        assertEquals(sourceReference, resolved)
    }

    @Test
    fun `SAF permission loss on stale reference still resolves the refreshed short file`() = runBlocking {
        val staleReference = "content://old-root/Covers/Artist-Song-12345678.jpg"
        val refreshedReference = "content://new-root/Covers/Artist-Song-12345678.jpg"
        val assetHash = "d".repeat(64)
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "1|netease|",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                coverReference = staleReference
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides(),
            baselineCoverAssetHash = assetHash,
            baselineCoverAssetFileName = "Artist-Song-12345678.jpg"
        )

        val resolved = resolveRestorableCoverReference(
            metadata = metadata,
            baseline = true,
            fingerprintReference = { reference ->
                if (reference == staleReference) {
                    throw SecurityException("permission lost")
                }
                ManagedDownloadCoverAssetStore.MaterializedCover(
                    reference = reference,
                    assetHash = assetHash
                )
            },
            findManagedReferenceByName = { refreshedReference },
            findContentAddressedReference = { null }
        )

        assertEquals(refreshedReference, resolved)
    }

    @Test
    fun `existing unfinalized audio selects finalization only`() {
        assertEquals(
            PreExistingDownloadedAudioAction.FINALIZE_EXISTING,
            resolvePreExistingDownloadedAudioAction(
                hasExistingAudio = true,
                needsFinalization = true
            )
        )
        assertEquals(
            PreExistingDownloadedAudioAction.DIRECT_SETTLE,
            resolvePreExistingDownloadedAudioAction(
                hasExistingAudio = true,
                needsFinalization = false
            )
        )
    }

    @Test
    fun `download playback hydration survives local reference normalization`() {
        val quickSong = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://downloads/audio/1",
            localFileName = "song.mp3",
            localFilePath = "/storage/emulated/0/neriplayer-download/song.mp3"
        )
        val normalizedSong = quickSong.copy(
            mediaUri = "content://downloads/audio/2"
        )

        assertFalse(quickSong.stableKey() == normalizedSong.stableKey())
        assertTrue(
            shouldApplyDownloadedPlaybackHydration(
                currentSong = normalizedSong,
                quickSong = quickSong
            )
        )
        assertFalse(
            shouldApplyDownloadedPlaybackHydration(
                currentSong = normalizedSong.copy(
                    mediaUri = "content://downloads/audio/3",
                    localFilePath = null
                ),
                quickSong = quickSong
            )
        )
    }

    @Test
    fun `unfinalized recovery only rebuilds a snapshot after deleting an artifact`() {
        assertFalse(shouldRebuildDownloadedLibrarySnapshot(recoveredArtifactCount = 0))
        assertTrue(shouldRebuildDownloadedLibrarySnapshot(recoveredArtifactCount = 1))
    }

    @Test
    fun `download with a network cover cannot finalize without an accessible sidecar`() {
        assertFalse(
            shouldFinalizeDownloadedSidecars(
                hasNetworkCoverCandidate = true,
                coverReference = null,
                coverAccessible = false
            )
        )
        assertFalse(
            shouldFinalizeDownloadedSidecars(
                hasNetworkCoverCandidate = true,
                coverReference = "content://downloads/cover.jpg",
                coverAccessible = false
            )
        )
        assertTrue(
            shouldFinalizeDownloadedSidecars(
                hasNetworkCoverCandidate = true,
                coverReference = "content://downloads/cover.jpg",
                coverAccessible = true
            )
        )
        assertTrue(
            shouldFinalizeDownloadedSidecars(
                hasNetworkCoverCandidate = false,
                coverReference = null,
                coverAccessible = false
            )
        )
    }

    @Test
    fun `missing network cover cannot finalize as a degraded complete item`() {
        assertFalse(
            shouldFinalizeDownloadedSidecars(
                hasNetworkCoverCandidate = true,
                coverReference = null,
                coverAccessible = false
            )
        )
    }

    @Test
    fun `post core enrichment failure remains active when audio is committed`() {
        assertEquals(
            DownloadStatus.QUEUED,
            resolvePostCoreEnrichmentTaskStatus(coreAudioCommitted = true)
        )
        assertEquals(
            DownloadStatus.FAILED,
            resolvePostCoreEnrichmentTaskStatus(coreAudioCommitted = false)
        )
    }

    @Test
    fun `degraded core retry skips explicit metadata action and stopped operations`() {
        assertTrue(
            shouldSchedulePostCoreEnrichmentRetry(
                coreAudioCommitted = true,
                operationState = "DEGRADED_COMPLETE",
                metadataActionRequired = false,
                userStopped = false
            )
        )
        assertFalse(
            shouldSchedulePostCoreEnrichmentRetry(
                coreAudioCommitted = true,
                operationState = "DEGRADED_COMPLETE",
                metadataActionRequired = true,
                userStopped = false
            )
        )
        assertFalse(
            shouldSchedulePostCoreEnrichmentRetry(
                coreAudioCommitted = true,
                operationState = "DEGRADED_COMPLETE",
                metadataActionRequired = false,
                userStopped = true
            )
        )
        assertFalse(
            shouldSchedulePostCoreEnrichmentRetry(
                coreAudioCommitted = true,
                operationState = "ASSETS_ENRICHING",
                metadataActionRequired = false,
                userStopped = false
            )
        )
        assertTrue(
            shouldSchedulePostCoreEnrichmentRetry(
                coreAudioCommitted = true,
                operationState = "ASSETS_ENRICHING",
                metadataActionRequired = false,
                userStopped = false,
                allowInFlightState = true
            )
        )
        assertFalse(
            shouldSchedulePostCoreEnrichmentRetry(
                coreAudioCommitted = true,
                operationState = "ASSETS_ENRICHING",
                metadataActionRequired = false,
                userStopped = false,
                allowInFlightState = true,
                songCancelled = true
            )
        )
        // 全局取消标志只属于当前传输代次，收尾重试由清空栅栏和单曲取消状态控制
        assertTrue(
            shouldSchedulePostCoreEnrichmentRetry(
                coreAudioCommitted = true,
                operationState = "DEGRADED_COMPLETE",
                metadataActionRequired = false,
                userStopped = false
            )
        )
    }

    @Test
    fun `runNonCancellableDownloadRollback still completes after coroutine cancellation`() = runBlocking {
        var executed = false
        var rollbackResult: String? = null

        val job = launch {
            cancel(CancellationException("cancel all download tasks"))
            rollbackResult = runNonCancellableDownloadRollback {
                delay(1)
                executed = true
                "rolled-back"
            }
        }

        job.join()

        assertTrue(executed)
        assertEquals("rolled-back", rollbackResult)
    }

    @Test
    fun `batch cancellation wait stops waiting at its fixed budget without cancelling cleanup`() = runBlocking {
        val completed = launch { }
        completed.join()
        assertTrue(awaitBatchDownloadJobsSettled(listOf(completed), timeoutMs = 50L))

        val blocker = CompletableDeferred<Unit>()
        val waiting = launch { blocker.await() }
        try {
            assertFalse(awaitBatchDownloadJobsSettled(listOf(waiting), timeoutMs = 50L))
            assertTrue(waiting.isActive)
        } finally {
            blocker.complete(Unit)
            waiting.join()
        }
    }

    @Test
    fun `blocked wifi recovery hands durable candidates to the shared pump`() {
        assertTrue(
            shouldHandoffBlockedWifiRecoveryToSharedPump(
                hasPendingCandidates = true,
                hasBlockingActiveOperations = true
            )
        )
        assertFalse(
            shouldHandoffBlockedWifiRecoveryToSharedPump(
                hasPendingCandidates = false,
                hasBlockingActiveOperations = true
            )
        )
        assertFalse(
            shouldHandoffBlockedWifiRecoveryToSharedPump(
                hasPendingCandidates = true,
                hasBlockingActiveOperations = false
            )
        )
    }

    @Test
    fun `startup reconciliation still runs when lightweight catalog is ready`() {
        assertEquals(true, shouldRunInitialDownloadScan(catalogReady = true))
        assertEquals(true, shouldRunInitialDownloadScan(catalogReady = false))
        assertEquals(
            true,
            shouldRunInitialDownloadScan(
                catalogReady = true,
                hasRecoveredEntries = true
            )
        )
    }

    @Test
    fun `legacy upgrade remains visible until a rebuilt catalog is published`() {
        assertTrue(
            GlobalDownloadManager.shouldCompleteProcessingAfterCatalogPublish(
                ManagedLibraryProcessingState.Running(
                    operationId = "legacy",
                    reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
                    phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
                )
            )
        )
        assertFalse(
            GlobalDownloadManager.shouldCompleteProcessingAfterCatalogPublish(
                ManagedLibraryProcessingState.Running(
                    operationId = "legacy",
                    reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
                    phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE
                )
            )
        )
        assertFalse(
            GlobalDownloadManager.shouldCompleteProcessingAfterCatalogPublish(
                ManagedLibraryProcessingState.WaitingForRetry(
                    operationId = "legacy",
                    reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
                    phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE
                )
            )
        )
    }

    @Test
    fun `empty scan is suspicious only when existing catalog is non-empty and root resolvable`() {
        // #D4: 同 root 下 SAF 列举瞬时失败返回空, 既有目录非空且存储根可解析时判为可疑, 不覆盖既有目录
        assertTrue(
            isSuspiciousEmptyDownloadScan(
                scannedSongCount = 0,
                existingSongCount = 3,
                storageRootResolvable = true,
                scanMatchesCatalogRoot = true
            )
        )
        // 存储根不可解析 (权限丢失/目录被移除->回退空目录) 属于可解释的空, 放行
        assertFalse(
            isSuspiciousEmptyDownloadScan(
                scannedSongCount = 0,
                existingSongCount = 3,
                storageRootResolvable = false,
                scanMatchesCatalogRoot = true
            )
        )
        // 既有目录本就为空, 没有需要保护的内容, 放行 (不会误伤真正的空目录)
        assertFalse(
            isSuspiciousEmptyDownloadScan(
                scannedSongCount = 0,
                existingSongCount = 0,
                storageRootResolvable = true,
                scanMatchesCatalogRoot = true
            )
        )
        // 扫描结果非空属于正常更新, 不判为可疑
        assertFalse(
            isSuspiciousEmptyDownloadScan(
                scannedSongCount = 2,
                existingSongCount = 3,
                storageRootResolvable = true,
                scanMatchesCatalogRoot = true
            )
        )
    }

    @Test
    fun `directory switch to empty is not suspicious so stale catalog is cleared`() {
        // H1 回归 (场景 1) : 切换/重置下载目录到空目录后, 扫描的是新 root
        // 而既有 catalog 属于旧 root (scanMatchesCatalogRoot=false) ; 即使存储根可解析, 既有目录非空
        // 也不得判为可疑 -- 应放行清空, 避免继续展示旧目录陈旧条目, 且 app 内刷新可自愈
        assertFalse(
            isSuspiciousEmptyDownloadScan(
                scannedSongCount = 0,
                existingSongCount = 3,
                storageRootResolvable = true,
                scanMatchesCatalogRoot = false
            )
        )
    }

    @Test
    fun `same directory transient empty scan stays protected`() {
        // H1 回归 (场景 2) : 同一下载目录 (scanMatchesCatalogRoot=true) 下的瞬时空列举失败仍受 #D4 保护
        // 判为可疑并保留既有目录, 确保修复 H1 不会削弱对瞬时失败的防护
        assertTrue(
            isSuspiciousEmptyDownloadScan(
                scannedSongCount = 0,
                existingSongCount = 5,
                storageRootResolvable = true,
                scanMatchesCatalogRoot = true
            )
        )
    }

    @Test
    fun `startup managed cleanup is deferred only for available SAF trees`() {
        assertTrue(
            shouldDeferStartupManagedCleanup(
                configuredDirectoryUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                treeRootAvailable = true
            )
        )
        assertFalse(
            shouldDeferStartupManagedCleanup(
                configuredDirectoryUri = null,
                treeRootAvailable = true
            )
        )
        assertFalse(
            shouldDeferStartupManagedCleanup(
                configuredDirectoryUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                treeRootAvailable = false
            )
        )
    }
}
