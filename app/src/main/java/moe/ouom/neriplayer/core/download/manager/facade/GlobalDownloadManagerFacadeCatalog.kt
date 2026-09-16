package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadedSongMetadataSyncOutcome
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.catalog.projectDownloadedSongMetadata
import moe.ouom.neriplayer.core.download.catalog.toMetadataPersistenceSong
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.metadata.RestorableMetadataClearPolicy
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

private const val FULL_LIBRARY_DELETE_ACK_TARGET_MS = 5_000L

internal suspend fun GlobalDownloadManager.scanLocalFilesAwaitImpl(
    context: Context,
    forceRefresh: Boolean = false
): ManagedLibraryRefreshOutcome {
    val waiter = CompletableDeferred<ManagedLibraryRefreshOutcome>()
    val appContext = context.applicationContext
    synchronized(this) {
        refreshWaiters += waiter
        requestLocalScanLocked(appContext, forceRefresh)
    }
    try {
        return waiter.await()
    } finally {
        synchronized(this) {
            refreshWaiters.remove(waiter)
        }
    }
}

internal fun GlobalDownloadManager.shouldCompleteProcessingAfterCatalogPublishImpl(
    state: ManagedLibraryProcessingState
): Boolean {
    return when (state) {
        is ManagedLibraryProcessingState.Running ->
            state.phase == ManagedLibraryProcessingPhase.REBUILDING_INDEX &&
                (
                    state.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE ||
                        state.reason == ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE
                    )
        is ManagedLibraryProcessingState.WaitingForRetry ->
            state.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE
        ManagedLibraryProcessingState.Idle -> false
    }
}

internal fun GlobalDownloadManager.resolveDurableMetadataPlaybackReferenceImpl(
    audio: ManagedDownloadStorage.StoredEntry,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): String? {
    return ManagedDownloadStorage.resolveStoredEntryPlaybackUri(audio)
        ?: metadata?.mediaUri
            ?.trim()
            ?.takeIf { reference ->
                reference.startsWith("/") ||
                    reference.startsWith("content:", ignoreCase = true) ||
                    reference.startsWith("file:", ignoreCase = true)
            }
}

internal fun GlobalDownloadManager.syncDownloadedSongMetadataImpl(
    song: SongItem,
    clearRestorableOverrides: RestorableMetadataClearPolicy =
        RestorableMetadataClearPolicy()
) {
    scope.launch {
        runDownloadedSongMetadataSyncSafely(
            block = {
                syncDownloadedSongMetadataNow(song, clearRestorableOverrides)
            },
            onFailure = { error ->
                NPLogger.e(
                    TAG,
                    "同步下载歌曲元数据后台任务失败: song=${song.name}, " +
                        "error=${error.message}",
                    error
                )
            }
        )
    }
}

internal fun GlobalDownloadManager.deleteDownloadedSongsImpl(context: Context, songs: List<DownloadedSong>) {
    val appContext = context.applicationContext
    val targetSongs = songs.distinctBy(DownloadedSong::deletionIdentity)
    if (targetSongs.isEmpty()) {
        return
    }
    val session = try {
        beginDownloadedSongDeleteSession(appContext, targetSongs)
    } catch (error: Exception) {
        NPLogger.e(TAG, "建立下载删除会话失败: ${error.message}", error)
        scheduleCatalogReconcile(appContext, forceRefresh = true)
        return
    }
    scope.launch {
        var deleteLease: AutoCloseable? = null
        try {
            deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(
                appContext
            )
            if (deleteLease == null) {
                restoreDeferredDownloadedSongDeleteSession(appContext, session)
                NPLogger.w(
                    TAG,
                    "目录迁移进行中，删除下载稍后重试: songs=${targetSongs.size}"
                )
                return@launch
            }
            withContext(Dispatchers.IO) {
                downloadedSongDeleteMutex.withLock {
                    deleteDownloadedSongsOnIo(appContext, session)
                }
            }
        } finally {
            deleteLease?.close()
            scheduleFullLibraryDeleteRecoveryIfNeeded(
                context = appContext,
                session = session
            )
            endDownloadedSongDeletion(session.deletionKeys)
        }
    }
}

