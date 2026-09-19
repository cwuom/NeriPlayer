package moe.ouom.neriplayer.core.download.manager.catalog

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadArtifactRemovalResult
import moe.ouom.neriplayer.core.download.ManagedDownloadSongDeletePlan
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.buildDownloadedSongCatalogIndex
import moe.ouom.neriplayer.core.download.upsertDownloadedSongCatalog
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshPreserveReason
import moe.ouom.neriplayer.core.download.model.remoteSourceStableKeyOrNull
import moe.ouom.neriplayer.core.download.policy.observeDownloadedSongReferencesFromSnapshot
import moe.ouom.neriplayer.core.download.policy.partitionForBoundedParallelism
import moe.ouom.neriplayer.core.download.policy.withDownloadClearRoomTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.FastIndexPersistenceRequest
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactState
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuildItem
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuilder
import moe.ouom.neriplayer.core.download.catalog.downloadedSongNewestFirstComparator
import moe.ouom.neriplayer.core.download.catalog.projectDownloadedSongMetadata
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexRebuildToken
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanDecision
import moe.ouom.neriplayer.core.download.reconcile.EmptyScanObservation
import moe.ouom.neriplayer.core.download.reconcile.ScanConfidence
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey


internal fun GlobalDownloadManager.requestLocalScanLocked(
    context: Context,
    forceRefresh: Boolean
) {
    if (refreshJob?.isActive == true) {
        // 同一目录同一时刻只枚举一次。普通刷新直接共享当前结果；只有
        // 当前轮用了缓存而新请求明确要求强刷时，才保留一轮后续枚举
        if (forceRefresh && !activeRefreshForceRefresh) {
            pendingRefresh = true
            pendingForceRefresh = true
        }
        return
    }

    activeRefreshForceRefresh = forceRefresh
    refreshJob = scope.launch {
        var latestOutcome: ManagedLibraryRefreshOutcome =
            ManagedLibraryRefreshOutcome.Failed("download directory scan did not run")
        try {
            var nextForceRefresh = forceRefresh
            while (true) {
                latestOutcome = reloadDownloadedSongs(
                    context,
                    forceRefresh = nextForceRefresh
                )
                nextForceRefresh = consumePendingRefreshRequest() ?: break
            }
            if (latestOutcome is ManagedLibraryRefreshOutcome.Published) {
                LegacyJsonCleanupScheduler.scheduleQuarantineRecovery(context)
            }
            val retryState = ManagedLibraryProcessingCoordinator.state.value
            if (shouldCompleteProcessingAfterCatalogPublish(retryState)) {
                retryState.operationId?.let { operationId ->
                    if (latestOutcome is ManagedLibraryRefreshOutcome.Published) {
                        ManagedLibraryProcessingCoordinator.complete(context, operationId)
                    } else {
                        ManagedLibraryProcessingCoordinator.waitingForRetry(
                            context,
                            operationId
                        )
                    }
                }
            }
        } finally {
            val (restartForceRefresh, waiters) = synchronized(this) {
                refreshJob = null
                activeRefreshForceRefresh = false
                if (pendingRefresh) {
                    val queuedForceRefresh = pendingForceRefresh
                    pendingRefresh = false
                    pendingForceRefresh = false
                    queuedForceRefresh to emptyList()
                } else {
                    null to refreshWaiters.toList()
                }
            }
            if (restartForceRefresh != null) {
                synchronized(this) {
                    requestLocalScanLocked(context, restartForceRefresh)
                }
            } else {
                waiters.forEach { waiter -> waiter.complete(latestOutcome) }
            }
        }
    }
}

