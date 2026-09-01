package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.SongItem

class GlobalDownloadManagerStartupPolicyTest {

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
    fun `durable clear retries are bounded before a later recovery`() {
        assertFalse(
            shouldDeferDownloadClearAfterDurableRetry(
                round = DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS - 1
            )
        )
        assertTrue(
            shouldDeferDownloadClearAfterDurableRetry(
                round = DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS
            )
        )
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val releaseBody = source.substringAfter(
            "private suspend fun clearDownloadClearFence"
        ).substringBefore("private suspend fun requestAllDownloadOperationCancellation")
        val cancellationBody = source.substringAfter(
            "private suspend fun requestAllDownloadOperationCancellation"
        ).substringBefore("fun interruptDownloadsForWifiDisconnected")
        assertFalse(releaseBody.contains("while (true)"))
        assertFalse(cancellationBody.contains("while (true)"))
        assertTrue(source.contains("DOWNLOAD_CLEAR_MAX_DURABLE_RETRY_ROUNDS"))
        assertTrue(source.contains("private suspend fun activateDownloadClearFence(context: Context): Boolean"))
        assertTrue(source.contains("下载清空栅栏未能持久化，未删除任务或文件并等待下次恢复"))
    }

    @Test
    fun `clear convergence exhaustion keeps the durable fence for a later retry`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val deferIndex = clearBody.indexOf("deferClearForRetry(")
        val deferredBranchIndex = clearBody.indexOf("if (clearDeferredForRetry)")
        val deferredReturnIndex = clearBody.indexOf("return@launch", deferredBranchIndex)
        val fenceReleaseIndex = clearBody.indexOf("clearDownloadClearFence(")