internal suspend fun GlobalDownloadManager.deleteDownloadedSongsWithResultImpl(
    context: Context,
    songs: List<DownloadedSong>,
    deleteEntireLibrary: Boolean = false
): DownloadedSongDeleteResult {
    val appContext = context.applicationContext
    val targetSongs = songs.distinctBy(DownloadedSong::deletionIdentity)
    if (targetSongs.isEmpty()) {
        return DownloadedSongDeleteResult.empty()
    }
    val requestStartedAtMs = SystemClock.elapsedRealtime()

    val session = try {
        beginDownloadedSongDeleteSession(
            context = appContext,
            targetSongs = targetSongs,
            deleteEntireLibrary = deleteEntireLibrary
        )
    } catch (error: Exception) {
        downloadedSongDeleteProgressMutable.value = DownloadedSongDeleteProgress(
            deleteId = downloadedSongDeleteIdGenerator.incrementAndGet(),
            phase = DownloadedSongDeletePhase.FAILED,
            requestedSongCount = targetSongs.size,
            failedReferenceCount = targetSongs.size,
            fullLibraryDelete = deleteEntireLibrary
        )
        NPLogger.e(TAG, "建立下载删除会话失败: ${error.message}", error)
        scheduleCatalogReconcile(appContext, forceRefresh = true)
        return DownloadedSongDeleteResult(
            deletedSongs = emptyList(),
            failedSongs = targetSongs
        )
    }
    if (session.fullLibraryDelete && session.deleteIntentDurable) {
        launchDurableFullLibraryDeleteSession(
            context = appContext,
            session = session
        )
        val acknowledgementElapsedMs = SystemClock.elapsedRealtime() - requestStartedAtMs
        NPLogger.i(
            TAG,
            "全选删除已持久接受并转入后台物理清理: songs=${targetSongs.size}, " +
                "elapsedMs=$acknowledgementElapsedMs, " +
                "targetMs=$FULL_LIBRARY_DELETE_ACK_TARGET_MS, " +
                "overBudget=${acknowledgementElapsedMs > FULL_LIBRARY_DELETE_ACK_TARGET_MS}"
        )
        return DownloadedSongDeleteResult(
            deletedSongs = targetSongs,
            failedSongs = emptyList(),
            physicalCleanupPending = true
        )
    }
    return try {
        withContext(Dispatchers.IO) {
            val deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(
                appContext
            ) ?: return@withContext restoreDeferredDownloadedSongDeleteSession(
                appContext,
                session
            ).also {
                NPLogger.w(
                    TAG,
                    "目录迁移进行中，删除下载稍后重试: songs=${targetSongs.size}"
                )
            }
            try {
                downloadedSongDeleteMutex.withLock {
                    deleteDownloadedSongsOnIo(appContext, session)
                }
            } finally {
                deleteLease.close()
            }
        }
    } finally {
        scheduleFullLibraryDeleteRecoveryIfNeeded(
            context = appContext,
            session = session
        )
        endDownloadedSongDeletion(session.deletionKeys)
    }
}