internal suspend fun GlobalDownloadManager.reloadDownloadedSongs(
    context: Context,
    forceRefresh: Boolean = false
): ManagedLibraryRefreshOutcome {
    if (isDownloadClearFenceActive(context)) {
        NPLogger.d(TAG, "下载清空栅栏生效，保留现有 catalog 并延后目录扫描")
        return ManagedLibraryRefreshOutcome.Preserved(
            ManagedLibraryRefreshPreserveReason.DOWNLOAD_CLEAR_IN_PROGRESS
        )
    }
    if (isDownloadedSongDeletionActive()) {
        NPLogger.d(TAG, "下载删除进行中，延后目录扫描")
        if (!awaitAllDownloadedSongDeletions()) {
            scheduleCatalogReconcile(context, forceRefresh = true)
            return ManagedLibraryRefreshOutcome.Preserved(
                ManagedLibraryRefreshPreserveReason.DOWNLOAD_CLEAR_IN_PROGRESS
            )
        }
        return reloadDownloadedSongs(context, forceRefresh = true)
    }
    isRefreshingMutable.value = true
    try {
        val scanStartedAtNs = System.nanoTime()
        val metadataRevisionAtScanStart = downloadedSongMetadataRevision.get()
        val catalogRevisionAtScanStart = downloadedSongCatalogPersistenceRevision.get()
        val fastIndexRebuildToken = runCatching {
            ManagedDownloadStorage.captureFastIndexRebuildToken(context)
        }.onFailure { error ->
            NPLogger.w(TAG, "无法建立 Managed SAF fast index 扫描令牌: ${error.message}")
        }.getOrNull()
        var snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(
            context = context,
            forceRefresh = forceRefresh
        )
        snapshot = ManagedDownloadStorage.refreshDownloadSidecarSnapshot(
            context = context,
            snapshot = snapshot,
            forceRefresh = forceRefresh
        )
        if (isDownloadedSongDeletionActive()) {
            NPLogger.d(TAG, "下载删除进行中，丢弃已完成的目录扫描")
            if (!awaitAllDownloadedSongDeletions()) {
                scheduleCatalogReconcile(context, forceRefresh = true)
                return ManagedLibraryRefreshOutcome.Preserved(
                    ManagedLibraryRefreshPreserveReason.DOWNLOAD_CLEAR_IN_PROGRESS
                )
            }
            return reloadDownloadedSongs(context, forceRefresh = true)
        }
        val rebuildPlan = ManagedLibraryRebuilder.plan(snapshot)
        val songs = rebuildDownloadedSongs(
            context = context,
            snapshot = snapshot,
            rebuildPlan = rebuildPlan,
            failureLogPrefix = "解析下载文件失败",
            verifySnapshotReferences = false
        )
        val scanElapsedMs = (System.nanoTime() - scanStartedAtNs) / 1_000_000L
        val scanMessage =
            "Managed SAF 刷新扫描完成: elapsedMs=$scanElapsedMs, " +
                "root=${snapshot.audioEntries.size}, metadata=${snapshot.metadataByAudioName.size}, " +
                "songs=${songs.size}, sidecarComplete=${snapshot.sidecarEntriesComplete}, " +
                "forceRefresh=$forceRefresh"
        if (scanElapsedMs > DOWNLOAD_LIBRARY_SCAN_TARGET_MS) {
            NPLogger.w(TAG, "$scanMessage, targetMs=$DOWNLOAD_LIBRARY_SCAN_TARGET_MS")
        } else {
            NPLogger.d(TAG, "$scanMessage, targetMs=$DOWNLOAD_LIBRARY_SCAN_TARGET_MS")
        }
        if (fastIndexRebuildToken != null) {
            scheduleFastIndexPersistence(
                snapshot = snapshot,
                rebuildToken = fastIndexRebuildToken
            )
        }
        if (!snapshot.rootEntriesComplete) {
            NPLogger.w(
                TAG,
                "下载目录根项扫描不完整，保留既有 catalog 并等待重扫: " +
                    "observed=${songs.size}, forceRefresh=$forceRefresh"
            )
            if (!forceRefresh) {
                scheduleCatalogReconcile(context, forceRefresh = true)
            }
            return ManagedLibraryRefreshOutcome.Preserved(
                ManagedLibraryRefreshPreserveReason.INCOMPLETE_ROOT_ENUMERATION
            )
        }
        var retryAfterDeletion = false
        var refreshOutcome: ManagedLibraryRefreshOutcome =
            ManagedLibraryRefreshOutcome.Preserved(
                ManagedLibraryRefreshPreserveReason.SUPERSEDED_BY_METADATA_CHANGE
            )
        downloadedSongMetadataSyncMutex.withLock {
            if (isDownloadedSongDeletionActive()) {
                NPLogger.d(TAG, "下载删除进行中，拒绝发布过期目录扫描")
                retryAfterDeletion = true
                return@withLock
            }
            if (metadataRevisionAtScanStart != downloadedSongMetadataRevision.get()) {
                NPLogger.d(TAG, "跳过过期下载目录扫描结果: metadata changed during scan")
                scheduleCatalogReconcile(context, forceRefresh = true)
                return@withLock
            }

            val existingSongs = downloadedSongsMutable.value
            // 记录本次扫描的存储根标识，用于和已有 catalog 的根目录比对
            val scanRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
            // 同一根目录的扫描突然变空时先保留旧 catalog，因为 DocumentsProvider
            // 可能只返回了空游标或暂时无法列举。换根后的空目录可以直接采用
            // 只有完整扫描确认目录确实为空，或者应用内删除已经发布空结果时才清空旧 catalog
            if (songs.isEmpty() && existingSongs.isNotEmpty()) {
                val storageRootResolvable = ManagedDownloadStorage.isStorageRootResolvable(context)
                val scanMatchesCatalogRoot = downloadedSongCatalogRootKey == scanRootKey
                val confidence = if (storageRootResolvable) {
                    ScanConfidence.COMPLETE
                } else {
                    ScanConfidence.ROOT_UNAVAILABLE
                }
                // 根目录已经完成一次批量枚举，直接复用快照中的音频引用。
                // 空结果路径不能再对每首歌执行 SAF inspect，否则 1000 首歌曲会
                // 退化为 1000 次 Binder 往返，并且 Provider 瞬时抖动会放大为误判
                val referenceCoverage = observeDownloadedSongReferencesFromSnapshot(
                    existingSongs = existingSongs,
                    snapshot = snapshot
                )
                val emptyDecision = if (scanMatchesCatalogRoot) {
                    managedLibraryReconciler.observeEmpty(
                        observation = EmptyScanObservation(
                            rootKey = scanRootKey,
                            confidence = confidence,
                            isUncached = forceRefresh,
                            knownReferenceCount = referenceCoverage.knownReferenceCount,
                            missingReferenceCount = referenceCoverage.missingReferenceCount,
                            scanId = emptyScanSequence.incrementAndGet()
                        ),
                        existingCount = existingSongs.size
                    )
                } else {
                    managedLibraryReconciler.reset()
                    EmptyScanDecision.CLEAR_CONFIRMED
                }
                if (
                    scanMatchesCatalogRoot &&
                        emptyDecision != EmptyScanDecision.CLEAR_CONFIRMED
                ) {
                    NPLogger.w(
                        TAG,
                        "扫描结果为空但既有目录非空、存储根可解析且同 root，判定为可疑空结果，保留既有目录: " +
                            "existing=${existingSongs.size}, forceRefresh=$forceRefresh"
                    )
                    // 仅在本次使用了缓存 (非强制刷新) 时安排一次强制重扫尝试恢复
                    // 若本次已是强制刷新仍为空, 则不再重排, 避免瞬时失败演化成无限重扫循环
                    if (!forceRefresh) {
                        scheduleCatalogReconcile(context, forceRefresh = true)
                    }
                    refreshOutcome = ManagedLibraryRefreshOutcome.Preserved(
                        ManagedLibraryRefreshPreserveReason.SUSPICIOUS_EMPTY_RESULT
                    )
                    return@withLock
                }
                runCatching {
                    managedDownloadArtifactCoordinator.reconcileEmptyConfirmed(
                        context = context,
                        rootKey = scanRootKey
                    )
                }.onFailure { error ->
                    NPLogger.w(TAG, "确认下载目录为空后清理 artifact 失败: ${error.message}")
                }
            } else if (songs.isNotEmpty()) {
                managedLibraryReconciler.reset()
            }
            runCatching {
                managedDownloadArtifactCoordinator.reconcileCatalog(context, songs)
            }.onFailure { error ->
                NPLogger.w(TAG, "扫描后回填下载 artifact 索引失败: ${error.message}")
            }
            if (existingSongs != songs) {
                if (
                    !publishScannedDownloadedSongsIfCurrent(
                        context = context,
                        songs = songs,
                        scanRootKey = scanRootKey,
                        expectedCatalogRevision = catalogRevisionAtScanStart,
                        expectedMetadataRevision = metadataRevisionAtScanStart
                    )
                ) {
                    NPLogger.d(
                        TAG,
                        "跳过与 core/catalog 增量发布冲突的下载目录扫描: " +
                            "scanRoot=$scanRootKey"
                    )
                    scheduleCatalogReconcile(context, forceRefresh = true)
                    refreshOutcome = ManagedLibraryRefreshOutcome.Preserved(
                        ManagedLibraryRefreshPreserveReason.SUPERSEDED_BY_METADATA_CHANGE
                    )
                    return@withLock
                }
            } else if (!downloadedSongCatalogReady) {
                synchronized(downloadedSongCatalogMutationLock) {
                    if (
                        catalogRevisionAtScanStart !=
                            downloadedSongCatalogPersistenceRevision.get() ||
                            metadataRevisionAtScanStart != downloadedSongMetadataRevision.get()
                    ) {
                        NPLogger.d(
                            TAG,
                            "跳过与 core/catalog 增量发布冲突的下载目录就绪标记: " +
                                "scanRoot=$scanRootKey"
                        )
                        scheduleCatalogReconcile(context, forceRefresh = true)
                        refreshOutcome = ManagedLibraryRefreshOutcome.Preserved(
                            ManagedLibraryRefreshPreserveReason.SUPERSEDED_BY_METADATA_CHANGE
                        )
                        return@withLock
                    }
                    downloadedSongCatalogIndex = buildDownloadedSongCatalogIndex(songs)
                    downloadedSongCatalogReady = true
                    downloadedSongCatalogRootKey = scanRootKey
                }
            }
            refreshOutcome = ManagedLibraryRefreshOutcome.Published(
                rootKey = scanRootKey,
                songCount = songs.size
            )
        }
        if (retryAfterDeletion) {
            if (!awaitAllDownloadedSongDeletions()) {
                scheduleCatalogReconcile(context, forceRefresh = true)
                return ManagedLibraryRefreshOutcome.Preserved(
                    ManagedLibraryRefreshPreserveReason.DOWNLOAD_CLEAR_IN_PROGRESS
                )
            }
            return reloadDownloadedSongs(context, forceRefresh = true)
        }
        return refreshOutcome
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.e(TAG, "扫描已下载文件失败: ${error.message}", error)
        return ManagedLibraryRefreshOutcome.Failed(
            error.message?.takeIf(String::isNotBlank)
                ?: error::class.java.simpleName
        )
    } finally {
        isRefreshingMutable.value = false
    }
}