        assertTrue(deferIndex >= 0)
        assertTrue(deferredBranchIndex > deferIndex)
        assertTrue(deferredReturnIndex > deferIndex)
        assertTrue(fenceReleaseIndex > deferredReturnIndex)
        assertTrue(
            clearBody.substring(deferredBranchIndex, fenceReleaseIndex)
                .contains("持久栅栏保持生效")
        )
    }

    @Test
    fun `deferred task clear recovery keeps the presentation fence until a retry settles`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val recoveryBody = source.substringAfter(
            "private fun scheduleDeferredTaskClearRecovery"
        ).substringBefore("private fun scheduleDeferredFullLibraryDeleteRecovery")
        val firstFenceCheck = recoveryBody.indexOf(
            "if (!PersistentDownloadClearFenceStore.isActive(appContext))"
        )
        val joinIndex = recoveryBody.indexOf(
            "requestAllDownloadTaskCancellation("
        )
        val joinedRetryIndex = recoveryBody.indexOf(").join()", joinIndex)
        val secondFenceCheck = recoveryBody.indexOf(
            "if (!PersistentDownloadClearFenceStore.isActive(appContext))",
            firstFenceCheck + 1
        )

        assertTrue(firstFenceCheck >= 0)
        assertTrue(joinIndex > firstFenceCheck)
        assertTrue(joinedRetryIndex > joinIndex)
        assertTrue(secondFenceCheck > joinedRetryIndex)
        assertTrue(recoveryBody.contains("forceConvergence = true"))
        assertTrue(recoveryBody.contains("deferredTaskClearRecoveryScheduled.set(false)"))
        assertFalse(recoveryBody.contains("downloadClearVisibility.finish"))
    }

    @Test
    fun `duplicate clear request waits for the existing durable recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val guardIndex = clearBody.indexOf(
            "if (hadPersistedClearFence && !forceConvergence)"
        )
        val gateIndex = clearBody.indexOf(
            "val clearToken = downloadAdmissionGate.beginClear()"
        )
        val waitIndex = clearBody.indexOf(
            "awaitDownloadClearFenceRelease(appContext)"
        )

        assertTrue(guardIndex >= 0)
        assertTrue(gateIndex > guardIndex)
        assertTrue(waitIndex > guardIndex)
        assertTrue(
            clearBody.substring(guardIndex, gateIndex)
                .contains("scheduleDeferredTaskClearRecovery")
        )
        assertTrue(clearBody.contains("!forceConvergence"))
    }

    @Test
    fun `failed clear fence activation abandons only an unpersisted epoch`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val failureIndex = clearBody.indexOf("if (!fenceActivated)")
        val abandonIndex = clearBody.indexOf(
            "abandonUnpersistedRequestIfCurrent",
            failureIndex
        )
        val releaseIndex = clearBody.indexOf(
            "downloadAdmissionGate.releaseFailedClear(clearToken)",
            abandonIndex
        )

        assertTrue(failureIndex >= 0)
        assertTrue(abandonIndex > failureIndex)
        assertTrue(releaseIndex > abandonIndex)
        assertTrue(
            clearBody.substring(failureIndex, releaseIndex)
                .contains("requestAbandoned")
        )
    }

    @Test
    fun `clear cancellation persists artifact state outside the cancellable job`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val body = source.substringAfter(
            "private suspend fun releaseDownloadArtifactClaim("
        ).substringBefore("private suspend fun releaseDownloadArtifactAfterExecutionOwnershipLoss")

        assertTrue(body.contains("PersistentDownloadClearFenceStore.isActive(context)"))
        assertTrue(body.contains("withContext(NonCancellable)"))
        assertTrue(body.contains("persistCancellation()"))
        assertTrue(body.contains("保留恢复凭据"))
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
    fun `restorable cover lookup only fingerprints and never materializes a second copy`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val lookup = source.substringAfter("internal suspend fun resolveManagedRestorableCoverReference")
            .substringBefore("internal suspend fun syncDownloadedSongMetadataNow")

        assertTrue(lookup.contains("ManagedDownloadCoverAssetStore.inspect("))
        assertFalse(lookup.contains("ManagedDownloadCoverAssetStore.materialize("))
    }

    @Test
    fun `managed metadata editing and downloaded playback require strict snapshot evidence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val restorableLookup = source.substringAfter("internal suspend fun readManagedRestorableMetadata")
            .substringBefore("internal suspend fun resolveManagedRestorableCoverReference")
        val metadataSync = source.substringAfter("internal suspend fun syncDownloadedSongMetadataNow")
            .substringBefore("private suspend fun publishDownloadedSongMetadataFallback")
        val downloadedPlayback = source.substringAfter("fun playDownloadedSong")
            .substringBefore("private fun hydrateDownloadedSidecarLyricsFast")

        assertTrue(restorableLookup.contains("resolveFinalizedManagedAudioSnapshot"))
        assertTrue(metadataSync.contains("resolveFinalizedManagedAudioSnapshot"))
        assertTrue(
            metadataSync.indexOf("resolveFinalizedManagedAudioSnapshot") <
                metadataSync.indexOf("persistDownloadedMetadata(")
        )
        assertTrue(downloadedPlayback.contains("resolvePlayableManagedAudioSnapshot"))
        assertFalse(downloadedPlayback.contains("ManagedDownloadStorage.toPlayableUri(playbackReference)"))
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
    fun `missing optional network cover can finalize as degraded`() {
        assertTrue(
            shouldFinalizeDownloadedSidecars(
                hasNetworkCoverCandidate = true,
                coverReference = null,
                coverAccessible = false,
                allowMissingOptionalCover = true
            )
        )
    }

    @Test
    fun `post core enrichment failure remains completed when audio is committed`() {
        assertEquals(
            DownloadStatus.COMPLETED,
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
    fun `clear all routes the batch wait through the bounded cancellation helper`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()

        assertFalse(source.contains("batchJobs.joinAll()"))
        val backgroundCleanupBody = source.substringAfter(
            "private suspend fun cancelDownloadTasksInBackground"
        ).substringBefore("private suspend fun awaitDownloadCancellationsSettled")
        assertTrue(backgroundCleanupBody.contains("awaitBatchDownloadJobsAfterCancellation("))
        assertFalse(backgroundCleanupBody.contains("batchJobs.joinAll()"))
        assertTrue(source.contains("DOWNLOAD_CANCEL_SETTLE_TIMEOUT_MS"))
    }

    @Test
    fun `clear all persists cancellation before waiting for batch jobs`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearAllBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val journalIndex = clearAllBody.indexOf(
            "requestAllDownloadOperationCancellation(appContext)"
        )
        val immediateHostCancellationIndex = clearAllBody.indexOf(
            "stopDownloadExecutionImmediately("
        )
        val finalizationIndex = clearAllBody.indexOf(
            "DownloadExecutionRoomStore.finalizeRequestedCancellations("
        )
        val backgroundCleanupIndex = clearAllBody.indexOf(
            "cancelDownloadTasksInBackground("
        )

        assertTrue(journalIndex >= 0)
        assertTrue(journalIndex in 0 until immediateHostCancellationIndex)
        assertTrue(finalizationIndex > journalIndex)
        assertTrue(backgroundCleanupIndex > finalizationIndex)
        assertTrue(
            source.contains("DownloadExecutionHosts.cancelAllOwned(appContext)")
        )
        assertTrue(
            source.substringAfter("private suspend fun cancelDownloadTasksInBackground")
                .contains("awaitBatchDownloadJobsAfterCancellation(")
        )
        assertTrue(source.contains("repeat(DOWNLOAD_CANCEL_JOURNAL_MAX_ATTEMPTS)"))
        assertTrue(source.contains("DOWNLOAD_CANCEL_DURABLE_RETRY_DELAY_MS"))
    }

    @Test
    fun `clear all suppresses explicit resume candidates before asynchronous journal cancellation`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val screenSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/DownloadProgressScreen.kt"
        ).readText()
        val clearAllBody = managerSource.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")

        assertTrue(managerSource.contains("val isClearingDownloadTasks: StateFlow<Boolean>"))
        assertTrue(
            managerSource.contains(
                "val isDownloadTaskClearPresentationActive: StateFlow<Boolean>"
            )
        )
        assertTrue(
            managerSource.contains(
                "val isDownloadTaskClearPresentationCleared: StateFlow<Boolean>"
            )
        )
        assertTrue(
            clearAllBody.indexOf("downloadClearVisibility.begin(clearToken)") <
                clearAllBody.indexOf("taskStore.clearAllTasks()")
        )
        assertTrue(managerSource.contains("downloadClearVisibility.finish(clearToken)"))
        assertTrue(
            screenSource.contains(
                "GlobalDownloadManager.isDownloadTaskClearPresentationActive"
            )
        )
        assertTrue(
            screenSource.contains(
                "val effectivePresentationCleared = " +
                    "isDownloadTaskClearPresentationActive"
            )
        )
        val bootstrapEffect = screenSource.substringAfter(
            "LaunchedEffect(\n        context,\n        taskPresenceKey"
        ).substringBefore("val bootstrapState")
        assertTrue(
            bootstrapEffect.contains("isClearingDownloadTasks")
        )
        assertTrue(
            bootstrapEffect.contains("isDownloadTaskClearPresentationActive")
        )
        assertTrue(bootstrapEffect.contains("isDownloadTaskClearPresentationCleared"))
        assertTrue(
            bootstrapEffect.contains(
                "isClearingDownloadTasks ||\n                isDownloadTaskClearPresentationActive ||"
            )
        )
        assertTrue(
            bootstrapEffect.indexOf("explicitResumeCandidates = emptyList()") <
                bootstrapEffect.indexOf("loadDownloadProgressBootstrapState(context)")
        )
        assertTrue(
            screenSource.contains(
                "val visibleTasks = if (effectivePresentationCleared)"
            )
        )
        assertFalse(screenSource.contains("val visibleTasks = if (isClearingDownloadTasks)"))
        assertTrue(screenSource.contains("R.string.download_clearing_tasks"))
        assertTrue(screenSource.contains("visibleDownloadProgressTasks(downloadTasks)"))
        assertFalse(screenSource.contains("item(key = \"queued-summary\")"))
        assertTrue(screenSource.contains("R.string.download_progress_with_percentage"))
    }

    @Test
    fun `clear presentation gate starts before async work and releases after durable fence`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val beginIndex = clearBody.indexOf("downloadAdmissionGate.beginClear()")
        val presentationBeginIndex = clearBody.indexOf(
            "taskStore.beginClearPresentation()"
        )
        val coroutineLaunchIndex = clearBody.indexOf("return scope.launch(")
        val finallyBody = clearBody.substringAfter("} finally {")
        val presentationFinishIndex = finallyBody.indexOf(
            "taskStore.finishClearPresentation(taskPresentationToken)"
        )
        val fenceStateIndex = finallyBody.indexOf(
            "PersistentDownloadClearFenceStore.isActive(appContext)"
        )

        assertTrue(beginIndex >= 0)
        assertTrue(presentationBeginIndex > beginIndex)
        assertTrue(coroutineLaunchIndex > presentationBeginIndex)
        assertTrue(presentationFinishIndex > fenceStateIndex)
        assertTrue(source.contains("currentClearPresentationToken()"))
        assertTrue(source.contains("finishClearPresentation(token)"))
    }

    @Test
    fun `download requests keep the creation admission generation across clear`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val singleBody = source.substringAfter("private fun scheduleUserDownload(")
            .substringBefore("internal suspend fun executeDownloadOperation")
        val batchBody = source.substringAfter("private fun startBatchDownload(")
            .substringBefore("private class BatchDownloadSession")

        assertTrue(singleBody.contains("val capturedAdmissionTicket = requestedAdmissionTicket"))
        assertTrue(singleBody.contains("?: downloadAdmissionGate.openTicketOrNull()"))
        assertTrue(batchBody.contains("val capturedAdmissionTicket = requestedAdmissionTicket"))
        assertTrue(singleBody.contains("capturedAdmissionTicket\n                ?: awaitDownloadAdmissionTicket"))
        assertTrue(
            batchBody.contains(
                "capturedAdmissionTicket\n                ?: if (awaitAdmissionWhenUnavailable)"
            )
        )
        assertTrue(batchBody.contains("清空期间跳过无票据批量下载请求"))
        assertFalse(singleBody.contains("val admissionTicket = awaitDownloadAdmissionTicket(appContext)"))
        assertFalse(batchBody.contains("val admissionTicket = awaitDownloadAdmissionTicket(appContext)"))
    }

    @Test
    fun `resuming a task keeps its creation admission generation across clear`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val resumeBody = source.substringAfter("fun resumeDownloadTask(")
            .substringBefore("private suspend fun awaitSongCancellationSettled")
        val capturedTicketIndex = resumeBody.indexOf(
            "val requestedAdmissionTicket = downloadAdmissionGate.openTicketOrNull()"
        )
        val scheduleIndex = resumeBody.indexOf("scheduleUserDownload(")
        val forwardedTicketIndex = resumeBody.indexOf(
            "requestedAdmissionTicket = requestedAdmissionTicket"
        )

        assertTrue(capturedTicketIndex >= 0)
        assertTrue(scheduleIndex > capturedTicketIndex)
        assertTrue(forwardedTicketIndex > scheduleIndex)
        assertTrue(resumeBody.contains("replacingAttemptId = task.attemptId"))
        assertTrue(
            source.contains(
                "requestedAdmissionTicket: Long? = null"
            )
        )
    }

    @Test
    fun `download progress task recovery button invokes the durable resume entry point`() {
        val screenSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/ui/screen/DownloadProgressScreen.kt"
        ).readText()

        assertTrue(
            screenSource.contains(
                "GlobalDownloadManager.resumeDownloadTask(context, songKey)"
            )
        )
        assertFalse(screenSource.contains("onResume: () -> Unit = {}"))
    }

    @Test
    fun `legacy download backfill is scheduled after startup catalog recovery`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializeBody = managerSource.substringAfter("fun initialize(context: Context)")
            .substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")

        val catalogRestoreIndex = initializeBody.indexOf(
            "val restoredCatalog = restorePersistedDownloadedSongs(appContext)"
        )
        val pendingRecoveryIndex = initializeBody.indexOf(
            "recoverPendingDownloadsForStartup("
        )
        val coverRepairIndex = initializeBody.indexOf(
            "repairFinalizedDownloadedCoversFromRoot("
        )
        val scheduleIndex = initializeBody.indexOf(
            "LegacyJsonCleanupScheduler.schedule(appContext, \"download-startup\")"
        )

        assertTrue(catalogRestoreIndex >= 0)
        assertTrue(pendingRecoveryIndex > catalogRestoreIndex)
        assertTrue(coverRepairIndex > pendingRecoveryIndex)
        assertTrue(scheduleIndex > coverRepairIndex)
        assertFalse(initializeBody.contains("runDownloadUpgradeOnce(appContext)"))
        assertTrue(
            initializeBody.contains("旧下载数据库回填不得阻塞首屏")
        )
        assertTrue(initializeBody.contains("startupRecoveryMutex.withLock"))
        assertTrue(initializeBody.contains("STARTUP_INITIAL_SCAN_WAIT_TIMEOUT_MS"))
        assertTrue(initializeBody.contains("启动目录扫描超过交互等待预算"))
        assertTrue(initializeBody.contains("scheduleCatalogReconcile(appContext, forceRefresh = true)"))
        assertTrue(
            managerSource.contains(
                "internal suspend fun reconcileMaterializedLegacyDownloads(context: Context)"
            )
        )
        val reconcileBody = managerSource
            .substringAfter(
                "internal suspend fun reconcileMaterializedLegacyDownloads(context: Context)"
            )
            .substringBefore("\n    private fun")
        assertTrue(reconcileBody.contains("recoverPendingDownloadsForStartup("))
        assertTrue(reconcileBody.contains("repairFinalizedDownloadedCoversFromRoot("))
    }

    @Test
    fun `startup recovery avoids fixed multi second waits`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        assertTrue(source.contains("STARTUP_PROGRESS_RESTORE_WAIT_TIMEOUT_MS = 500L"))

        val initializeBody = source.substringAfter("fun initialize(context: Context)")
            .substringBefore("internal suspend fun reconcileMaterializedLegacyDownloads")
        assertTrue(initializeBody.contains("yield()"))
        assertFalse(initializeBody.contains("INITIAL_SCAN_DELAY_MS"))

        val startupRecoveryBody = source
            .substringAfter("private suspend fun recoverPendingDownloadsForStartup")
            .substringBefore("private fun loadPendingWorkingProgressSnapshotOnce")
        assertTrue(startupRecoveryBody.contains("yield()"))
        assertFalse(startupRecoveryBody.contains("delay(1_500L)"))
    }

    @Test
    fun `clear fence is durable before task removal and blocks startup recovery`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearAllBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val initializeBody = source.substringAfter("fun initialize(context: Context)")
            .substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")

        val clearVisibilityIndex = clearAllBody.indexOf("downloadClearVisibility.begin(clearToken)")
        val activateIndex = clearAllBody.indexOf("activateDownloadClearFence(appContext)")
        val durableActivateIndex = clearAllBody.indexOf(
            "PersistentDownloadClearFenceStore.activate(appContext)"
        )
        val presentationClearedIndex = clearAllBody.indexOf(
            "downloadClearVisibility.markFencePersisted(clearToken)"
        )
        val firstRunClearIndex = clearAllBody.indexOf(
            "downloadAdmissionGate.runClear(clearToken)"
        )
        val firstPersistedProgressIndex = clearAllBody.indexOf(
            "persistDownloadClearProgress(appContext, clearToken)"
        )
        val immediateStopIndex = clearAllBody.indexOf("stopDownloadExecutionImmediately(")
        val firstTaskClearIndex = clearAllBody.indexOf("taskStore.clearAllTasks()")
        val journalIndex = clearAllBody.indexOf(
            "requestAllDownloadOperationCancellation(appContext)"
        )
        val clearFenceIndex = clearAllBody.indexOf("clearDownloadClearFence(")
        val clearRequestIndex = clearAllBody.indexOf(
            "PersistentDownloadClearFenceStore.beginClear("
        )

        assertTrue(clearRequestIndex >= 0)
        assertTrue(clearRequestIndex > clearAllBody.indexOf("downloadAdmissionGate.beginClear()"))
        assertTrue(clearVisibilityIndex > clearRequestIndex)
        assertTrue(durableActivateIndex > clearRequestIndex)
        assertTrue(clearVisibilityIndex > durableActivateIndex)
        assertTrue(activateIndex > clearVisibilityIndex)
        assertTrue(presentationClearedIndex > durableActivateIndex)
        assertTrue(firstRunClearIndex > durableActivateIndex)
        assertTrue(presentationClearedIndex < firstRunClearIndex)
        assertTrue(firstPersistedProgressIndex < firstRunClearIndex)
        assertTrue(firstTaskClearIndex > durableActivateIndex)
        assertTrue(clearAllBody.indexOf("taskStore.currentTasks()") > durableActivateIndex)
        assertTrue(clearAllBody.indexOf("clearBatchDownloadPresentation()") > durableActivateIndex)
        assertTrue(immediateStopIndex > journalIndex)
        assertTrue(journalIndex > firstTaskClearIndex)
        assertTrue(clearFenceIndex > journalIndex)
        assertTrue(
            clearAllBody.indexOf("return@runClear") < clearFenceIndex
        )
        assertTrue(
            initializeBody.indexOf("PersistentDownloadClearFenceStore.isActive(appContext)") <
                initializeBody.indexOf("recoverPendingAudioWritesFromRoot(")
        )
        assertTrue(source.contains("private suspend fun activateDownloadClearFence"))
        assertTrue(source.contains("private fun stopDownloadExecutionImmediately"))
        assertTrue(source.contains("private suspend fun clearDownloadClearFence"))
        assertTrue(source.contains("DOWNLOAD_CLEAR_PRESENTATION_BUDGET_MS = 500L"))
        assertTrue(clearAllBody.contains("CoroutineStart.UNDISPATCHED"))
        assertTrue(source.contains("if (isDownloadClearFenceActive(appContext))"))
        assertFalse(clearAllBody.contains("beginAndActivate"))
        assertTrue(clearAllBody.contains("while (true)"))
        assertTrue(clearAllBody.contains("下载清空流程失败，保持栅栏并重试"))
        assertTrue(
            clearAllBody.lastIndexOf("retrying failed download clear") <
                clearAllBody.indexOf("clearDownloadClearFence(")
        )
    }

    @Test
    fun `activated clear publishes progress before waiting for admission mutex`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val immediateActivationBody = clearBody.substringAfter(
            "val fenceActivatedImmediately ="
        ).substringBefore("val startFastClearUndispatched")
        val firstRunClearIndex = clearBody.indexOf(
            "downloadAdmissionGate.runClear(clearToken)"
        )
        val markIndex = clearBody.indexOf(
            "downloadClearVisibility.markFencePersisted(clearToken)"
        )
        val persistIndex = clearBody.indexOf(
            "persistDownloadClearProgress(appContext, clearToken)"
        )

        assertFalse(immediateActivationBody.contains("if (fenceActivatedImmediately)"))
        assertTrue(
            immediateActivationBody.contains(
                "if (fenceActivatedImmediately && !hadPersistedClearFence)"
            )
        )
        assertTrue(markIndex in 0 until firstRunClearIndex)
        assertTrue(persistIndex in 0 until firstRunClearIndex)
    }

    @Test
    fun `single cancellation removes presentation before durable cancellation work`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val entryBody = source.substringAfter("fun cancelDownloadTask(songKey: String)")
            .substringBefore("private fun requestDownloadTaskCancellation")
        val batchEntryBody = source.substringAfter("private fun requestDownloadTaskCancellation")
            .substringBefore("private suspend fun cancelDownloadTasksDurably")
        val durableBody = source.substringAfter("private suspend fun cancelDownloadTaskDurably")
            .substringBefore("fun clearAllDownloadTasks()")

        assertFalse(entryBody.contains("operationIdForSong("))
        assertFalse(entryBody.contains("DownloadExecutionOperationStore().read("))
        assertTrue(entryBody.contains("requestDownloadTaskCancellation(setOf(songKey))"))
        assertTrue(batchEntryBody.contains("cancelDownloadTasksDurably("))
        assertTrue(
            batchEntryBody.indexOf("removeDownloadTask(") <
                batchEntryBody.indexOf("return scope.launch")
        )
        assertTrue(durableBody.contains("operationIdForSong("))
        assertTrue(durableBody.contains("requestOperationCancellation(setOf(songKey))"))
    }

    @Test
    fun `batch Wi-Fi wait schedules one global wake instead of one work per operation`() {
        val managerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val workerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/WifiBoundDownloadWakeWorker.kt"
        ).readText()
        val schedulingBody = managerSource.substringAfter(
            "private suspend fun scheduleWifiBoundDownloadWakeups"
        ).substringBefore("private suspend fun pauseActiveDownloadsForNetworkPolicyIfNeeded")

        assertTrue(schedulingBody.contains("WifiBoundDownloadWakeWorker.scheduleAll("))
        assertFalse(schedulingBody.contains("wakeupEntries.forEach"))
        assertTrue(workerSource.contains("private const val GLOBAL_WORK_NAME"))
        assertTrue(workerSource.contains("fun scheduleAll(context: Context)"))
        assertFalse(workerSource.contains("fun rearmAll(context: Context)"))
        assertTrue(workerSource.contains("recoverPendingDownloadsFromWifiWake(applicationContext)"))
        assertTrue(
            workerSource.contains("applicationContext.currentDownloadNetworkTypeOrNull()")
        )
        assertFalse(
            workerSource.contains("applicationContext.currentTrafficNetworkTypeOrNull()")
        )
    }

    @Test
    fun `partial Wi-Fi cancellation does not cancel the global wake for other songs`() {
        val workerSource = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/WifiBoundDownloadWakeWorker.kt"
        ).readText()
        val partialCancelBody = workerSource.substringAfter(
            "fun cancelAll("
        ).substringBefore("fun cancelAllOwned(")

        assertTrue(partialCancelBody.contains("cancelUniqueWork(uniqueWorkName(operationId))"))
        assertFalse(partialCancelBody.contains("cancelAllWorkByTag(ALL_WIFI_WAKE_WORK_TAG)"))
        assertTrue(
            workerSource.substringAfter("fun cancelAllOwned(")
                .contains("cancelAllWorkByTag(ALL_WIFI_WAKE_WORK_TAG)")
        )
    }

    @Test
    fun `recovery triggers share one suspending slot instead of dropping startup work`() = runBlocking {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        assertTrue(source.contains("private val pendingDownloadRecoverySlot = Mutex()"))
        assertTrue(source.contains("withPendingDownloadRecoverySlot(\"startup\")"))
        assertTrue(source.contains("withPendingDownloadRecoverySlot(\"network:"))
        assertFalse(source.contains("pendingDownloadRecoveryActive"))
        assertFalse(source.contains("跳过启动下载恢复: 已有恢复任务执行中"))

        val slot = Mutex()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = launch {
            slot.withLock {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            slot.withLock {
                secondEntered.complete(Unit)
            }
        }
        delay(20L)
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        secondEntered.await()
        joinAll(first, second)
    }

    @Test
    fun `startup restores Room progress before interactive gate`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializeBody = source.substringAfter("fun initialize(context: Context)")
            .substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")
        val progressIndex = initializeBody.indexOf("restorePersistedDownloadProgress(")
        val gateIndex = initializeBody.indexOf("AppStartupWorkGate.awaitInteractiveContentOrTimeout()")

        assertTrue(progressIndex >= 0)
        assertTrue(gateIndex >= 0)
        assertTrue(progressIndex < gateIndex)
        assertEquals(1, initializeBody.split("restorePersistedDownloadProgress(").size - 1)
    }

    @Test
    fun `startup progress backfill is admitted after the clear fence check`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val restoreBody = source.substringAfter(
            "private suspend fun restorePersistedDownloadProgress(context: Context)"
        ).substringBefore("private suspend fun reconcilePendingDownloadArtifacts")
        val ticketIndex = restoreBody.indexOf(
            "val capturedAdmissionTicket = admissionTicket"
        )
        val admissionIndex = restoreBody.indexOf(
            "downloadAdmissionGate.admit(capturedAdmissionTicket)"
        )
        val fenceIndex = restoreBody.indexOf("if (isDownloadClearFenceActive(context))")
        val taskBackfillIndex = restoreBody.indexOf("taskStore.ensureDownloadTasks(")
        val progressBackfillIndex = restoreBody.indexOf("taskStore.restoreProgressBatch(")
        val staleSnapshotIndex = restoreBody.indexOf("if (!admitted || blockedByDurableClear)")

        assertTrue(ticketIndex >= 0)
        assertTrue(admissionIndex > ticketIndex)
        assertTrue(fenceIndex > admissionIndex)
        assertTrue(taskBackfillIndex > fenceIndex)
        assertTrue(progressBackfillIndex > taskBackfillIndex)
        assertTrue(staleSnapshotIndex > progressBackfillIndex)
        assertTrue(restoreBody.contains("跳过任务卡片回填"))
    }

    @Test
    fun `startup recovery keeps the outer admission ticket`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val initializeBody = source.substringAfter("fun initialize(context: Context)")
            .substringBefore("internal suspend fun reconcileMaterializedLegacyDownloads")
        val recoveryBody = source.substringAfter(
            "private suspend fun recoverPendingDownloadsForStartup("
        ).substringBefore("/**\n     * 启动时先从 Room 恢复任务卡片")

        val captureIndex = initializeBody.indexOf(
            "val startupAdmissionTicket = downloadAdmissionGate.openTicketOrNull()"
        )
        val progressCallIndex = initializeBody.indexOf(
            "restorePersistedDownloadProgress(\n                    context = appContext"
        )
        val recoveryCallIndex = initializeBody.indexOf(
            "recoverPendingDownloadsForStartup(\n                    context = appContext"
        )

        assertTrue(captureIndex >= 0)
        assertTrue(progressCallIndex > captureIndex)
        assertTrue(recoveryCallIndex > captureIndex)
        assertTrue(
            initializeBody.substring(progressCallIndex, recoveryCallIndex)
                .contains("admissionTicket = startupAdmissionTicket")
        )
        assertTrue(
            initializeBody.substring(recoveryCallIndex)
                .contains("admissionTicket = startupAdmissionTicket")
        )
        assertTrue(recoveryBody.contains("admissionTicket: Long?"))
        assertTrue(recoveryBody.contains("capturedAdmissionTicket"))
        assertFalse(recoveryBody.contains("openTicketOrNull()"))
    }

    @Test
    fun `late progress callbacks update existing attempts without recreating tasks`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val progressBody = source.substringAfter(
            "private suspend fun updateDownloadProgress(progress: AudioDownloadManager.DownloadProgress)"
        ).substringBefore("private suspend fun restoreTaskProgressCheckpoint")
        val ticketIndex = progressBody.indexOf(
            "val admissionTicket = openDownloadAdmissionTicketOrNull(appContext)"
        )
        val admissionIndex = progressBody.indexOf(
            "admitDownloadMutation(appContext, admissionTicket)"
        )
        val taskUpdateIndex = progressBody.indexOf("taskStore.updateProgress(progress)")
        val presentationIndex = progressBody.indexOf(
            "updateBatchDownloadPresentationProgress(effectiveProgress)"
        )
        val checkpointIndex = progressBody.indexOf(
            "DownloadExecutionRoomStore.checkpointProgress("
        )

        assertTrue(ticketIndex >= 0)
        assertTrue(admissionIndex > ticketIndex)
        assertTrue(taskUpdateIndex > admissionIndex)
        assertTrue(presentationIndex > taskUpdateIndex)
        assertTrue(checkpointIndex > presentationIndex)
        assertTrue(progressBody.contains("progress.attemptId"))
        assertFalse(progressBody.contains("ensureDownloadTasks("))
        assertFalse(progressBody.contains("registerActiveDownloadTask("))
    }

    @Test
    fun `completed task retention and pending queue persistence keep the clear admission`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val completedRemovalBody = source.substringAfter(
            "private fun scheduleCompletedTaskRemoval("
        ).substringBefore("private fun scheduleCatalogReconcile")
        val pendingQueueBody = source.substringAfter(
            "private fun rememberPendingDownloadQueue("
        ).substringBefore("private fun beginDownloadRequestGeneration")

        val delayIndex = completedRemovalBody.indexOf(
            "delay(DOWNLOAD_TASK_COMPLETED_RETENTION_MS)"
        )
        val admissionIndex = completedRemovalBody.indexOf(
            "admitDownloadMutation(appContext, capturedAdmissionTicket)"
        )
        val taskLookupIndex = completedRemovalBody.indexOf("taskStore.findTask(songKey)")
        val removeIndex = completedRemovalBody.indexOf("removeDownloadTask(songKey")

        assertTrue(completedRemovalBody.contains("admissionTicket: Long? = null"))
        assertTrue(
            completedRemovalBody.contains(
                "val capturedAdmissionTicket = admissionTicket"
            )
        )
        assertTrue(
            completedRemovalBody.contains(
                "openDownloadAdmissionTicketOrNull(appContext)"
            )
        )
        assertTrue(delayIndex >= 0)
        assertTrue(admissionIndex > delayIndex)
        assertTrue(taskLookupIndex > admissionIndex)
        assertTrue(removeIndex > taskLookupIndex)

        val permitIndex = pendingQueueBody.indexOf(
            "PersistentDownloadClearFenceStore.withSchedulingPermit("
        )
        val rejectIndex = pendingQueueBody.indexOf("onFenceActive = { emptyList() }")
        val upsertIndex = pendingQueueBody.indexOf(
            "ManagedDownloadStorage.upsertPendingDownloadQueue("
        )

        assertTrue(permitIndex >= 0)
        assertTrue(rejectIndex > permitIndex)
        assertTrue(upsertIndex > rejectIndex)
    }

    @Test
    fun `Wi-Fi wake keeps its current work retryable until recovery reaches a terminal state`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val wakeBody = source.substringAfter(
            "internal suspend fun recoverPendingDownloadsFromWifiWake"
        ).substringBefore("private suspend fun cancelDownloadTaskInBackground")

        val activeBranchIndex = wakeBody.indexOf("if (hasBlockingActiveDownloadOperationsForRecovery())")
        val acceptedIndex = wakeBody.indexOf("val accepted = recoverPendingResumableDownloads")

        assertTrue(wakeBody.contains("withPendingDownloadRecoverySlot(\"wifi_wake\")"))
        assertFalse(wakeBody.contains("tryBeginPendingDownloadRecovery"))
        assertTrue(activeBranchIndex >= 0)
        assertTrue(
            wakeBody.substring(
                activeBranchIndex,
                wakeBody.indexOf("} else", activeBranchIndex)
            ).contains("false")
        )
        assertTrue(acceptedIndex >= 0)
        assertTrue(
            wakeBody.substring(
                acceptedIndex,
                wakeBody.indexOf("} else {", acceptedIndex)
            ).contains("if (!hasPendingRecoveryCandidates(appContext))")
        )
        assertFalse(wakeBody.contains("WifiBoundDownloadWakeWorker.rearmAll(appContext)"))
        assertFalse(wakeBody.contains("rearmWifiWakeAfterCompletion"))
        assertTrue(wakeBody.contains("WIFI 唤醒恢复尚未成为终态，保留 WorkManager 重试"))
    }

    @Test
    fun `cover sidecar is checked before finalized metadata is written`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val enrichmentBody = source.substringAfter("private suspend fun enrichCoreCommittedDownload")
            .substringBefore("private suspend fun publishFinalizedDownload")

        val sidecarIndex = enrichmentBody.indexOf("downloadSidecarsForCompletedAudio(")
        val coverGateIndex = enrichmentBody.indexOf("shouldFinalizeDownloadedSidecars(")
        val finalizedMetadataIndex = enrichmentBody.indexOf("downloadFinalized = true")

        assertTrue(sidecarIndex >= 0)
        assertTrue(coverGateIndex > sidecarIndex)
        assertTrue(finalizedMetadataIndex > coverGateIndex)
        assertTrue(source.contains("repairFinalizedDownloadedCoversFromRoot(appContext)"))
        assertTrue(source.contains("finalizedCoverRepairActive.compareAndSet(false, true)"))
    }

    @Test
    fun `operation execution admits task creation before a clear can proceed`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val executionBody = source.substringAfter(
            "internal suspend fun executeDownloadOperation"
        ).substringBefore("private suspend fun executionResultForOperation")
        val admissionIndex = executionBody.indexOf(
            "admitDownloadMutation(appContext, capturedAdmissionTicket)"
        )
        val ensureIndex = executionBody.indexOf("taskStore.ensureDownloadTasks(")
        val upsertIndex = executionBody.indexOf("DownloadExecutionRoomStore.upsert(")
        val generationIndex = executionBody.indexOf(
            "admittedRequestGeneration = reuseOrBeginDownloadRequestGeneration"
        )

        assertTrue(
            executionBody.indexOf(
                "val capturedAdmissionTicket = admissionTicket"
            ) >= 0
        )
        assertTrue(admissionIndex >= 0)
        assertFalse(executionBody.contains("downloadAdmissionGate.admit(admissionTicket)"))
        assertTrue(ensureIndex > admissionIndex)
        assertTrue(upsertIndex > ensureIndex)
        assertTrue(generationIndex > upsertIndex)
        assertTrue(
            executionBody.contains(
                "taskStore.removeDownloadTask(\n                    songKey = songKey,\n                    expectedAttemptId = effectiveAttemptId"
            )
        )
        val admissionHelperBody = source.substringAfter(
            "private suspend fun admitDownloadMutation("
        ).substringBefore("/** 队列持久化完成前")
        assertTrue(admissionHelperBody.contains("isDownloadClearFenceActive(appContext)"))
        assertTrue(admissionHelperBody.contains("ranBlock = true"))
    }

    @Test
    fun `artifact recovery never bypasses clear admission`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val helperBody = source.substringAfter(
            "private suspend fun admitArtifactRecoveryMutation("
        ).substringBefore("/** 队列持久化完成前")
        val legacyBody = source.substringAfter(
            "internal suspend fun reconcileMaterializedLegacyDownloads(context: Context)"
        ).substringBefore("    /**")
        val migrationBody = source.substringAfter(
            "internal suspend fun reconcilePendingDownloadsBeforeMigrationDetailed("
        ).substringBefore("private const val TERMINAL_OPERATION_RETENTION_MS")
        val recoveryBody = source.substringAfter(
            "private suspend fun recoverPendingAudioWritesFromRoot("
        ).substringBefore("private suspend fun recoverUnfinalizedPublishedAudioFromRoot")
        val sourceRootBody = recoveryBody.substringAfter("if (sourceRootRecovery) {")
            .substringBefore("val metadataPostProcessingEnabled")
        val networkBody = source.substringAfter(
            "fun recoverPendingDownloadsForNetworkRestored(context: Context, reason: String)"
        ).substringBefore("internal fun scheduleWifiRecoveryProbe")

        assertTrue(helperBody.contains("if (admissionTicket == null)"))
        assertTrue(helperBody.contains("return false"))
        assertFalse(helperBody.contains("block()\n            return true"))
        assertTrue(legacyBody.contains("admissionTicket = admissionTicket"))
        assertTrue(
            migrationBody.contains("val admissionTicket = downloadAdmissionGate.openTicketOrNull()")
        )
        assertTrue(migrationBody.contains("admissionTicket = admissionTicket"))
        assertTrue(sourceRootBody.contains("admitArtifactRecoveryMutation("))
        assertTrue(
            sourceRootBody.indexOf("admitArtifactRecoveryMutation(") <
                sourceRootBody.indexOf("cleanupCancelledPendingDownloadArtifacts(")
        )
        assertTrue(
            networkBody.contains(
                "recoverPendingAudioWritesFromRoot(\n                    context = appContext,\n                    admissionTicket = admissionTicket"
            )
        )
        assertTrue(
            networkBody.contains(
                "recoverUnfinalizedPublishedAudioFromRoot(\n                    context = appContext,\n                    admissionTicket = admissionTicket"
            )
        )
        assertFalse(
            networkBody.contains(
                "admitArtifactRecoveryMutation(appContext, admissionTicket) {\n" +
                    "                        recoverPendingAudioWritesFromRoot"
            )
        )
    }

    @Test
    fun `single transfer registration rechecks clear admission after preparation`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val helperBody = source.substringAfter(
            "private suspend fun admitDownloadTransferStart("
        ).substringBefore("private suspend fun startDownloadConfirmed(")
        val startBody = source.substringAfter(
            "private suspend fun startDownloadConfirmed("
        ).substringBefore("    fun startBatchDownload(context: Context, songs: List<SongItem>)")

        assertTrue(helperBody.contains("downloadAdmissionGate.admit(effectiveAdmissionTicket)"))
        assertTrue(helperBody.contains("isDownloadClearFenceActive(appContext)"))
        assertTrue(
            helperBody.contains(
                "isDownloadRequestGenerationCurrent(songKey, requestGeneration)"
            )
        )
        assertTrue(helperBody.contains("isSongCancelled(songKey)"))
        val beginTransferIndex = helperBody.indexOf("taskStore.beginDownloadTransfer()")
        val registerIndex = helperBody.indexOf("taskStore.registerActiveDownloadTask(")
        val resetCancelFlagIndex = helperBody.indexOf("AudioDownloadManager.resetCancelFlag()")
        assertTrue(beginTransferIndex >= 0)
        assertTrue(registerIndex > beginTransferIndex)
        assertTrue(resetCancelFlagIndex > registerIndex)
        assertTrue(helperBody.contains("registeredTask.status != DownloadStatus.DOWNLOADING"))
        assertTrue(startBody.contains("admissionTicket: Long? = null"))
        assertTrue(startBody.contains("val transferAdmitted = admitDownloadTransferStart("))
        assertTrue(
            startBody.indexOf("if (!transferAdmitted)") <
                startBody.indexOf("resumeBatchDownloadPresentationOnRetry(")
        )
        val secondAdmissionCheckIndex = startBody.indexOf("val transferStartStillCurrent")
        val downloadSongIndex = startBody.indexOf("AudioDownloadManager.downloadSong(")
        assertTrue(secondAdmissionCheckIndex >= 0)
        assertTrue(downloadSongIndex > secondAdmissionCheckIndex)
        assertTrue(startBody.contains("!isDownloadClearFenceActive(appContext)"))
        assertTrue(startBody.contains("!isSongCancelled(songKey)"))
        assertTrue(startBody.contains("removeDownloadTask(songKey, expectedAttemptId = attemptId)"))
        assertTrue(
            source.substringAfter("internal suspend fun executeDownloadOperation(")
                .substringBefore("private suspend fun executionResultForOperation(")
                .contains("admissionTicket = capturedAdmissionTicket")
        )
    }

    @Test
    fun `batch preparation and post core recovery honor the admission ticket`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val batchPreparationBody = source
            .substringAfter("private suspend fun prepareBatchDownloadSong(")
            .substringBefore("private suspend fun prepareBatchDownloadSongAdmitted")
        val settlementBody = source
            .substringAfter("private suspend fun settlePostCoreEnrichmentFailure(")
            .substringBefore("private suspend fun schedulePostCoreEnrichmentRetry")
        val retryBody = source
            .substringAfter("private suspend fun schedulePostCoreEnrichmentRetry(")
            .substringBefore("private suspend fun enrichCoreCommittedDownload")

        val preparationAdmissionIndex = batchPreparationBody.indexOf(
            "admitDownloadMutation("
        )
        val preparationCallIndex = batchPreparationBody.indexOf(
            "prepareBatchDownloadSongAdmitted("
        )
        assertTrue(preparationAdmissionIndex >= 0)
        assertTrue(preparationCallIndex > preparationAdmissionIndex)
        assertTrue(batchPreparationBody.contains("session.admissionTicket"))

        val taskUpdateIndex = settlementBody.indexOf("updateTaskStatus(")
        val settlementTicketCheckIndex = settlementBody.lastIndexOf(
            "isDownloadAdmissionTicketCurrent(appContext, admissionTicket)",
            startIndex = taskUpdateIndex
        )
        assertTrue(taskUpdateIndex >= 0)
        assertTrue(settlementTicketCheckIndex >= 0)
        assertTrue(settlementTicketCheckIndex < taskUpdateIndex)

        val permitIndex = retryBody.indexOf(
            "PersistentDownloadClearFenceStore.withSchedulingPermit("
        )
        val permitTicketCheckIndex = retryBody.indexOf(
            "isDownloadAdmissionTicketCurrent(appContext, admissionTicket)",
            startIndex = permitIndex
        )
        val hostScheduleIndex = retryBody.indexOf(
            "DownloadExecutionHosts.default.schedule(",
            startIndex = permitIndex
        )
        assertTrue(permitIndex >= 0)
        assertTrue(permitTicketCheckIndex > permitIndex)
        assertTrue(hostScheduleIndex > permitTicketCheckIndex)
    }

    @Test
    fun `clear all keeps durable cancellation tombstones until a fresh request replaces them`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val clearAllBody = source.substringAfter(
            "private fun requestAllDownloadTaskCancellation"
        ).substringBefore("private suspend fun cancelAllDownloadTasksAndWait")
        val cleanupBody = source.substringAfter(
            "private suspend fun cancelDownloadTasksInBackground"
        ).substringBefore("private suspend fun awaitDownloadCancellationsSettled")

        assertFalse(cleanupBody.contains("DownloadExecutionRoomStore.purgeCancelled("))
        assertFalse(cleanupBody.contains("DownloadExecutionRoomStore.purgeClearedOperations("))
        assertTrue(clearAllBody.contains("listAllOperationIdentities(appContext)"))
        assertTrue(clearAllBody.contains("workingFilesBySongKey = clearWorkingFilesBySongKey"))
        assertTrue(clearAllBody.contains("executionOperationIds = clearOperationIds"))
        assertTrue(
            cleanupBody.contains(
                "workingFiles.forEach(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)"
            )
        )
        assertTrue(cleanupBody.contains("hasWorkingDownloadArtifact"))
        assertTrue(cleanupBody.contains("residualWorkingSongKeys"))
        assertTrue(cleanupBody.contains("executionOperationIds"))
        assertTrue(
            clearAllBody.indexOf("if (!settlement.isSettled)") <
                clearAllBody.indexOf("purgeFullyClearedOperations(")
        )
        assertTrue(source.contains("clearSongCancellationForFreshStart"))
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

    @Test
    fun `startup download recovery waits for user decision on mobile data`() {
        assertFalse(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false
            )
        )
        assertTrue(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            )
        )
        assertTrue(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.ROAMING,
                mobileDataOverrideAllowed = false
            )
        )
        assertFalse(
            shouldDeferPendingDownloadRecoveryForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = true
            )
        )
    }

    @Test
    fun `wifi admitted execution waits after transport moves to mobile until user continues`() {
        assertTrue(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            )
        )
        assertTrue(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.ROAMING,
                mobileDataOverrideAllowed = false
            )
        )
        assertFalse(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false
            )
        )
        assertFalse(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = true,
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = true
            )
        )
        assertFalse(
            shouldDeferDownloadExecutionForNetwork(
                requiresWifiNetwork = false,
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false
            )
        )
    }

    @Test
    fun `only Wi-Fi-bound durable work keeps network policy active before memory rehydrates`() {
        assertFalse(
            hasWifiBoundNetworkPolicyDownloads(
                activeTaskCount = 0,
                persistedQueuedCount = 0
            )
        )
        assertTrue(
            hasWifiBoundNetworkPolicyDownloads(
                activeTaskCount = 0,
                persistedQueuedCount = 1
            )
        )
    }

    @Test
    fun `Wi-Fi waiting count is the stable-key union across active and durable work`() {
        assertEquals(
            4,
            wifiBoundDownloadTaskCount(
                activeSongKeys = listOf("active-a", "shared", "  "),
                persistedSongKeys = listOf("shared", "persisted-c", "persisted-d", "")
            )
        )
    }

    @Test
    fun `mobile interruption recount distinguishes unavailable data from an authoritative zero`() {
        assertEquals(
            843,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 843,
                observedTaskCount = null,
                fallbackTaskCount = 1
            )
        )
        assertEquals(
            0,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 843,
                observedTaskCount = 0,
                fallbackTaskCount = 1
            )
        )
        assertEquals(
            2,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 843,
                observedTaskCount = 2,
                fallbackTaskCount = 1
            )
        )
        assertEquals(
            3,
            resolveMobileDataDownloadInterruptionTaskCount(
                existingTaskCount = 1,
                observedTaskCount = null,
                fallbackTaskCount = 3
            )
        )
    }

    @Test
    fun `mobile interruption snapshot cannot publish after a clear advances its epoch`() {
        assertTrue(
            isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = null,
                currentEpoch = 5L
            )
        )
        assertTrue(
            isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = 5L,
                currentEpoch = 5L
            )
        )
        assertFalse(
            isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = 5L,
                currentEpoch = 6L
            )
        )
    }

    @Test
    fun `authoritative mobile interruption counts carry their sampling epoch into publication`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val networkPolicyBody = source.substringAfter(
            "private suspend fun pauseActiveDownloadsForNetworkPolicyIfNeeded"
        ).substringBefore("private suspend fun deferQueuedDownloadStartForNetworkPolicyIfNeeded")
        val wifiDisconnectBody = source.substringAfter(
            "fun interruptDownloadsForWifiDisconnected"
        ).substringBefore("fun continueDownloadsOnMobileData")
        val publicationBody = source.substringAfter(
            "private suspend fun publishMobileDataDownloadInterruptionRequestIfNeeded"
        ).substringBefore("private suspend fun observeWifiBoundMobileDataTaskCount")

        assertTrue(networkPolicyBody.contains("val interruptionSnapshotEpoch"))
        assertTrue(networkPolicyBody.contains("interruptionSnapshotEpoch = interruptionSnapshotEpoch"))
        assertTrue(wifiDisconnectBody.contains("val interruptionSnapshotEpoch"))
        assertTrue(wifiDisconnectBody.contains("interruptionSnapshotEpoch = interruptionSnapshotEpoch"))
        assertTrue(publicationBody.contains("interruptionSnapshotEpoch: Long? = null"))
        assertTrue(
            publicationBody.contains("isMobileDataDownloadInterruptionSnapshotCurrent(")
        )
    }

    @Test
    fun `known cancelled durable operation is not counted through a stale fallback queue entry`() {
        assertNull(
            resolvePersistedWifiBoundRequirement(
                fallbackRequiresWifi = true,
                hasKnownOperation = true,
                durableRequiresWifi = null
            )
        )
        assertEquals(
            true,
            resolvePersistedWifiBoundRequirement(
                fallbackRequiresWifi = true,
                hasKnownOperation = false,
                durableRequiresWifi = null
            )
        )
        assertEquals(
            false,
            resolvePersistedWifiBoundRequirement(
                fallbackRequiresWifi = true,
                hasKnownOperation = true,
                durableRequiresWifi = false
            )
        )
    }

    @Test
    fun `Wi-Fi disconnect revokes mobile override only while the current route remains non Wi-Fi`() {
        assertFalse(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.WIFI,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
        assertFalse(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.WIFI
            )
        )
        assertFalse(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.ROAMING,
                currentNetworkType = TrafficNetworkType.WIFI
            )
        )
        assertTrue(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
        assertTrue(
            shouldRevokeMobileDataDownloadOverrideForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.ROAMING,
                currentNetworkType = TrafficNetworkType.ROAMING
            )
        )
    }

    @Test
    fun `Wi-Fi disconnect skips stale callback after Wi-Fi is restored`() {
        assertFalse(
            shouldPauseDownloadsForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.WIFI
            )
        )
        assertTrue(
            shouldPauseDownloadsForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.MOBILE,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
        assertFalse(
            shouldPauseDownloadsForWifiDisconnect(
                callbackNetworkType = TrafficNetworkType.WIFI,
                currentNetworkType = TrafficNetworkType.MOBILE
            )
        )
    }

    @Test
    fun `explicit mobile permission is not paused by a later Wi-Fi disconnect`() {
        assertTrue(shouldPauseDownloadForWifiDisconnect(requiresWifiNetwork = true))
        assertFalse(shouldPauseDownloadForWifiDisconnect(requiresWifiNetwork = false))
    }

    @Test
    fun `network pause marker survives an unsettled prior transfer`() {
        assertFalse(
            shouldClearNetworkPolicyPauseAfterCancellationSettled(
                cancellationSettled = false
            )
        )
        assertTrue(
            shouldClearNetworkPolicyPauseAfterCancellationSettled(
                cancellationSettled = true
            )
        )
    }

    @Test
    fun `new execution keeps network pause until the prior transfer settles`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val startBody = source.substringAfter("private suspend fun startDownloadConfirmed")
            .substringBefore("fun startBatchDownload(context")

        val settleIndex = startBody.indexOf("val cancellationSettled = awaitSongCancellationSettled(")
        val guardIndex = startBody.indexOf(
            "shouldClearNetworkPolicyPauseAfterCancellationSettled(cancellationSettled)"
        )
        val clearIndex = startBody.indexOf("AudioDownloadManager.clearNetworkPolicyPause")

        assertTrue(settleIndex >= 0)
        assertTrue(guardIndex > settleIndex)
        assertTrue(clearIndex > guardIndex)
        assertTrue(startBody.contains("CANCELLATION_SETTLEMENT_PENDING"))
    }

    @Test
    fun `prepared recovery download start is blocked on mobile data until user confirms`() {
        assertFalse(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false,
                deferForNetworkPolicy = false
            )
        )
        assertTrue(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = false,
                deferForNetworkPolicy = true
            )
        )
        assertFalse(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.MOBILE,
                mobileDataOverrideAllowed = true,
                deferForNetworkPolicy = true
            )
        )
        assertFalse(
            shouldDeferQueuedDownloadStartForNetwork(
                networkType = TrafficNetworkType.WIFI,
                mobileDataOverrideAllowed = false,
                deferForNetworkPolicy = true
            )
        )
    }

    @Test
    fun `missing downloaded cover is repaired only when a network candidate exists`() {
        assertTrue(
            shouldRepairDownloadedCover(
                coverReferenceAccessible = false,
                hasNetworkCoverCandidate = true
            )
        )
        assertFalse(
            shouldRepairDownloadedCover(
                coverReferenceAccessible = true,
                hasNetworkCoverCandidate = true
            )
        )
        assertFalse(
            shouldRepairDownloadedCover(
                coverReferenceAccessible = false,
                hasNetworkCoverCandidate = false
            )
        )
    }

    @Test
    fun `cancel cleanup survives invalidated generation until a new request takes over`() {
        assertTrue(
            shouldKeepCancellationCleanup(
                currentGeneration = 10L,
                cancellationGeneration = 10L,
                cancelled = true
            )
        )
        assertTrue(
            shouldKeepCancellationCleanup(
                currentGeneration = null,
                cancellationGeneration = 10L,
                cancelled = true
            )
        )
        assertFalse(
            shouldKeepCancellationCleanup(
                currentGeneration = 11L,
                cancellationGeneration = 10L,
                cancelled = true
            )
        )
        assertFalse(
            shouldKeepCancellationCleanup(
                currentGeneration = null,
                cancellationGeneration = 10L,
                cancelled = false
            )
        )
    }

    @Test
    fun `downloaded song catalog keeps lightweight list fields in json cache`() {
        val song = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.mp3",
            fileSize = 2048L,
            downloadTime = 123456L,
            coverPath = "/music/Covers/song.jpg",
            coverUrl = "https://example.com/cover.jpg",
            matchedLyric = "[00:00.00]lyric",
            matchedTranslatedLyric = "[00:00.00]translated",
            matchedLyricSource = "CLOUD_MUSIC",
            matchedSongId = "9001",
            userLyricOffsetMs = 120L,
            customCoverUrl = "https://example.com/custom.jpg",
            customName = "Custom Song",
            customArtist = "Custom Artist",
            originalName = "Original Song",
            originalArtist = "Original Artist",
            originalCoverUrl = "https://example.com/original.jpg",
            originalLyric = "original lyric",
            originalTranslatedLyric = "original translated lyric",
            mediaUri = "content://downloads/song.mp3",
            durationMs = 3000L
        )

        val payload = serializeDownloadedSongsCatalog(
            cacheKey = "tree:test",
            songs = listOf(song)
        )

        val restored = deserializeDownloadedSongsCatalog(
            raw = payload,
            expectedCacheKey = "tree:test"
        )

        assertEquals(
            listOf(
                song.copy(
                    originalLyric = null,
                    originalTranslatedLyric = null,
                    originalRomanizedLyric = null
                )
            ),
            restored
        )
    }

    @Test
    fun `catalog upsert immediately replaces a custom cover with the restored cover`() {
        val customCoverSong = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.m4a",
            fileSize = 2048L,
            downloadTime = 123456L,
            coverPath = "file:///data/user/0/app/files/original-cover.jpg",
            customCoverUrl = "file:///data/user/0/app/files/custom-cover.jpg",
            originalCoverUrl = "file:///data/user/0/app/files/original-cover.jpg",
            mediaUri = "content://downloads/song.m4a",
            stableKey = "42|netease|"
        )
        val restoredCoverSong = customCoverSong.copy(
            customCoverUrl = null,
            coverPath = "file:///data/user/0/app/files/original-cover.jpg"
        )

        assertTrue(
            shouldPublishDownloadedSongCatalogUpdate(
                currentSong = customCoverSong,
                updatedSong = restoredCoverSong
            )
        )
        assertEquals(
            listOf(restoredCoverSong),
            upsertDownloadedSongCatalog(
                currentSongs = listOf(customCoverSong),
                updatedSong = restoredCoverSong
            )
        )
    }

    @Test
    fun `resolveDownloadedLyricContent keeps embedded and local fallbacks compatible`() {
        assertEquals(
            "embedded lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = "embedded lyric",
                embeddedOriginalLyric = "original lyric",
                localLyricContent = "local lyric",
                indexedLyricContent = "indexed lyric"
            )
        )
        assertEquals(
            "original lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = "original lyric",
                localLyricContent = "local lyric",
                indexedLyricContent = "indexed lyric"
            )
        )
        assertEquals(
            "local lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = null,
                localLyricContent = "local lyric",
                indexedLyricContent = "indexed lyric"
            )
        )
        assertEquals(
            "indexed lyric",
            resolveDownloadedLyricContent(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = null,
                localLyricContent = null,
                indexedLyricContent = "indexed lyric"
            )
        )
    }

    @Test
    fun `resolveDownloadedLyricOverride keeps explicit blank metadata over fallback lyrics`() {
        assertEquals(
            "",
            resolveDownloadedLyricOverride(
                fileLyric = null,
                embeddedMatchedLyric = "",
                embeddedOriginalLyric = "[00:00.00]original",
                localLyricContent = "[00:00.00]local",
                indexedLyricContent = "[00:00.00]indexed"
            )
        )
        assertEquals(
            "",
            resolveDownloadedLyricOverride(
                fileLyric = null,
                embeddedMatchedLyric = null,
                embeddedOriginalLyric = "",
                localLyricContent = "[00:00.00]local",
                indexedLyricContent = "[00:00.00]indexed"
            )
        )
        assertEquals(
            "",
            resolveDownloadedLyricOverride(
                fileLyric = "",
                embeddedMatchedLyric = "[00:00.00]embedded",
                embeddedOriginalLyric = "[00:00.00]original",
                localLyricContent = "[00:00.00]local",
                indexedLyricContent = "[00:00.00]indexed"
            )
        )
    }

    @Test
    fun `download task remains cancellable during finalizing stage`() {
        val task = DownloadTask(
            song = SongItem(
                id = 7L,
                name = "Finalizing",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/finalizing"
            ),
            progress = AudioDownloadManager.DownloadProgress(
                songKey = "7|Album|https://example.com/finalizing",
                songId = 7L,
                fileName = "Finalizing.flac",
                bytesRead = 1024L,
                totalBytes = 1024L,
                speedBytesPerSec = 0L,
                stage = AudioDownloadManager.DownloadStage.FINALIZING
            ),
            status = DownloadStatus.DOWNLOADING
        )

        assertTrue(isDownloadTaskFinalizing(task))
        assertTrue(isDownloadTaskCancellable(task))
    }

    @Test
    fun `task mutation applies only to matching attempt id`() {
        val task = DownloadTask(
            song = SongItem(
                id = 8L,
                name = "Attempt",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/attempt"
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING,
            attemptId = 42L
        )

        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = 42L))
        assertFalse(shouldApplyTaskMutation(task, expectedAttemptId = 7L))
        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = null))
    }

    @Test
    fun `upsertDownloadedSongCatalog replaces same file and keeps newest first`() {
        val olderSong = DownloadedSong(
            id = 1L,
            name = "Older",
            artist = "Artist",
            album = "Album",
            filePath = "/music/older.flac",
            fileSize = 10L,
            downloadTime = 10L,
            durationMs = 1000L
        )
        val currentSong = DownloadedSong(
            id = 2L,
            name = "Current",
            artist = "Artist",
            album = "Album",
            filePath = "/music/current.flac",
            fileSize = 20L,
            downloadTime = 30L,
            durationMs = 2000L
        )
        val updatedCurrentSong = currentSong.copy(name = "Current V2", downloadTime = 40L)

        val merged = upsertDownloadedSongCatalog(
            currentSongs = listOf(olderSong, currentSong),
            updatedSong = updatedCurrentSong
        )

        assertEquals(listOf(updatedCurrentSong, olderSong), merged)
    }

    @Test
    fun `upsertDownloadedSongCatalog appends new file without disturbing existing items`() {
        val firstSong = DownloadedSong(
            id = 1L,
            name = "First",
            artist = "Artist",
            album = "Album",
            filePath = "/music/first.flac",
            fileSize = 10L,
            downloadTime = 50L,
            durationMs = 1000L
        )
        val secondSong = DownloadedSong(
            id = 2L,
            name = "Second",
            artist = "Artist",
            album = "Album",
            filePath = "/music/second.flac",
            fileSize = 20L,
            downloadTime = 40L,
            durationMs = 2000L
        )
        val thirdSong = DownloadedSong(
            id = 3L,
            name = "Third",
            artist = "Artist",
            album = "Album",
            filePath = "/music/third.flac",
            fileSize = 30L,
            downloadTime = 45L,
            durationMs = 3000L
        )

        val merged = upsertDownloadedSongCatalog(
            currentSongs = listOf(firstSong, secondSong),
            updatedSong = thirdSong
        )

        assertEquals(listOf(firstSong, thirdSong, secondSong), merged)
    }

    @Test
    fun `catalog order is stable when download times are equal`() {
        val first = DownloadedSong(
            id = 1L,
            name = "First",
            artist = "Artist",
            album = "Album",
            filePath = "/music/z.flac",
            fileSize = 1L,
            downloadTime = 100L
        )
        val second = first.copy(
            id = 2L,
            name = "Second",
            filePath = "/music/a.flac"
        )

        assertEquals(
            listOf(second, first),
            upsertDownloadedSongCatalog(listOf(first), second)
        )
    }

    @Test
    fun `pending download task helpers ignore completed items`() {
        val downloadingTask = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Downloading",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/downloading"
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING
        )
        val completedTask = downloadingTask.copy(
            song = downloadingTask.song.copy(id = 2L, name = "Completed"),
            status = DownloadStatus.COMPLETED
        )
        val failedTask = downloadingTask.copy(
            song = downloadingTask.song.copy(id = 3L, name = "Failed"),
            status = DownloadStatus.FAILED
        )
        val cancelledTask = downloadingTask.copy(
            song = downloadingTask.song.copy(id = 4L, name = "Cancelled"),
            status = DownloadStatus.CANCELLED
        )

        assertEquals(
            2,
            countPendingDownloadTasks(
                listOf(downloadingTask, completedTask, failedTask, cancelledTask)
            )
        )
        assertTrue(
            hasPendingDownloadTasks(
                listOf(downloadingTask, completedTask, failedTask, cancelledTask)
            )
        )
        assertFalse(hasPendingDownloadTasks(listOf(completedTask)))
        assertFalse(hasPendingDownloadTasks(listOf(cancelledTask)))

        val summary = buildDownloadTaskSummary(
            listOf(downloadingTask, completedTask, failedTask, cancelledTask)
        )
        assertEquals(2, summary.pendingTaskCount)
        assertEquals(0, summary.queuedTaskCount)
        assertTrue(summary.hasActiveTasks)
        assertTrue(summary.hasActiveOperations)
    }

    @Test
    fun `active download helpers keep finalizing tasks cancellable`() {
        val finalizingTask = DownloadTask(
            song = SongItem(
                id = 4L,
                name = "Finalizing",
                artist = "Artist",
                album = "Album",
                albumId = 4L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/finalizing"
            ),
            progress = AudioDownloadManager.DownloadProgress(
                songKey = "song:4",
                songId = 4L,
                fileName = "Finalizing.flac",
                bytesRead = 1_024L,
                totalBytes = 1_024L,
                speedBytesPerSec = 0L,
                stage = AudioDownloadManager.DownloadStage.FINALIZING
            ),
            status = DownloadStatus.DOWNLOADING
        )
        val completedTask = finalizingTask.copy(
            song = finalizingTask.song.copy(id = 5L, name = "Completed"),
            progress = null,
            status = DownloadStatus.COMPLETED
        )

        assertTrue(isDownloadTaskFinalizing(finalizingTask))
        assertTrue(isDownloadTaskCancellable(finalizingTask))
        assertTrue(hasActiveDownloadTasks(listOf(finalizingTask, completedTask)))
        assertFalse(hasActiveDownloadTasks(listOf(completedTask)))
    }

    @Test
    fun `active download operations keep directory changes blocked until download pipeline is fully idle`() {
        val queuedTask = DownloadTask(
            song = SongItem(
                id = 6L,
                name = "Queued",
                artist = "Artist",
                album = "Album",
                albumId = 6L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/queued"
            ),
            progress = null,
            status = DownloadStatus.QUEUED
        )
        val completedTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 7L, name = "Completed"),
            status = DownloadStatus.COMPLETED
        )

        assertTrue(
            hasActiveDownloadOperations(
                tasks = listOf(queuedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasActiveDownloadOperations(
                tasks = listOf(completedTask),
                isSingleDownloading = true,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasActiveDownloadOperations(
                tasks = listOf(completedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = true
            )
        )
        assertFalse(
            hasActiveDownloadOperations(
                tasks = listOf(completedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
    }

    @Test
    fun `recovery is not blocked by stale queued tasks without a running pipeline`() {
        val queuedTask = DownloadTask(
            song = SongItem(
                id = 8L,
                name = "Queued",
                artist = "Artist",
                album = "Album",
                albumId = 8L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/queued-recovery"
            ),
            progress = null,
            status = DownloadStatus.QUEUED
        )
        val downloadingTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 9L, name = "Downloading"),
            status = DownloadStatus.DOWNLOADING
        )

        assertFalse(
            hasRecoveryBlockingDownloadOperations(
                tasks = listOf(queuedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasRecoveryBlockingDownloadOperations(
                tasks = listOf(downloadingTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
        assertTrue(
            hasRecoveryBlockingDownloadOperations(
                tasks = listOf(queuedTask),
                isSingleDownloading = false,
                hasActiveBatchJobs = true
            )
        )
        assertTrue(
            hasRecoveryBlockingDownloadOperations(
                tasks = emptyList(),
                isSingleDownloading = true,
                hasActiveBatchJobs = false
            )
        )
    }

    @Test
    fun `findDownloadedSongCatalogMatch prefers stable identity for remote favorites playback`() {
        val song = SongItem(
            id = 9L,
            name = "Favorite Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://music.163.com/song?id=9"
        )
        val downloaded = DownloadedSong(
            id = 100L,
            name = "renamed locally",
            artist = "local artist",
            album = "Downloads",
            filePath = "content://downloads/9",
            fileSize = 10L,
            downloadTime = 10L,
            stableKey = song.stableKey()
        )

        assertEquals(downloaded, findDownloadedSongCatalogMatch(song, listOf(downloaded)))
    }

    @Test
    fun `downloaded song catalog index keeps newest stable match first`() {
        val song = SongItem(
            id = 9L,
            name = "Favorite Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://music.163.com/song?id=9"
        )
        val newest = DownloadedSong(
            id = 100L,
            name = "renamed locally",
            artist = "local artist",
            album = "Downloads",
            filePath = "content://downloads/newest",
            fileSize = 10L,
            downloadTime = 20L,
            stableKey = song.stableKey()
        )
        val older = newest.copy(
            filePath = "content://downloads/older",
            downloadTime = 10L
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(newest, older)
        )

        assertEquals(newest, index.find(song))
    }

    @Test
    fun `downloaded song catalog index still falls back to legacy identity when stable key entry mismatches`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Bilibili|2002",
            albumId = 0L,
            durationMs = 3000L,
            coverUrl = null
        )
        val mismatchedStable = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/mismatch.flac",
            fileSize = 10L,
            downloadTime = 20L,
            stableKey = SongItem(
                id = 42L,
                name = "Song",
                artist = "Artist",
                album = "Bilibili|1001",
                albumId = 0L,
                durationMs = 3000L,
                coverUrl = null
            ).stableKey()
        )
        val legacyFallback = mismatchedStable.copy(
            filePath = "/music/legacy.flac",
            downloadTime = 10L,
            stableKey = null
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(mismatchedStable, legacyFallback)
        )

        assertEquals(legacyFallback, index.find(song))
    }

    @Test
    fun `downloaded song catalog keeps legacy remote entries without source identity`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 3_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val legacyDownloaded = DownloadedSong(
            id = song.id,
            name = song.name,
            artist = song.artist,
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(listOf(legacyDownloaded))

        assertEquals(legacyDownloaded, index.find(song))
        assertTrue(matchesDownloadedSong(song, legacyDownloaded))
    }

    @Test
    fun `downloaded song catalog keeps a legacy local stable key entry for its remote song`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 3_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val legacyDownloaded = DownloadedSong(
            id = song.id,
            name = song.name,
            artist = song.artist,
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L,
            stableKey = "42|__local_files__|/music/song.flac"
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(listOf(legacyDownloaded))

        assertEquals(legacyDownloaded, index.find(song))
        assertTrue(matchesDownloadedSong(song, legacyDownloaded))
    }

    @Test
    fun `downloaded catalog matches a local shaped queue item through its remote identity`() {
        val remoteSong = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 3_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val downloaded = DownloadedSong(
            id = remoteSong.id,
            name = remoteSong.name,
            artist = remoteSong.artist,
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L,
            stableKey = remoteSong.stableKey(),
            sourceChannelId = "netease",
            sourceAudioId = "42"
        )
        val localShapedQueueItem = remoteSong.copy(
            mediaUri = "content://old-tree/song",
            sourceStableKey = null
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(listOf(downloaded))

        assertEquals(downloaded, index.find(localShapedQueueItem))
        assertTrue(matchesDownloadedSong(localShapedQueueItem, downloaded))
    }

    @Test
    fun `downloaded catalog recovers a legacy local row after its SAF uri changes`() {
        val localSong = SongItem(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://new-tree/document/primary%3AMusic%2FSong.flac",
            localFileName = "Song.flac"
        )
        val legacyDownloaded = DownloadedSong(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "/old-private/Song.flac",
            fileSize = 1024L,
            downloadTime = 10L,
            durationMs = 180_000L,
            coverPath = "/old-private/Covers/Song.jpg"
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(legacyDownloaded)
        )

        assertEquals(legacyDownloaded, index.find(localSong))
    }

    @Test
    fun `downloaded catalog refuses ambiguous legacy local filenames`() {
        val localSong = SongItem(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "content://new-tree/document/primary%3AMusic%2FSong.flac",
            localFileName = "Song.flac"
        )
        val first = DownloadedSong(
            id = 0L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "/old-private/Song.flac",
            fileSize = 1024L,
            downloadTime = 10L,
            durationMs = 180_000L
        )
        val second = first.copy(
            filePath = "/other-private/Song.flac",
            downloadTime = 11L
        )

        val index = GlobalDownloadManager.buildDownloadedSongCatalogIndex(
            listOf(first, second)
        )

        assertNull(index.find(localSong))
    }

    @Test
    fun `catalog upsert replaces a legacy entry when the local file reference is unchanged`() {
        val legacy = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Downloads",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 10L
        )
        val refreshed = legacy.copy(
            sourceChannelId = "netease",
            sourceAudioId = "42",
            downloadTime = 20L
        )

        assertTrue(matchesDownloadedSongCatalogEntry(legacy, refreshed))
        assertEquals(listOf(refreshed), upsertDownloadedSongCatalog(listOf(legacy), refreshed))
    }

    @Test
    fun `matchesDownloadedSongCatalogEntry keeps legacy media uri entries aligned`() {
        val legacy = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = "content://downloads/song"
        )
        val refreshed = legacy.copy(
            filePath = "/storage/emulated/0/Android/data/moe.ouom.neriplayer/files/song.flac",
            mediaUri = "content://downloads/song",
            downloadTime = 20L
        )

        assertTrue(matchesDownloadedSongCatalogEntry(legacy, refreshed))
    }

    @Test
    fun `upsertDownloadedSongCatalog replaces legacy media uri entries when file path was blank`() {
        val legacy = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = "content://downloads/song"
        )
        val refreshed = legacy.copy(
            filePath = "/storage/emulated/0/Android/data/moe.ouom.neriplayer/files/song.flac",
            fileSize = 20L,
            downloadTime = 20L,
            mediaUri = "content://downloads/song"
        )

        assertEquals(listOf(refreshed), upsertDownloadedSongCatalog(listOf(legacy), refreshed))
    }

    @Test
    fun `resolveDownloadedSongPlaybackReference falls back to media uri when file path is blank`() {
        val downloaded = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "",
            fileSize = 10L,
            downloadTime = 10L,
            mediaUri = "content://downloads/song"
        )

        assertEquals(
            "content://downloads/song",
            resolveDownloadedSongPlaybackReference(downloaded)
        )
        assertNull(
            resolveDownloadedSongPlaybackReference(
                downloaded.copy(mediaUri = "https://example.com/remote")
            )
        )
    }

    @Test
    fun `fast downloaded catalog hit trusts lightweight cache without accessibility probe`() {
        assertTrue(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "content://downloads/song",
                cachedKnownReferences = null
            )
        )
        assertTrue(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "content://downloads/song",
                cachedKnownReferences = setOf("content://downloads/song")
            )
        )
        assertFalse(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "content://downloads/song",
                cachedKnownReferences = setOf("content://downloads/other")
            )
        )
        assertFalse(
            shouldTrustFastDownloadedSongCatalogHit(
                reference = "",
                cachedKnownReferences = null
            )
        )
    }

    @Test
    fun `completed download post processing skips access probe for trusted reference`() {
        assertFalse(
            shouldProbeCompletedAudioAccessDuringPostProcessing(
                reference = "content://downloads/song",
                fastPathTrusted = true
            )
        )
        assertTrue(
            shouldProbeCompletedAudioAccessDuringPostProcessing(
                reference = "content://downloads/song",
                fastPathTrusted = false
            )
        )
        assertFalse(
            shouldProbeCompletedAudioAccessDuringPostProcessing(
                reference = "",
                fastPathTrusted = false
            )
        )
    }

    @Test
    fun `SAF sidecar lookup avoids indexed scan during fast background finalization`() {
        assertFalse(
            shouldUseIndexedSidecarLookup(
                usesDocumentTree = true,
                allowSlowLookup = true
            )
        )
        assertTrue(
            shouldUseIndexedSidecarLookup(
                usesDocumentTree = false,
                allowSlowLookup = true
            )
        )
        assertFalse(
            shouldUseIndexedSidecarLookup(
                usesDocumentTree = false,
                allowSlowLookup = false
            )
        )
    }

    @Test
    fun `cancelled artifact recovery yields to active retry`() {
        assertTrue(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = true,
                taskStatus = null
            )
        )
        assertTrue(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = DownloadStatus.QUEUED
            )
        )
        assertTrue(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = DownloadStatus.DOWNLOADING
            )
        )
        assertFalse(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = DownloadStatus.CANCELLED
            )
        )
        assertFalse(
            shouldSkipCancelledArtifactRecovery(
                downloadActive = false,
                taskStatus = null
            )
        )
    }

    @Test
    fun `detailed inspection stays disabled when slow local inspection is turned off`() {
        assertEquals(
            false,
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = false,
                metadata = null,
                coverReference = null,
                needsLocalLyricFallback = true
            )
        )
    }

    @Test
    fun `detailed inspection is skipped when cached metadata is already complete`() {
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            name = "Song",
            artist = "Artist",
            originalName = "Song",
            originalArtist = "Artist",
            durationMs = 3000L
        )

        assertEquals(
            false,
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = true,
                metadata = metadata,
                coverReference = "content://covers/song.jpg",
                needsLocalLyricFallback = false
            )
        )
    }

    @Test
    fun `detailed inspection stays enabled when local lyric fallback is the only source left`() {
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            name = "Song",
            artist = "Artist",
            originalName = "Song",
            originalArtist = "Artist",
            durationMs = 3000L
        )

        assertEquals(
            true,
            shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection = true,
                metadata = metadata,
                coverReference = "content://covers/song.jpg",
                needsLocalLyricFallback = true
            )
        )
    }

    @Test
    fun `hidden downloaded metadata refresh does not republish the whole catalog`() {
        val currentSong = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            matchedLyric = null
        )
        val updatedSong = currentSong.copy(
            matchedLyric = "[00:00.00]lyric",
            durationMs = 3000L,
            mediaUri = "content://downloads/song.flac"
        )

        assertFalse(
            shouldPublishDownloadedSongCatalogUpdate(
                currentSong = currentSong,
                updatedSong = updatedSong
            )
        )
    }

    @Test
    fun `visible downloaded metadata refresh still republishes the catalog`() {
        val currentSong = DownloadedSong(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            coverPath = null
        )
        val updatedSong = currentSong.copy(coverPath = "content://covers/song.jpg")

        assertTrue(
            shouldPublishDownloadedSongCatalogUpdate(
                currentSong = currentSong,
                updatedSong = updatedSong
            )
        )
    }

    @Test
    fun `downloaded song matches active local playback by local media reference`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 3000L,
            coverUrl = null,
            mediaUri = "content://downloads/song.flac"
        )
        val downloadedSong = DownloadedSong(
            id = 7L,
            name = "Other",
            artist = "Other",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            mediaUri = "content://downloads/song.flac"
        )

        assertTrue(matchesDownloadedSong(song, downloadedSong))
    }

    @Test
    fun `downloaded song matches remote playback by stable track identity fallback`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "netease",
            albumId = 99L,
            durationMs = 3000L,
            coverUrl = null,
            mediaUri = "https://example.com/stream"
        )
        val downloadedSong = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            mediaUri = "content://downloads/song.flac"
        )

        assertTrue(matchesDownloadedSong(song, downloadedSong))
    }

    @Test
    fun `downloaded song stable key prevents same name track collisions`() {
        val song = SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Bilibili|2002",
            albumId = 0L,
            durationMs = 3000L,
            coverUrl = null
        )
        val downloadedSong = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.flac",
            fileSize = 10L,
            downloadTime = 20L,
            stableKey = SongItem(
                id = 42L,
                name = "Song",
                artist = "Artist",
                album = "Bilibili|1001",
                albumId = 0L,
                durationMs = 3000L,
                coverUrl = null
            ).stableKey()
        )

        assertFalse(matchesDownloadedSong(song, downloadedSong))
    }

    @Test
    fun `downloaded song catalog preserves stable key`() {
        val song = DownloadedSong(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            filePath = "/music/song.mp3",
            fileSize = 2048L,
            downloadTime = 123456L,
            stableKey = "42|Album|content://song",
            mediaUri = "content://downloads/song.mp3",
            durationMs = 3000L
        )

        val payload = serializeDownloadedSongsCatalog(
            cacheKey = "tree:test",
            songs = listOf(song)
        )

        val restored = deserializeDownloadedSongsCatalog(
            raw = payload,
            expectedCacheKey = "tree:test"
        )

        assertEquals(listOf(song), restored)
    }

    @Test
    fun `completed download finalization rolls back when cancel arrives after audio commit`() {
        assertEquals(
            CompletedDownloadFinalizationAction.ROLLBACK_CANCELLED,
            resolveCompletedDownloadFinalizationAction(
                hasStoredAudio = true,
                cancelled = true
            )
        )
    }

    @Test
    fun `completed download finalization detects missing audio when not cancelled`() {
        assertEquals(
            CompletedDownloadFinalizationAction.COMPLETE_WITHOUT_STORED_AUDIO,
            resolveCompletedDownloadFinalizationAction(
                hasStoredAudio = false,
                cancelled = false
            )
        )
    }

    @Test
    fun `pre existing downloaded audio settles directly instead of finalizing missing completed reference`() {
        assertEquals(
            PreExistingDownloadedAudioAction.DIRECT_SETTLE,
            resolvePreExistingDownloadedAudioAction(hasExistingAudio = true)
        )
        assertEquals(
            PreExistingDownloadedAudioAction.CONTINUE_DOWNLOAD,
            resolvePreExistingDownloadedAudioAction(hasExistingAudio = false)
        )
    }

    @Test
    fun `task mutation ignores stale attempt id but accepts current attempt`() {
        val task = DownloadTask(
            song = SongItem(
                id = 11L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/audio"
            ),
            progress = null,
            status = DownloadStatus.DOWNLOADING,
            attemptId = 99L
        )

        assertFalse(shouldApplyTaskMutation(task, expectedAttemptId = 98L))
        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = 99L))
        assertTrue(shouldApplyTaskMutation(task, expectedAttemptId = null))
    }

    @Test
    fun `active download attempt only matches current unfinished attempt`() {
        val song = SongItem(
            id = 12L,
            name = "Retry",
            artist = "Artist",
            album = "Album",
            albumId = 12L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/retry"
        )
        val queuedTask = DownloadTask(
            song = song,
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 201L
        )
        val completedTask = queuedTask.copy(
            status = DownloadStatus.COMPLETED,
            attemptId = 202L
        )

        assertTrue(isActiveDownloadAttempt(listOf(queuedTask), song.stableKey(), expectedAttemptId = 201L))
        assertFalse(isActiveDownloadAttempt(listOf(queuedTask), song.stableKey(), expectedAttemptId = 200L))
        assertFalse(isActiveDownloadAttempt(listOf(completedTask), song.stableKey(), expectedAttemptId = 202L))
    }

    @Test
    fun `finalizing download task remains cancellable`() {
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = AudioDownloadManager.DownloadProgress(
                songKey = "1|Album|",
                songId = 1L,
                fileName = "song.flac",
                bytesRead = 10L,
                totalBytes = 10L,
                speedBytesPerSec = 0L,
                stage = AudioDownloadManager.DownloadStage.FINALIZING
            ),
            status = DownloadStatus.DOWNLOADING
        )

        assertTrue(isDownloadTaskFinalizing(task))
        assertTrue(isDownloadTaskCancellable(task))
    }

    @Test
    fun `download action stays visible while task is unfinished even if local file is detected`() {
        val task = DownloadTask(
            song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null
            ),
            progress = null,
            status = DownloadStatus.CANCELLED
        )

        assertFalse(
            shouldHideRemoteDownloadAction(
                hasLocalDownload = true,
                task = task
            )
        )
        assertTrue(
            shouldHideRemoteDownloadAction(
                hasLocalDownload = true,
                task = null
            )
        )
    }

    @Test
    fun `lyric only downloaded playback hydration is immediate`() {
        val originalSong = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 3_000L,
            coverUrl = "content://covers/song.jpg",
            mediaUri = "content://audio/song.flac",
            localFileName = "song.flac",
            localFilePath = "content://audio/song.flac"
        )
        val hydratedSong = originalSong.copy(
            matchedLyric = "[00:00.00]lyric",
            matchedTranslatedLyric = "[00:00.00]translated"
        )

        assertFalse(
            shouldUseImmediateDownloadedPlaybackHydration(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
        assertEquals(
            0L,
            resolveDownloadedPlaybackHydrationDelayMs(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
    }

    @Test
    fun `cover changes keep downloaded playback hydration eager`() {
        val originalSong = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 3_000L,
            coverUrl = null,
            mediaUri = "content://audio/song.flac",
            localFileName = "song.flac",
            localFilePath = "content://audio/song.flac"
        )
        val hydratedSong = originalSong.copy(
            coverUrl = "content://covers/song.jpg"
        )

        assertTrue(
            shouldUseImmediateDownloadedPlaybackHydration(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
        assertEquals(
            1_500L,
            resolveDownloadedPlaybackHydrationDelayMs(
                originalSong = originalSong,
                hydratedSong = hydratedSong
            )
        )
    }

    @Test
    fun `applyCancelledStatus keeps cancelled tasks visible for the matching attempt`() {
        val queuedTask = DownloadTask(
            song = SongItem(
                id = 11L,
                name = "Queued",
                artist = "Artist",
                album = "Album",
                albumId = 11L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/queued"
            ),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 101L
        )
        val downloadingTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 12L, name = "Downloading"),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 102L
        )
        val completedTask = queuedTask.copy(
            song = queuedTask.song.copy(id = 13L, name = "Completed"),
            status = DownloadStatus.COMPLETED,
            attemptId = 103L
        )

        val updatedTasks = applyCancelledStatus(
            tasks = listOf(queuedTask, downloadingTask, completedTask),
            cancelledTasks = listOf(queuedTask, downloadingTask)
        )

        assertEquals(3, updatedTasks.size)
        assertEquals(DownloadStatus.CANCELLED, updatedTasks[0].status)
        assertEquals(DownloadStatus.CANCELLED, updatedTasks[1].status)
        assertEquals(DownloadStatus.COMPLETED, updatedTasks[2].status)
    }

    @Test
    fun `applyCancelledStatus ignores stale attempts for the same song`() {
        val song = SongItem(
            id = 21L,
            name = "Retry",
            artist = "Artist",
            album = "Album",
            albumId = 21L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/retry"
        )
        val activeRetryTask = DownloadTask(
            song = song,
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 202L
        )
        val staleCancelledTask = activeRetryTask.copy(
            status = DownloadStatus.DOWNLOADING,
            attemptId = 201L
        )

        val updatedTasks = applyCancelledStatus(
            tasks = listOf(activeRetryTask),
            cancelledTasks = listOf(staleCancelledTask)
        )

        assertEquals(DownloadStatus.QUEUED, updatedTasks.single().status)
        assertEquals(202L, updatedTasks.single().attemptId)
    }

    @Test
    fun `waiting network status keeps queue visible but not active`() {
        val queuedTask = DownloadTask(
            song = recoverySong(id = 31L, name = "Queued"),
            progress = null,
            status = DownloadStatus.QUEUED,
            attemptId = 301L
        )
        val downloadingTask = queuedTask.copy(
            song = recoverySong(id = 32L, name = "Downloading"),
            status = DownloadStatus.DOWNLOADING,
            attemptId = 302L
        )

        val waitingTasks = applyWaitingNetworkStatus(
            tasks = listOf(queuedTask, downloadingTask),
            waitingTasks = listOf(queuedTask, downloadingTask)
        )

        assertEquals(listOf(DownloadStatus.WAITING_NETWORK, DownloadStatus.WAITING_NETWORK), waitingTasks.map { it.status })
        assertEquals(2, countPendingDownloadTasks(waitingTasks))
        assertFalse(hasActiveDownloadTasks(waitingTasks))
        assertFalse(
            hasActiveDownloadOperations(
                tasks = waitingTasks,
                isSingleDownloading = false,
                hasActiveBatchJobs = false
            )
        )
    }

    @Test
    fun `resolveUndeletedManagedReferences only keeps references that still exist`() = runBlocking {
        val remaining = resolveUndeletedManagedReferences(
            requestedReferences = setOf("audio", "cover", "lyric"),
            deletedReferences = setOf("audio")
        ) { reference ->
            reference == "cover"
        }

        assertEquals(setOf("cover"), remaining)
    }

    @Test
    fun `mergeManagedRequestedReferences removes duplicates across songs`() {
        val merged = mergeManagedRequestedReferences(
            listOf(
                linkedSetOf("audio-a", "cover-shared", "lyric-a"),
                linkedSetOf("audio-b", "cover-shared", "lyric-b")
            )
        )

        assertEquals(
            linkedSetOf("audio-a", "cover-shared", "lyric-a", "audio-b", "lyric-b"),
            merged
        )
    }

    @Test
    fun `groupRemainingManagedReferencesByIdentity only keeps remaining references per song`() {
        val remainingBySong = groupRemainingManagedReferencesByIdentity(
            requestedReferencesByIdentity = mapOf(
                "song-a" to setOf("audio-a", "cover-shared"),
                "song-b" to setOf("audio-b", "cover-shared", "lyric-b")
            ),
            remainingReferences = setOf("cover-shared", "lyric-b")
        )

        assertEquals(
            mapOf(
                "song-a" to setOf("cover-shared"),
                "song-b" to setOf("cover-shared", "lyric-b")
            ),
            remainingBySong
        )
    }

    @Test
    fun `shouldRepairMetadataLessManagedDownload returns true for fallback parsed source prefix`() {
        assertTrue(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("One Day"),
                expectedArtists = setOf("Matisyahu"),
                expectedDurationMs = 205_000L,
                actualTitle = "Matisyahu - One Day",
                actualArtist = "netease",
                actualDurationMs = 205_000L
            )
        )
    }

    @Test
    fun `shouldRepairMetadataLessManagedDownload keeps valid metadata less legacy file`() {
        assertFalse(
            shouldRepairMetadataLessManagedDownload(
                expectedTitles = setOf("One Day"),
                expectedArtists = setOf("Matisyahu"),
                expectedDurationMs = 205_000L,
                actualTitle = "One Day",
                actualArtist = "Matisyahu",
                actualDurationMs = 204_500L
            )
        )
    }

    @Test
    fun `download recovery merges queued snapshot and partial files without losing queued songs`() {
        val firstSong = recoverySong(id = 901L, name = "First")
        val secondSong = recoverySong(id = 902L, name = "Second")
        val queuedDownloads = listOf(
            ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = firstSong.stableKey(),
                song = firstSong,
                order = 0,
                queuedAtMs = 10L,
                requiresWifiNetwork = false
            ),
            ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = secondSong.stableKey(),
                song = secondSong,
                order = 1,
                queuedAtMs = 10L
            )
        )
        val partialFile = File("first.partial")

        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = queuedDownloads,
            resumableDownloads = listOf(
                ManagedDownloadStorage.PendingResumableDownload(
                    song = firstSong.copy(durationMs = 2_000L),
                    workingFile = partialFile
                )
            )
        )

        assertEquals(listOf(firstSong.stableKey(), secondSong.stableKey()), merged.map { it.song.stableKey() })
        assertEquals(partialFile, merged.first().workingFile)
        assertEquals(2_000L, merged.first().song.durationMs)
        assertFalse(merged.first().requiresWifiNetwork)
        assertNull(merged[1].workingFile)
    }

    @Test
    fun `download recovery preserves resumable operation identity`() {
        val song = recoverySong(id = 905L, name = "Operation")
        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = emptyList(),
            resumableDownloads = listOf(
                ManagedDownloadStorage.PendingResumableDownload(
                    song = song,
                    workingFile = File("operation.partial"),
                    operationId = "operation-905"
                )
            )
        )

        assertEquals("operation-905", merged.single().operationId)
    }

    @Test
    fun `download recovery preserves queued operation identity when no partial exists`() {
        val song = recoverySong(id = 906L, name = "Queued operation")
        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = listOf(
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = song.stableKey(),
                    song = song,
                    order = 0,
                    queuedAtMs = 10L,
                    operationId = "operation-906"
                )
            ),
            resumableDownloads = emptyList()
        )

        assertEquals("operation-906", merged.single().operationId)
    }

    @Test
    fun `taskless finalization recovery keeps its publication permission through enrichment`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/GlobalDownloadManager.kt"
        ).readText()
        val finalizationBody = source.substringAfter(
            "private suspend fun finalizeCompletedDownload"
        ).substringBefore("private suspend fun completeCoreDownloadAndEnqueueEnrichment")
        val coreCommitBody = source.substringAfter(
            "private suspend fun completeCoreDownloadAndEnqueueEnrichment"
        ).substringBefore("private suspend fun enrichCoreCommittedDownload")
        val enrichmentBody = source.substringAfter(
            "private suspend fun enrichCoreCommittedDownload"
        ).substringBefore("private suspend fun publishFinalizedDownload")
        val networkRecoveryBody = source.substringAfter(
            "fun recoverPendingDownloadsForNetworkRestored"
        ).substringBefore("private fun tryBeginPendingDownloadRecovery")

        assertTrue(finalizationBody.contains("allowMissingTask = allowMissingTask"))
        assertTrue(coreCommitBody.contains("allowMissingTask = allowMissingTask"))
        assertTrue(enrichmentBody.contains("allowMissingTask = allowMissingTask"))
        assertTrue(
            networkRecoveryBody.indexOf("recoverPendingAudioWritesFromRoot(") <
                networkRecoveryBody.indexOf("if (!hasPendingRecoveryCandidates(appContext))")
        )
        assertTrue(
            networkRecoveryBody.indexOf("recoverUnfinalizedPublishedAudioFromRoot(") <
                networkRecoveryBody.indexOf("if (!hasPendingRecoveryCandidates(appContext))")
        )
        assertTrue(
            networkRecoveryBody.indexOf("repairFinalizedDownloadedCoversFromRoot(") <
                networkRecoveryBody.indexOf("if (!hasPendingRecoveryCandidates(appContext))")
        )
    }

    @Test
    fun `interrupted operations remain recoverable unless user explicitly stopped them`() {
        listOf("RUNNING", "QUEUED", "RETRYABLE").forEach { state ->
            assertFalse(
                shouldRequireExplicitResume(
                    userInitiated = true,
                    state = state,
                    hasPendingUidtJob = false
                )
            )
        }
        assertFalse(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RUNNING",
                hasPendingUidtJob = true
            )
        )
        assertFalse(
            shouldRequireExplicitResume(
                userInitiated = false,
                state = "RUNNING",
                hasPendingUidtJob = false
            )
        )
        assertTrue(
            shouldRequireExplicitResume(
                userInitiated = true,
                state = "RUNNING",
                hasPendingUidtJob = true,
                stopRequestedByUser = true
            )
        )
    }

    @Test
    fun `download recovery marks cancelled candidates so stale partial files do not resurrect`() {
        val cancelledSong = recoverySong(id = 911L, name = "Cancelled")
        val queuedSong = recoverySong(id = 912L, name = "Queued")
        val partialFile = File("cancelled.partial")

        val merged = mergePendingDownloadRecoveryCandidates(
            queuedDownloads = listOf(
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = cancelledSong.stableKey(),
                    song = cancelledSong,
                    order = 0,
                    queuedAtMs = 10L
                ),
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = queuedSong.stableKey(),
                    song = queuedSong,
                    order = 1,
                    queuedAtMs = 10L
                )
            ),
            resumableDownloads = listOf(
                ManagedDownloadStorage.PendingResumableDownload(
                    song = cancelledSong,
                    workingFile = partialFile
                )
            ),
            cancelledKeys = setOf(cancelledSong.stableKey())
        )

        assertEquals(listOf(true, false), merged.map { it.cancelled })
        assertEquals(partialFile, merged.first().workingFile)
        assertEquals(listOf(cancelledSong.stableKey(), queuedSong.stableKey()), merged.map { it.song.stableKey() })
    }

    private fun recoverySong(id: Long, name: String): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/$id"
        )
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