private fun GlobalDownloadManager.launchDurableFullLibraryDeleteSession(
    context: Context,
    session: GlobalDownloadManager.DownloadedSongDeleteSession
) {
    scope.launch {
        var deleteLease: AutoCloseable? = null
        try {
            deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(
                context
            )
            if (deleteLease == null) {
                updateDownloadedSongDeleteProgress(
                    session = session,
                    phase = DownloadedSongDeletePhase.WAITING_FOR_DIRECTORY
                )
                NPLogger.w(
                    TAG,
                    "目录迁移进行中，全选删除已接受并交给持久恢复: " +
                        "songs=${session.targetSongs.size}"
                )
                return@launch
            }
            withContext(Dispatchers.IO) {
                downloadedSongDeleteMutex.withLock {
                    deleteDownloadedSongsOnIo(context, session)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "后台全选删除失败，保留持久意图继续恢复: ${error.message}",
                error
            )
        } finally {
            deleteLease?.close()
            scheduleFullLibraryDeleteRecoveryIfNeeded(
                context = context,
                session = session
            )
            endDownloadedSongDeletion(session.deletionKeys)
        }
    }
}

internal fun GlobalDownloadManager.playDownloadedSongImpl(context: Context, song: DownloadedSong) {
    val appContext = context.applicationContext
    val requestGeneration = downloadedPlaybackRequestGeneration.incrementAndGet()
    val job = synchronized(downloadedPlaybackJobLock) {
        downloadedPlaybackJob?.cancel()
        scope.launch {
        try {
            if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                return@launch
            }
            val playbackReference = resolveDownloadedSongPlaybackReference(song)
            if (playbackReference.isNullOrBlank()) {
                NPLogger.w(TAG, "下载文件不存在: ${song.name}, reference=$playbackReference")
                removeMissingDownloadedSongEntry(appContext, song)
                return@launch
            }

            val sourceSong = song.toPlaybackSongItem()
            val playbackResolution = resolveDownloadedPlaybackWithRetry(
                context = appContext,
                downloadedSong = song,
                sourceSong = sourceSong,
                playbackReference = playbackReference
            )
            if (playbackResolution == null) {
                NPLogger.w(
                    TAG,
                    "播放下载歌曲暂未取得可读本地引用，保留等待收尾: " +
                        "song=${song.name}, reference=$playbackReference"
                )
                scheduleCatalogReconcile(appContext, forceRefresh = false)
                return@launch
            }
            if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                return@launch
            }
            val storedAudio = playbackResolution.audio
            val playbackUri = playbackResolution.reference
            val playbackSnapshot = playbackResolution.snapshot
            val quickDownloadedSong = storedAudio?.let { audio ->
                runCatching {
                    buildDownloadedSong(
                        context = appContext,
                        storedAudio = audio,
                        snapshot = playbackSnapshot,
                        existingDownloadTime = song.downloadTime,
                        loadLyricContents = false,
                        resolveLyricFallbacks = false,
                        allowSlowLocalInspection = false
                    )
                }.getOrNull()
            }
            val quickSongBase = (quickDownloadedSong ?: song).toPlaybackSongItem(
                playbackUri = playbackUri,
                localFileName = storedAudio?.name
                    ?: ManagedDownloadStorage.normalizeManagedAudioFileName(playbackUri),
                localFilePath = storedAudio?.localFilePath
                    ?: playbackUri.takeIf { it.startsWith("/") },
                resolvedDurationMs = song.durationMs
            )
            val quickSong = hydrateDownloadedSidecarLyricsFast(
                context = appContext,
                song = quickSongBase
            )
            withContext(Dispatchers.Main.immediate) {
                if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                    return@withContext
                }
                PlayerManager.playPlaylist(listOf(quickSong), 0)
            }

            if (!isLatestDownloadedPlaybackRequest(requestGeneration)) {
                return@launch
            }
            scheduleDownloadedPlaybackReferenceValidation(appContext, song, playbackReference)
            val refreshedSong = runCatching {
                val hydrationSnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot =
                    ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                    context = appContext,
                    restorePersisted = false
                )?.let { snapshot ->
                    ManagedDownloadStorage.refreshDownloadSidecarSnapshot(
                        context = appContext,
                        snapshot = snapshot
                    )
                } ?: playbackSnapshot ?: return@runCatching null
                val hydratedAudio = storedAudio?.let { audio ->
                    resolvePlayableManagedAudioSnapshot(
                        context = appContext,
                        snapshot = hydrationSnapshot,
                        candidate = audio,
                        song = sourceSong
                    )
                } ?: return@runCatching null
                buildDownloadedSong(
                    context = appContext,
                    storedAudio = hydratedAudio.audio,
                    snapshot = hydratedAudio.snapshot,
                    existingDownloadTime = song.downloadTime,
                    loadLyricContents = false,
                    resolveLyricFallbacks = false,
                    allowSlowLocalInspection = false
                )
            }.getOrNull() ?: quickDownloadedSong ?: song
            val hydratedDurationMs = refreshedSong.durationMs
                .takeIf { it > 0L }
                ?: quickSong.durationMs.takeIf { it > 0L }
                ?: resolveAudioDuration(appContext, playbackUri)
            val hydratedSong = hydrateDownloadedSidecarLyricsFast(
                context = appContext,
                song = refreshedSong.toPlaybackSongItem(
                    playbackUri = playbackUri,
                    localFileName = storedAudio?.name
                        ?: ManagedDownloadStorage.normalizeManagedAudioFileName(playbackUri),
                    localFilePath = storedAudio?.localFilePath
                        ?: playbackUri.takeIf { it.startsWith("/") },
                    resolvedDurationMs = hydratedDurationMs
                ),
                refreshIfMissing = true
            )
            if (hydratedSong != quickSong) {
                delay(resolveDownloadedPlaybackHydrationDelayMs(quickSong, hydratedSong))
                if (!isLatestDownloadedPlaybackRequest(requestGeneration) ||
                    !shouldApplyDownloadedPlaybackHydration(
                        currentSong = PlayerManager.currentSongFlow.value,
                        quickSong = quickSong
                    )
                ) {
                    return@launch
                }
                PlayerManager.hydrateSongMetadata(
                    originalSong = quickSong,
                    updatedSong = hydratedSong
                )
            }
        } catch (error: CancellationException) {
            // 新的播放请求会取消旧请求，取消不应被记录成播放失败
            return@launch
        } catch (error: Exception) {
            NPLogger.e(TAG, "播放下载文件失败: ${error.message}", error)
        }
        }
        .also { launchedJob ->
            downloadedPlaybackJob = launchedJob
        }
    }
    job.invokeOnCompletion {
        synchronized(downloadedPlaybackJobLock) {
            if (downloadedPlaybackJob === job) {
                downloadedPlaybackJob = null
            }
        }
    }
}