internal fun GlobalDownloadManager.buildSongFromDurableMetadata(
    audio: ManagedDownloadStorage.StoredEntry,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): SongItem? {
    val stableKey = metadata?.stableKey?.takeIf(String::isNotBlank) ?: return null
    return SongItem(
        id = metadata.songId ?: 0L,
        name = metadata.name ?: audio.nameWithoutExtension,
        artist = metadata.artist ?: "",
        album = metadata.identityAlbum ?: metadata.album ?: "local",
        albumId = 0L,
        durationMs = metadata.durationMs,
        coverUrl = metadata.coverUrl,
        mediaUri = resolveDurableMetadataPlaybackReference(audio, metadata),
        matchedLyric = metadata.matchedLyric,
        matchedTranslatedLyric = metadata.matchedTranslatedLyric,
        matchedRomanizedLyric = metadata.matchedRomanizedLyric,
        matchedSongId = metadata.matchedSongId,
        customCoverUrl = metadata.customCoverUrl,
        customName = metadata.customName,
        customArtist = metadata.customArtist,
        originalName = metadata.originalName,
        originalArtist = metadata.originalArtist,
        originalCoverUrl = metadata.originalCoverUrl,
        originalLyric = metadata.originalLyric,
        originalTranslatedLyric = metadata.originalTranslatedLyric,
        originalRomanizedLyric = metadata.originalRomanizedLyric,
        channelId = metadata.channelId,
        audioId = metadata.audioId,
        subAudioId = metadata.subAudioId,
        playlistContextId = metadata.playlistContextId,
        sourceStableKey = stableKey,
        localFileName = audio.name,
        localFilePath = audio.localFilePath,
        addedAt = metadata.createdAtMs ?: metadata.downloadTimeMs ?: audio.lastModifiedMs
    )
}

