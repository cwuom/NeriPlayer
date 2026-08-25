package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        assertTrue(downloadedPlayback.contains("resolveFinalizedManagedAudioSnapshot"))
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
        assertTrue(immediateHostCancellationIndex in 0 until journalIndex)
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
            clearAllBody.indexOf("downloadClearVisibility.begin(clearToken)") <
                clearAllBody.indexOf("taskStore.clearAllTasks()")
        )
        assertTrue(managerSource.contains("downloadClearVisibility.finish(clearToken)"))
        assertTrue(
            screenSource.contains(
                "LaunchedEffect(context, taskPresenceKey, isClearingDownloadTasks)"
            )
        )
        assertTrue(screenSource.contains("if (isClearingDownloadTasks)"))
        assertTrue(screenSource.contains("R.string.download_clearing_tasks"))
        assertTrue(screenSource.contains("visibleDownloadProgressTasks(downloadTasks)"))
        assertFalse(screenSource.contains("item(key = \"queued-summary\")"))
        assertTrue(screenSource.contains("R.string.download_progress_with_percentage"))
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

        val activateIndex = clearAllBody.indexOf("activateDownloadClearFence(appContext)")
        val immediateStopIndex = clearAllBody.indexOf("stopDownloadExecutionImmediately(")
        val firstTaskClearIndex = clearAllBody.indexOf("taskStore.clearAllTasks()")
        val journalIndex = clearAllBody.indexOf(
            "requestAllDownloadOperationCancellation(appContext)"
        )
        val clearFenceIndex = clearAllBody.indexOf("clearDownloadClearFence(")
        val clearRequestIndex = clearAllBody.indexOf(
            "PersistentDownloadClearFenceStore.beginClear()"
        )

        assertTrue(clearRequestIndex >= 0)
        assertTrue(clearRequestIndex > clearAllBody.indexOf("downloadAdmissionGate.beginClear()"))
        assertTrue(activateIndex >= 0)
        assertTrue(immediateStopIndex > activateIndex)
        assertTrue(firstTaskClearIndex > activateIndex)
        assertTrue(immediateStopIndex < journalIndex)
        assertTrue(journalIndex > firstTaskClearIndex)
        assertTrue(clearFenceIndex > journalIndex)
        assertTrue(
            clearAllBody.indexOf("return@runClear") < clearFenceIndex
        )
        assertTrue(
            initializeBody.indexOf("PersistentDownloadClearFenceStore.isActive(appContext)") <
                initializeBody.indexOf("recoverPendingAudioWritesFromRoot(appContext)")
        )
        assertTrue(source.contains("private suspend fun activateDownloadClearFence"))
        assertTrue(source.contains("private fun stopDownloadExecutionImmediately"))
        assertTrue(source.contains("private suspend fun clearDownloadClearFence"))
        assertTrue(source.contains("if (isDownloadClearFenceActive(appContext))"))
        assertTrue(clearAllBody.contains("while (true)"))
        assertTrue(clearAllBody.contains("下载清空流程失败，保持栅栏并重试"))
        assertTrue(
            clearAllBody.lastIndexOf("retrying failed download clear") <
                clearAllBody.indexOf("clearDownloadClearFence(")
        )
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
            "downloadAdmissionGate.admit(admissionTicket)"
        )
        val ensureIndex = executionBody.indexOf("taskStore.ensureDownloadTasks(")
        val upsertIndex = executionBody.indexOf("DownloadExecutionRoomStore.upsert(")
        val generationIndex = executionBody.indexOf(
            "admittedRequestGeneration = reuseOrBeginDownloadRequestGeneration"
        )

        assertTrue(executionBody.indexOf("val admissionTicket = downloadAdmissionGate.ticket()") >= 0)
        assertTrue(admissionIndex >= 0)
        assertTrue(ensureIndex > admissionIndex)
        assertTrue(upsertIndex > ensureIndex)
        assertTrue(generationIndex > upsertIndex)
        assertTrue(
            executionBody.contains(
                "taskStore.removeDownloadTask(\n                    songKey = songKey,\n                    expectedAttemptId = effectiveAttemptId"
            )
        )
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
                queuedAtMs = 10L
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
            networkRecoveryBody.indexOf("recoverPendingAudioWritesFromRoot(appContext)") <
                networkRecoveryBody.indexOf("if (!hasPendingRecoveryCandidates(appContext))")
        )
        assertTrue(
            networkRecoveryBody.indexOf("recoverUnfinalizedPublishedAudioFromRoot(appContext)") <
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