internal fun GlobalDownloadManager.findAccessibleDownloadedSongPlaybackUriImpl(context: Context, song: SongItem): String? {
    if (isSongCancelled(song.stableKey())) {
        return null
    }
    // core 提交后资产收尾可能尚未刷新 SAF 快照，此时优先使用已发布的
    // 内存目录条目，避免每次播放都触发一次完整 SAF 扫描
    val cachedSong = findDownloadedSongCached(song)
    if (cachedSong != null) {
        val probe = probeDownloadedSongReferences(
            context = context,
            song = cachedSong
        )
        probe.reference?.let { return it }
        scheduleDownloadedSongReferenceReconcile(
            context = context,
            song = cachedSong,
            probe = probe
        )
        if (probe.sawUncertain) {
            NPLogger.w(
                TAG,
                "下载目录缓存候选引用暂不可确认，保留并等待对账: " +
                    "song=${song.name}"
            )
        }
    }

    // 缓存引用失效时才尝试严格快照路径。该路径仍会校验 root 完整性和
    // core metadata，避免把旧目录或临时文件当作可播放音频
    val downloadedSong = findFastCachedDownloadedSong(context, song) ?: return null
    val probe = probeDownloadedSongReferences(
        context = context,
        song = downloadedSong
    )
    probe.reference?.let { return it }
    scheduleDownloadedSongReferenceReconcile(
        context = context,
        song = downloadedSong,
        probe = probe
    )
    if (probe.sawUncertain) {
        NPLogger.w(
            TAG,
            "下载快索引候选引用暂不可确认，保留并等待对账: song=${song.name}"
        )
    }
    return null
}