internal suspend fun GlobalDownloadManager.publishDownloadedSongMetadataFallback(
    context: Context,
    currentSongs: List<DownloadedSong>,
    catalogSong: DownloadedSong?,
    storedAudio: ManagedDownloadStorage.StoredEntry?,
    song: SongItem,
    reason: String
): Boolean {
    val projectedSong = catalogSong
        ?.let { existing -> projectDownloadedSongMetadata(existing, song) }
        ?: storedAudio?.let { audio -> buildOptimisticDownloadedSong(song, audio) }
        ?: return false
    val refreshedSongs = upsertDownloadedSongCatalog(currentSongs, projectedSong)
    publishDownloadedSongs(context, refreshedSongs, persistCatalog = true)
    downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
    NPLogger.w(
        TAG,
        "下载元数据目录使用已验证标签更新: file=${projectedSong.filePath}, reason=$reason"
    )
    return true
}

internal fun GlobalDownloadManager.hasExpectedDownloadedSongMetadata(
    downloadedSong: DownloadedSong,
    song: SongItem
): Boolean {
    return downloadedSong.name == song.name &&
        downloadedSong.artist == song.artist &&
        downloadedSong.album == song.album &&
        downloadedSong.coverUrl == song.coverUrl &&
        downloadedSong.customCoverUrl == song.customCoverUrl &&
        downloadedSong.customName == song.customName &&
        downloadedSong.customArtist == song.customArtist &&
        downloadedSong.originalCoverUrl == song.originalCoverUrl &&
        (
            song.sourceStableKey.isNullOrBlank() ||
                downloadedSong.remoteSourceStableKeyOrNull() == song.sourceStableKey
        )
}