internal fun GlobalDownloadManager.findFastCachedDownloadedSongPlaybackUriImpl(context: Context, song: SongItem): String? {
    val downloadedSong = findFastCachedDownloadedSong(context, song) ?: return null
    val probe = probeDownloadedSongReferences(
        context = context,
        song = downloadedSong
    )
    probe.reference?.let { return it }
    scheduleDownloadedSongReferenceReconcile(
        context = context,
        song = downloadedSong,
        probe = probe
    )
    if (probe.sawUncertain) {
        NPLogger.w(
            TAG,
            "下载快索引候选引用暂不可确认，跳过破坏性对账: song=${song.name}"
        )
    }
    return null
}

internal suspend fun GlobalDownloadManager.syncDownloadedSongMetadataNowImpl(
    song: SongItem,
    clearRestorableOverrides: RestorableMetadataClearPolicy =
        RestorableMetadataClearPolicy()
): DownloadedSongMetadataSyncOutcome = withContext(Dispatchers.IO) {
    downloadedSongMetadataSyncMutex.withLock {
        val context = AppContainer.applicationContext
        val currentSongs = downloadedSongsMutable.value
        val catalogSong = findDownloadedSongCatalogMatch(song, currentSongs)
        val metadataSong = catalogSong
            ?.let { existing -> projectDownloadedSongMetadata(existing, song) }
            ?.toMetadataPersistenceSong(song)
            ?: song
        val resolvedStoredAudio = resolveStoredAudio(context, song)
            ?: catalogSong?.let { downloaded ->
                resolveStoredAudio(context, downloaded.filePath)
                    ?: resolveStoredAudio(context, downloaded.mediaUri)
            }
        if (resolvedStoredAudio == null) {
            if (publishDownloadedSongMetadataFallback(
                    context = context,
                    currentSongs = currentSongs,
                    catalogSong = catalogSong,
                    storedAudio = null,
                    song = metadataSong,
                    reason = "storage entry unavailable"
                )
            ) {
                return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
            }
            NPLogger.w(
                TAG,
                "同步下载歌曲元数据失败: file unavailable, song=${song.name}"
            )
            return@withLock DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
        }

        val preflightSnapshot = runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }.getOrElse { error ->
            NPLogger.w(
                TAG,
                "同步下载歌曲元数据前无法确认目录快照，拒绝发布: " +
                    "file=${resolvedStoredAudio.name}, error=${error.message}"
            )
            scheduleCatalogReconcile(context, forceRefresh = true)
            return@withLock DownloadedSongMetadataSyncOutcome.FAILED
        }
        val finalizedStoredAudio = resolveFinalizedManagedAudioSnapshot(
            snapshot = preflightSnapshot,
            candidate = resolvedStoredAudio
        ) ?: run {
            NPLogger.w(
                TAG,
                "同步下载歌曲元数据命中未完成或未确认音频，拒绝写回完成状态: " +
                    "file=${resolvedStoredAudio.name}"
            )
            scheduleCatalogReconcile(context, forceRefresh = true)
            return@withLock DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
        }
        val storedAudio = finalizedStoredAudio.audio

        if (!persistDownloadedMetadata(
                context = context,
                audio = storedAudio,
                song = metadataSong,
                clearRestorableOverrides = clearRestorableOverrides
            )
        ) {
            NPLogger.e(TAG, "同步下载歌曲元数据失败: metadata persist failed, file=${storedAudio.name}")
            if (publishDownloadedSongMetadataFallback(
                    context = context,
                    currentSongs = currentSongs,
                    catalogSong = catalogSong,
                    storedAudio = storedAudio,
                    song = metadataSong,
                    reason = "metadata sidecar persist deferred"
                )
            ) {
                return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
            }
            return@withLock DownloadedSongMetadataSyncOutcome.FAILED
        }

        downloadedSongMetadataRevision.incrementAndGet()
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        )?.takeIf { cachedSnapshot ->
            cachedSnapshot.audioEntriesByLookupKey.containsKey(storedAudio.reference) ||
                cachedSnapshot.audioEntriesByLookupKey.containsKey(storedAudio.mediaUri)
        } ?: runCatching {
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                context = context,
                forceRefresh = true
            )
        }.getOrElse { error ->
            NPLogger.e(TAG, "同步下载歌曲元数据失败: refresh failed, file=${storedAudio.name}", error)
            if (publishDownloadedSongMetadataFallback(
                    context = context,
                    currentSongs = currentSongs,
                    catalogSong = catalogSong,
                    storedAudio = storedAudio,
                    song = metadataSong,
                    reason = "snapshot refresh failed"
                )
            ) {
                scheduleCatalogReconcile(context, forceRefresh = true)
                return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
            }
            return@withLock DownloadedSongMetadataSyncOutcome.FAILED
        }
        val refreshedStoredAudio = resolveFinalizedManagedAudioSnapshot(
            snapshot = snapshot,
            candidate = storedAudio
        ) ?: run {
            NPLogger.w(
                TAG,
                "同步下载歌曲元数据后目录快照未提供严格完成凭据，拒绝发布: " +
                    "file=${storedAudio.name}"
            )
            scheduleCatalogReconcile(context, forceRefresh = true)
            return@withLock DownloadedSongMetadataSyncOutcome.FAILED
        }
        val refreshedSong = runCatching {
            buildDownloadedSong(
                context = context,
                storedAudio = refreshedStoredAudio.audio,
                snapshot = refreshedStoredAudio.snapshot,
                existingDownloadTime = catalogSong?.downloadTime,
                allowSlowLocalInspection = false
            )
        }.getOrElse { error ->
            NPLogger.e(TAG, "同步下载歌曲元数据失败: rebuild failed, file=${storedAudio.name}", error)
            if (publishDownloadedSongMetadataFallback(
                    context = context,
                    currentSongs = currentSongs,
                    catalogSong = catalogSong,
                    storedAudio = storedAudio,
                    song = metadataSong,
                    reason = "catalog rebuild failed"
                )
            ) {
                scheduleCatalogReconcile(context, forceRefresh = true)
                return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
            }
            return@withLock DownloadedSongMetadataSyncOutcome.FAILED
        }
        if (!hasExpectedDownloadedSongMetadata(refreshedSong, metadataSong)) {
            NPLogger.w(TAG, "下载目录快照未反映最新标签，使用已验证结果: file=${storedAudio.name}")
            publishDownloadedSongMetadataFallback(
                context = context,
                currentSongs = currentSongs,
                catalogSong = catalogSong,
                storedAudio = storedAudio,
                song = metadataSong,
                reason = "catalog metadata stale"
            )
            scheduleCatalogReconcile(context, forceRefresh = true)
            return@withLock DownloadedSongMetadataSyncOutcome.SUCCESS
        }
        val refreshedSongs = upsertDownloadedSongCatalog(currentSongs, refreshedSong)
        publishDownloadedSongs(context, refreshedSongs, persistCatalog = true)
        downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
        updateFastIndexAfterMetadataEdit(
            context = context,
            song = metadataSong,
            storedAudio = refreshedStoredAudio.audio,
            state = "FINALIZED",
            coverPath = refreshedSong.coverPath
        )
        NPLogger.d(
            TAG,
            "已同步下载歌曲元数据并发布目录: file=${storedAudio.name}, " +
                "customCover=${refreshedSong.customCoverUrl != null}"
        )
        DownloadedSongMetadataSyncOutcome.SUCCESS
    }
}