internal fun GlobalDownloadManager.scheduleFastIndexPersistence(
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    rebuildToken: ManagedLibraryFastIndexRebuildToken
) {
    val request = FastIndexPersistenceRequest(
        snapshot = snapshot,
        rebuildToken = rebuildToken
    )
    synchronized(fastIndexPersistenceLock) {
        pendingFastIndexPersistence = request
        if (fastIndexPersistenceJob?.isActive == true) {
            return
        }
        val job = scope.launch {
            while (true) {
                val nextRequest = synchronized(fastIndexPersistenceLock) {
                    pendingFastIndexPersistence.also {
                        pendingFastIndexPersistence = null
                        if (it == null) {
                            fastIndexPersistenceJob = null
                        }
                    }
                } ?: break
                try {
                    val appContext = AppContainer.applicationContext
                    if (isDownloadClearFenceActive(appContext)) {
                        continue
                    }
                    val persisted = ManagedDownloadStorage.persistFastIndex(
                        context = appContext,
                        snapshot = nextRequest.snapshot,
                        rebuildToken = nextRequest.rebuildToken
                    )
                    if (!persisted) {
                        NPLogger.d(
                            TAG,
                            "扫描期间 fast index 已有增量变更，跳过过期全量写入"
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NPLogger.w(TAG, "写入 Managed SAF fast index 失败: ${error.message}")
                }
            }
        }
        fastIndexPersistenceJob = job
        job.invokeOnCompletion {
            synchronized(fastIndexPersistenceLock) {
                if (fastIndexPersistenceJob === job) {
                    fastIndexPersistenceJob = null
                }
            }
        }
    }
}

internal suspend fun GlobalDownloadManager.rebuildDownloadedSongs(
    context: Context,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
    rebuildPlan: List<ManagedLibraryRebuildItem>,
    failureLogPrefix: String,
    verifySnapshotReferences: Boolean
): List<DownloadedSong> {
    val songs = ArrayList<DownloadedSong>(rebuildPlan.size)
    partitionForBoundedParallelism(
        items = rebuildPlan,
        maxParallelism = DOWNLOADED_SONG_BUILD_PARALLELISM
    ).forEach { batch ->
        val builtBatch = coroutineScope {
            batch.map { rebuildItem ->
                async(downloadedSongBuildDispatcher) {
                    try {
                        buildDownloadedSong(
                            context = context,
                            storedAudio = rebuildItem.audio,
                            snapshot = snapshot,
                            existingDownloadTime = rebuildItem.logicalTimeMs,
                            loadLyricContents = false,
                            resolveLyricFallbacks = false,
                            allowSlowLocalInspection = false,
                            verifySnapshotReferences = verifySnapshotReferences
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        NPLogger.w(
                            TAG,
                            "$failureLogPrefix: ${rebuildItem.audio.name} - ${error.message}"
                        )
                        null
                    }
                }
            }.awaitAll()
        }
        builtBatch.filterNotNullTo(songs)
    }
    return songs.sortedWith(downloadedSongNewestFirstComparator)
}

internal suspend fun GlobalDownloadManager.markDownloadArtifactFinalized(
    context: Context,
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    leaseId: String?
): Boolean {
    return runCatching {
        managedDownloadArtifactCoordinator.markFinalized(
            context = context,
            song = song,
            storedAudio = storedAudio,
            expectedLeaseId = leaseId
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "写入下载 artifact 完成状态失败: ${error.message}")
    }.getOrNull()?.isApplied == true
}

internal suspend fun GlobalDownloadManager.markDownloadArtifactRetryable(
    context: Context,
    song: SongItem,
    leaseId: String?,
    errorCode: String
) {
    runCatching {
        managedDownloadArtifactCoordinator.markRetryable(
            context = context,
            song = song,
            expectedLeaseId = leaseId,
            errorCode = errorCode
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "写入下载 artifact 重试状态失败: ${error.message}")
    }
}

internal suspend fun GlobalDownloadManager.markDownloadArtifactWaitingForStorage(
    context: Context,
    song: SongItem,
    leaseId: String?,
    errorCode: String
) {
    try {
        managedDownloadArtifactCoordinator.markWaitingForStorage(
            context = context,
            song = song,
            expectedLeaseId = leaseId,
            errorCode = errorCode
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(TAG, "写入下载 artifact 空间等待状态失败: ${error.message}")
    }
}

internal suspend fun GlobalDownloadManager.markDownloadArtifactRepairRequired(
    context: Context,
    song: SongItem,
    leaseId: String?,
    errorCode: String
) {
    runCatching {
        managedDownloadArtifactCoordinator.markRepairRequired(
            context = context,
            song = song,
            expectedLeaseId = leaseId,
            errorCode = errorCode
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "写入下载 artifact 修复状态失败: ${error.message}")
    }
}

internal suspend fun GlobalDownloadManager.markDownloadArtifactMissingConfirmed(
    context: Context,
    song: SongItem,
    errorCode: String?
) {
    runCatching {
        managedDownloadArtifactCoordinator.markMissingConfirmed(
            context = context,
            song = song,
            errorCode = errorCode
        )
    }.onFailure { error ->
        NPLogger.w(TAG, "写入下载 artifact 缺失确认状态失败: ${error.message}")
    }
}

internal fun GlobalDownloadManager.handOffDownloadArtifactLeaseToTaskClear(
    context: Context,
    songKey: String,
    operationId: String?,
    expectedLeaseId: String
): Boolean {
    if (
        !PersistentDownloadClearFenceStore.hasPersistedFence(context) ||
            !PersistentDownloadClearFenceStore.isTaskProgressActive(context) ||
            !PersistentDownloadClearFenceStore.isBlocked(
                context = context,
                stableKey = songKey,
                operationId = operationId
            )
    ) {
        return false
    }
    managedDownloadArtifactLeases.remove(songKey, expectedLeaseId)
    return true
}

internal suspend fun GlobalDownloadManager.releaseDownloadArtifactClaim(
    context: Context,
    song: SongItem,
    expectedLeaseId: String
) {
    val songKey = song.stableKey()
    managedDownloadArtifactLeases.remove(songKey, expectedLeaseId)
    suspend fun persistCancellation() {
        val settled = managedDownloadArtifactCoordinator.settleLeaseAnyRoot(
            context = context,
            song = song,
            expectedLeaseId = expectedLeaseId,
            requestedState = ManagedDownloadArtifactState.CANCELLED,
            errorCode = "USER_CANCELLED"
        )
        if (!settled) {
            NPLogger.d(
                TAG,
                "取消时未找到匹配 artifact lease，保留当前根对账: " +
                    "song=$songKey"
            )
        }
    }
    try {
        if (PersistentDownloadClearFenceStore.isActive(context)) {
            // 清空期间取消回调也要把 artifact 状态落盘，避免重启后重新出现旧任务
            withContext(NonCancellable) {
                persistCancellation()
            }
        } else {
            persistCancellation()
        }
    } catch (error: CancellationException) {
        if (!PersistentDownloadClearFenceStore.isActive(context)) {
            throw error
        }
        // 栅栏刚在 Provider 调用期间生效时，补一次不可取消的持久化
        try {
            withContext(NonCancellable) {
                persistCancellation()
            }
        } catch (retryError: CancellationException) {
            NPLogger.w(
                TAG,
                "清空期间 artifact 取消状态未确认，保留恢复凭据: " +
                    "song=$songKey, error=${retryError.message}",
                retryError
            )
        } catch (retryError: Exception) {
            NPLogger.w(
                TAG,
                "清空期间 artifact 取消状态未确认，保留恢复凭据: " +
                    "song=$songKey, error=${retryError.message}",
                retryError
            )
        }
    } catch (error: Exception) {
        NPLogger.w(TAG, "释放下载 artifact lease 失败: ${error.message}")
    }
}

internal suspend fun GlobalDownloadManager.releaseDownloadArtifactAfterExecutionOwnershipLoss(
    context: Context,
    song: SongItem,
    operationId: String,
    expectedLeaseId: String,
    boundedRoomWait: Boolean = false
) {
    if (
        !boundedRoomWait &&
            handOffDownloadArtifactLeaseToTaskClear(
                context = context,
                songKey = song.stableKey(),
                operationId = operationId,
                expectedLeaseId = expectedLeaseId
            )
    ) {
        return
    }
    val operationState = if (boundedRoomWait) {
        withDownloadClearRoomTimeout(
            operation = "read artifact ownership operation state"
        ) {
            DownloadExecutionRoomStore.state(context, operationId)
        }
    } else {
        DownloadExecutionRoomStore.state(context, operationId)
    }
    val stoppedByUser = if (boundedRoomWait) {
        withDownloadClearRoomTimeout(
            operation = "read artifact ownership stop marker"
        ) {
            DownloadExecutionRoomStore.isStopped(context, operationId)
        }
    } else {
        DownloadExecutionRoomStore.isStopped(context, operationId)
    }
    val preserveForResume = operationState == "RETRYABLE" || stoppedByUser
    if (preserveForResume) {
        markDownloadArtifactRetryable(
            context = context,
            song = song,
            leaseId = expectedLeaseId,
            errorCode = "EXECUTION_OWNERSHIP_LOST"
        )
        managedDownloadArtifactLeases.remove(song.stableKey(), expectedLeaseId)
    } else {
        releaseDownloadArtifactClaim(context, song, expectedLeaseId)
    }
}

internal suspend fun GlobalDownloadManager.removeManagedDownloadArtifacts(
    context: Context,
    songName: String,
    storedAudio: ManagedDownloadStorage.StoredEntry?,
    songId: Long,
    candidateBaseNames: List<String>,
    explicitReferences: List<String> = emptyList(),
    useCachedSnapshotOnly: Boolean = false
): ManagedDownloadArtifactRemovalResult {
    return managedDownloadDeletePlanner.removeArtifacts(
        context = context,
        songName = songName,
        storedAudio = storedAudio,
        songId = songId,
        candidateBaseNames = candidateBaseNames,
        explicitReferences = explicitReferences,
        useCachedSnapshotOnly = useCachedSnapshotOnly,
        logger = { message -> NPLogger.d(TAG, message) }
    )
}

internal suspend fun GlobalDownloadManager.buildManagedDownloadDeletePlans(
    context: Context,
    songs: List<DownloadedSong>
): List<ManagedDownloadSongDeletePlan> {
    return managedDownloadDeletePlanner.buildDeletePlans(context, songs)
}
