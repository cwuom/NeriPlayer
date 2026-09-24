package moe.ouom.neriplayer.core.download.manager.catalog

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadSongDeletePlan
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.downloadedSongPlaybackReferenceCandidates
import moe.ouom.neriplayer.core.download.matchesDownloadedSongCatalogEntry
import moe.ouom.neriplayer.core.download.mergeManagedRequestedReferences
import moe.ouom.neriplayer.core.download.resolveDownloadedSongPlaybackReference
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutationForStableKeys
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrentForStableKeys
import moe.ouom.neriplayer.core.download.manager.batch.awaitDownloadCancellationsSettled
import moe.ouom.neriplayer.core.download.manager.batch.cancelAllDownloadTasksAndWait
import moe.ouom.neriplayer.core.download.manager.batch.cancellationOperationIdsForSong
import moe.ouom.neriplayer.core.download.manager.batch.clearPersistedDownloadClearProgress
import moe.ouom.neriplayer.core.download.manager.batch.clearSongCancellationForFreshStart
import moe.ouom.neriplayer.core.download.manager.batch.finishReleasedTaskClearState
import moe.ouom.neriplayer.core.download.manager.batch.isFullLibraryDeleteCancellationSettled
import moe.ouom.neriplayer.core.download.manager.batch.finishUnconfirmedFullLibraryDelete
import moe.ouom.neriplayer.core.download.manager.batch.requestAllDownloadTaskCancellation
import moe.ouom.neriplayer.core.download.manager.batch.requestDownloadTaskCancellation
import moe.ouom.neriplayer.core.download.manager.batch.scheduleCatalogReconcile
import moe.ouom.neriplayer.core.download.manager.batch.scheduleDeferredFullLibraryDeleteRecovery
import moe.ouom.neriplayer.core.download.manager.admission.promoteWaitingStorageMutationsForRecovery
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.execution.worker.DownloadStorageRecoveryWorker
import moe.ouom.neriplayer.core.download.manager.runtime.publishDownloadStage
import moe.ouom.neriplayer.core.download.manager.runtime.resolvePlayableManagedAudioSnapshot
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeletePhase
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteProgress
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteResult
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteVisibility
import moe.ouom.neriplayer.core.download.model.mergeDownloadedSongsAfterDelete
import moe.ouom.neriplayer.core.download.model.remoteSourceStableKeyOrNull
import moe.ouom.neriplayer.core.download.model.resolveConfirmedFullLibraryDeleteResult
import moe.ouom.neriplayer.core.download.model.resolveDownloadedSongDeleteResult
import moe.ouom.neriplayer.core.download.model.resolveFullLibraryRemainingReferences
import moe.ouom.neriplayer.core.download.policy.nextDownloadOperationCreatedAtMs
import moe.ouom.neriplayer.core.download.policy.shouldAllowPendingCatalogPlayback
import moe.ouom.neriplayer.core.download.policy.shouldApplyDownloadedPlaybackRequest
import moe.ouom.neriplayer.core.download.policy.shouldDeleteEntireDownloadedLibrary
import moe.ouom.neriplayer.core.download.policy.shouldEvictMissingDownloadedSongCatalogEntry
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadedPlaybackResolution
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadedSongReferenceProbe
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.DownloadedSongDeleteSession
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuilder
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadDeleteReferenceIndex
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearPurpose
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexMutationResult
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.download.manager.batch.beginBatchDownloadPresentation
import moe.ouom.neriplayer.core.download.manager.batch.bindBatchDownloadPresentationAttempts
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.isReadableManagedAudioPlaybackAllowed
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.resolveDownloadAudioQualitySelection
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap


internal fun GlobalDownloadManager.scheduleFullLibraryDeleteRecoveryIfNeeded(
    context: Context,
    session: DownloadedSongDeleteSession
) {
    if (!session.fullLibraryDelete || !session.deleteIntentDurable) {
        return
    }
    // 不在删除入口同步读取磁盘状态。恢复协程会在启动后再次确认意图和
    // fence，成功删除已经清理意图时会直接退出
    scheduleDeferredFullLibraryDeleteRecovery(context)
}

internal fun GlobalDownloadManager.restoreDeferredDownloadedSongDeleteSession(
    context: Context,
    session: DownloadedSongDeleteSession
): DownloadedSongDeleteResult {
    updateDownloadedSongDeleteProgress(
        session = session,
        phase = DownloadedSongDeletePhase.WAITING_FOR_DIRECTORY
    )
    settleDownloadedSongDeleteSession(
        context = context,
        session = session,
        deletedSongs = emptyList(),
        restoredSongs = session.targetSongs
    )
    scheduleCatalogReconcile(context, forceRefresh = true)
    return DownloadedSongDeleteResult(
        deletedSongs = emptyList(),
        failedSongs = session.targetSongs,
        physicalCleanupPending = session.fullLibraryDelete && session.deleteIntentDurable
    )
}

internal fun GlobalDownloadManager.beginDownloadedSongDeleteSession(
    context: Context,
    targetSongs: List<DownloadedSong>,
    deleteEntireLibrary: Boolean = false
): DownloadedSongDeleteSession {
    val deletionKeys = downloadedSongDeletionKeys(targetSongs)
    beginDownloadedSongDeletion(deletionKeys)
    var visibilityToken: DownloadedSongDeleteVisibility.Token? = null
    var deleteIntentDurable = true
    return try {
        synchronized(downloadedSongCatalogMutationLock) {
            val previousSongs = downloadedSongsMutable.value
            val currentVisibilityToken = downloadedSongDeleteVisibility.begin(targetSongs)
            visibilityToken = currentVisibilityToken
            val currentRootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
            val pendingIntentExists =
                PersistentDownloadedSongDeleteIntentStore.hasPending(context)
            val persistedIntent = PersistentDownloadedSongDeleteIntentStore.read(context)
            if (
                pendingIntentExists &&
                    (persistedIntent == null || persistedIntent.rootKey != currentRootKey)
            ) {
                NPLogger.e(
                    TAG,
                    "检测到无法确认归属的全选删除意图，拒绝覆盖并保留原文件: " +
                        "currentRoot=$currentRootKey"
                )
                throw IllegalStateException(
                    "existing full-library delete intent is unreadable or belongs to another root"
                )
            }
            // 部分提交后的回放可能只剩少量 catalog 行，持久意图仍拥有整批清空栅栏
            val deletesEntireCatalog = shouldDeleteEntireDownloadedLibrary(
                explicitlyRequested = deleteEntireLibrary,
                pendingDeleteIntentExists = persistedIntent != null
            )
            if (deletesEntireCatalog) {
                if (persistedIntent == null) {
                    val intentPersisted = PersistentDownloadedSongDeleteIntentStore.begin(
                        context = context,
                        rootKey = currentRootKey,
                        songs = targetSongs
                    )
                    deleteIntentDurable = intentPersisted
                    if (!intentPersisted) {
                        // 内存清空仍可继续，但保留高可见日志，便于诊断极端
                        // 的 filesDir 不可写窗口；物理删除结果仍按引用确认
                        NPLogger.e(TAG, "全选删除恢复意图未落盘，继续执行并等待目录对账")
                    }
                }
            }
            val visibleSongs = downloadedSongDeleteVisibility.filterVisible(previousSongs)
            if (visibleSongs != previousSongs) {
                publishDownloadedSongs(context, visibleSongs, persistCatalog = false)
            }
            val clearJob = if (
                deletesEntireCatalog && deleteIntentDurable
            ) {
                NPLogger.d(
                    TAG,
                    "全选删除已被接受，立即建立下载清空栅栏: songs=${targetSongs.size}"
                )
                requestAllDownloadTaskCancellation(
                    purpose = DownloadClearPurpose.FULL_LIBRARY_DELETE
                )
            } else {
                null
            }
            DownloadedSongDeleteSession(
                deleteId = downloadedSongDeleteIdGenerator.incrementAndGet(),
                targetSongs = targetSongs,
                previousSongs = previousSongs,
                deletionKeys = deletionKeys,
                visibilityToken = currentVisibilityToken,
                clearJob = clearJob,
                fullLibraryDelete = deletesEntireCatalog,
                deleteIntentDurable = deleteIntentDurable
            ).also { session ->
                downloadedSongDeleteProgressMutable.value = DownloadedSongDeleteProgress(
                    deleteId = session.deleteId,
                    phase = DownloadedSongDeletePhase.PREPARING,
                    requestedSongCount = targetSongs.size,
                    fullLibraryDelete = deletesEntireCatalog
                )
            }
        }
    } catch (error: Throwable) {
        visibilityToken?.let(downloadedSongDeleteVisibility::finish)
        endDownloadedSongDeletion(deletionKeys)
        throw error
    }
}

internal fun GlobalDownloadManager.downloadedSongDeletionKeys(songs: Collection<DownloadedSong>): Set<String> {
    return songs.mapNotNullTo(linkedSetOf()) { song ->
        song.remoteSourceStableKeyOrNull()
            ?: song.stableKey?.trim()?.takeIf(String::isNotBlank)
    }
}

internal fun selectDeletionCancellationKeys(
    requestedKeys: Set<String>,
    taskStatuses: Map<String, DownloadStatus>,
    durableKeys: Set<String>,
    activeKeys: Set<String>
): Set<String> {
    return requestedKeys.filterTo(linkedSetOf()) { songKey ->
        songKey in durableKeys || songKey in activeKeys ||
            taskStatuses[songKey] == DownloadStatus.QUEUED ||
            taskStatuses[songKey] == DownloadStatus.DOWNLOADING ||
            taskStatuses[songKey] == DownloadStatus.WAITING_NETWORK
    }
}

internal fun GlobalDownloadManager.beginDownloadedSongDeletion(songKeys: Collection<String>) {
    songKeys.forEach { songKey ->
        downloadedSongDeletionCounts.compute(songKey) { _, current ->
            (current ?: AtomicInteger()).apply { incrementAndGet() }
        }
    }
}

internal fun GlobalDownloadManager.endDownloadedSongDeletion(
    songKeys: Collection<String>,
    context: Context? = null
) {
    songKeys.forEach { songKey ->
        downloadedSongDeletionCounts.computeIfPresent(songKey) { _, count ->
            count.takeIf { it.decrementAndGet() > 0 }
        }
    }
    if (context != null) {
        scheduleDeleteCleanupRetry(context, songKeys, downloadAdmissionGate.openTicketOrNull())
    }
}

internal suspend fun GlobalDownloadManager.awaitDownloadedSongDeletion(
    songKeys: Collection<String>
): Boolean {
    val keys = songKeys.filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) {
        return true
    }
    val settled = withTimeoutOrNull(DOWNLOADED_SONG_DELETE_BARRIER_TIMEOUT_MS) {
        while (keys.any(downloadedSongDeletionCounts::containsKey)) {
            delay(DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS)
        }
        true
    } == true
    if (!settled) {
        NPLogger.w(
            TAG,
            "等待旧下载文件清理超时，交由后台重试: " +
                "keys=${keys.size}, " +
                "timeoutMs=$DOWNLOADED_SONG_DELETE_BARRIER_TIMEOUT_MS"
        )
    }
    return settled
}

internal suspend fun GlobalDownloadManager.awaitAllDownloadedSongDeletions(): Boolean {
    if (!isDownloadedSongDeletionActive()) {
        return true
    }
    val settled = withTimeoutOrNull(DOWNLOADED_SONG_DELETE_BARRIER_TIMEOUT_MS) {
        while (isDownloadedSongDeletionActive()) {
            delay(DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS)
        }
        true
    } == true
    if (!settled) {
        NPLogger.w(
            TAG,
            "等待下载删除集合清理超时，跳过本轮目录扫描: " +
                "timeoutMs=$DOWNLOADED_SONG_DELETE_BARRIER_TIMEOUT_MS"
        )
    }
    return settled
}

internal fun GlobalDownloadManager.isDownloadedSongDeletionActive(): Boolean {
    return downloadedSongDeletionCounts.isNotEmpty() ||
        downloadedSongDeleteVisibility.hasActiveDeletions()
}

internal suspend fun GlobalDownloadManager.deferDownloadForDeleteCleanup(
    context: Context,
    songs: Collection<SongItem>,
    userInitiated: Boolean,
    admissionTicket: Long?,
    batchPresentationId: Long? = null
): Boolean {
    val ticket = admissionTicket ?: return false
    var deferred = false
    val admitted = admitDownloadMutationForStableKeys(
        context, ticket, songs.map(SongItem::stableKey)
    ) {
        deferred = persistDownloadWaitingForDeleteCleanup(
            context, songs, userInitiated, ticket, batchPresentationId
        )
    }
    return admitted && deferred
}

private suspend fun GlobalDownloadManager.persistDownloadWaitingForDeleteCleanup(
    context: Context,
    songs: Collection<SongItem>,
    userInitiated: Boolean,
    admissionTicket: Long,
    batchPresentationId: Long?
): Boolean {
    val appContext = context.applicationContext
    val distinctSongs = songs.distinctBy(SongItem::stableKey)
    val songKeys = distinctSongs.map(SongItem::stableKey)
    if (
        distinctSongs.isEmpty() ||
            !isDownloadAdmissionTicketCurrentForStableKeys(
                context = appContext,
                admissionTicket = admissionTicket,
                stableKeys = songKeys
            )
    ) {
        return false
    }
    if (userInitiated) {
        if (!clearSongCancellationForFreshStart(
            context = appContext,
            songKeys = distinctSongs.map(SongItem::stableKey)
        )) {
            NPLogger.w(
                TAG,
                "删除清理等待意图暂缓，旧取消 operation 快照尚未完成: " +
                    "songs=${distinctSongs.size}"
            )
            return false
        }
    }
    if (!isDownloadAdmissionTicketCurrentForStableKeys(
            context = appContext,
            admissionTicket = admissionTicket,
            stableKeys = songKeys
        )
    ) {
        return false
    }
    val currentNetworkType = appContext.currentDownloadNetworkTypeOrNull()
    val requiresWifiNetwork = !userInitiated ||
        currentNetworkType == null || currentNetworkType == TrafficNetworkType.WIFI
    val operationCreatedAtMs = nextDownloadOperationCreatedAtMs(
        requestedAtMs = System.currentTimeMillis(),
        cancellationCutoffs = songKeys.mapNotNull { songKey ->
            cancellationOperationSnapshotCutoffs[songKey]
        }
    )
    var batchIdentity: DownloadExecutionRoomStore.DownloadBatchIdentity? = null
    val operationIds = try {
        val excludedOperationIds = distinctSongs
            .flatMap { song -> cancellationOperationIdsForSong(song.stableKey()) }
            .toSet()
        val forceNewOperationForStableKeys = distinctSongs
            .map(SongItem::stableKey)
            .filter(cancellationForceNewSongKeys::contains)
            .toSet()
        val quality = resolveDownloadAudioQualitySelection(appContext)
        val database = NeriUserDataDatabase.getInstance(appContext)
        database.withTransaction {
            val waiting = DownloadRecoveryRoomStore(appContext, database)
                .upsertWaitingStorageMutationWithRequests(
                    songs = distinctSongs,
                    nowMs = operationCreatedAtMs,
                    userInitiated = userInitiated,
                    requiresWifiNetwork = requiresWifiNetwork,
                    downloadAudioQuality = quality,
                    excludedOperationIds = excludedOperationIds,
                    forceNewOperationForStableKeys = forceNewOperationForStableKeys
                )
            if (batchPresentationId != null && waiting.operationIds.isNotEmpty()) {
                val requests = waiting.requestsByOperationId.values.toList()
                val identity = DownloadExecutionRoomStore.createBatchSnapshot(
                    context = appContext,
                    songs = requests.map { it.song },
                    clearEpoch = PersistentDownloadClearFenceStore.currentEpoch(appContext),
                    networkGeneration = AudioDownloadManager.currentDownloadNetworkGeneration(),
                    database = database
                )
                DownloadExecutionRoomStore.attachBatchIdentity(
                    appContext, identity, requests, database = database
                )
                batchIdentity = identity
            }
            waiting.operationIds
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "删除清理等待意图未能落盘: songs=${distinctSongs.size}, " +
                "error=${error.message}",
            error
        )
        return false
    }
    if (operationIds.isEmpty()) {
        return false
    }
    // 卡片恢复失败也不能丢失已经落盘的唤醒
    DownloadStorageRecoveryWorker.schedule(appContext)
    if (!isDownloadAdmissionTicketCurrentForStableKeys(
            context = appContext,
            admissionTicket = admissionTicket,
            stableKeys = songKeys
        )
    ) {
        return false
    }
    try {
        val database = NeriUserDataDatabase.getInstance(appContext)
        database.withTransaction {
            val snapshots = DownloadExecutionRoomStore.readOperationSnapshots(
                context = appContext,
                operationIds = operationIds,
                database = database
            ).filterValues { it.state == WAITING_STORAGE_MUTATION_OPERATION_STATE }
            if (snapshots.isEmpty()) return@withTransaction
            val durableAttemptIds = snapshots.values.mapNotNull { snapshot ->
                snapshot.request.attemptId
                    ?.takeIf { attemptId -> attemptId > 0L }
                    ?.let { attemptId ->
                        snapshot.request.song.stableKey() to attemptId
                    }
            }.toMap()
            val attempts = taskStore.ensureDownloadTasks(
                songs = snapshots.values.map { it.request.song },
                status = DownloadStatus.WAITING_NETWORK,
                durableAttemptIds = durableAttemptIds
            )
            if (batchPresentationId != null) {
                batchIdentity?.let { identity ->
                    durableBatchIdentityByPresentationId[batchPresentationId] = identity
                    beginBatchDownloadPresentation(
                        songs = snapshots.values.map { it.request.song },
                        batchId = batchPresentationId
                    )
                    bindBatchDownloadPresentationAttempts(
                        batchId = batchPresentationId,
                        attemptIdsBySongKey = attempts,
                        operationIdsBySongKey = snapshots.values.associate {
                            it.request.song.stableKey() to it.request.operationId
                        }
                    )
                }
            }
            attempts.forEach { (songKey, attemptId) ->
                taskStore.updateTaskStatus(
                    songKey = songKey,
                    status = DownloadStatus.WAITING_NETWORK,
                    expectedAttemptId = attemptId
                )
            }
            snapshots.values.forEach { snapshot ->
                val request = snapshot.request
                val attemptId = attempts[request.song.stableKey()] ?: return@forEach
                publishDownloadStage(
                    song = request.song,
                    stage = AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP,
                    operationId = request.operationId,
                    attemptId = attemptId
                )
                if (request.attemptId == attemptId) return@forEach
                DownloadExecutionRoomStore.ensureAttemptId(
                    context = appContext,
                    operationId = request.operationId,
                    stableKey = request.song.stableKey(),
                    attemptId = attemptId,
                    database = database
                )
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "删除清理等待任务卡片未能恢复: songs=${distinctSongs.size}, " +
                "error=${error.message}",
            error
        )
        return false
    }
    NPLogger.w(
        TAG,
        "旧下载文件清理未收敛，保留等待重试意图: " +
            "songs=${distinctSongs.size}, operations=${operationIds.size}"
    )
    return true
}

internal suspend fun GlobalDownloadManager.markDownloadWaitingForDeleteCleanup(
    context: Context,
    songKey: String,
    operationId: String,
    expectedAttemptId: Long? = null,
    admissionTicket: Long? = null
) {
    if (
        admissionTicket != null &&
            !isDownloadAdmissionTicketCurrent(
                context = context,
                admissionTicket = admissionTicket,
                stableKey = songKey,
                operationId = operationId
            )
    ) {
        return
    }
    val task = taskStore.findTask(songKey)
    val attemptId = expectedAttemptId ?: task?.attemptId
    if (attemptId != null) {
        taskStore.updateTaskStatus(
            songKey = songKey,
            status = DownloadStatus.WAITING_NETWORK,
            expectedAttemptId = attemptId
        )
    }
    val request = try {
        DownloadExecutionRoomStore.read(context.applicationContext, operationId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "读取删除清理等待 operation 失败: operationId=$operationId, " +
                "error=${error.message}",
            error
        )
        null
    }
    if (request == null || request.song.stableKey() != songKey) {
        NPLogger.w(
            TAG,
            "删除清理等待 operation 与歌曲不匹配，跳过状态变更: " +
                "songKey=$songKey, operationId=$operationId"
        )
        return
    }
    publishDownloadStage(
        song = request.song,
        stage = AudioDownloadManager.DownloadStage.WAITING_DELETE_CLEANUP,
        operationId = operationId,
        attemptId = attemptId
    )
    val markedRetryable = try {
        DownloadExecutionRoomStore.markWaitingForStorageMutation(
            context = context.applicationContext,
            operationId = operationId,
            errorCode = "WAITING_DELETE_CLEANUP"
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "写入删除清理等待状态失败: operationId=$operationId, " +
                "error=${error.message}",
            error
        )
        false
    }
    NPLogger.w(
        TAG,
        "旧下载文件清理未收敛，任务进入等待清理重试: " +
            "songKey=$songKey, operationId=$operationId, marked=$markedRetryable"
    )
    if (markedRetryable) DownloadStorageRecoveryWorker.schedule(context.applicationContext)
}

internal fun GlobalDownloadManager.scheduleDeleteCleanupRetry(
    context: Context,
    songKeys: Collection<String>,
    admissionTicket: Long?
) {
    val keys = songKeys.filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) return
    val appContext = context.applicationContext
    DownloadStorageRecoveryWorker.schedule(appContext)
    scope.launch {
        if (!awaitDownloadedSongDeletion(keys)) {
            NPLogger.w(
                TAG,
                "删除清理重试仍在等待，保留持久等待意图: keys=${keys.size}"
            )
            return@launch
        }
        val ticket = admissionTicket?.takeIf {
            isDownloadAdmissionTicketCurrentForStableKeys(
                context = appContext,
                admissionTicket = it,
                stableKeys = keys
            )
        } ?: return@launch
        val promoted = promoteWaitingStorageMutationsForRecovery(
            context = appContext,
            admissionTicket = ticket
        )
        if (promoted > 0) {
            wakeDownloadExecutionPump(appContext, reason = "delete_cleanup_released")
        }
    }
}

internal fun GlobalDownloadManager.settleDownloadedSongDeleteSession(
    context: Context,
    session: DownloadedSongDeleteSession,
    deletedSongs: List<DownloadedSong>,
    restoredSongs: List<DownloadedSong>
): List<DownloadedSong> {
    var shouldPersistCatalog = false
    val settledSongs = synchronized(downloadedSongCatalogMutationLock) {
        downloadedSongDeleteVisibility.recordDeleted(
            token = session.visibilityToken,
            songs = deletedSongs
        )
        val ownedDeletedSongs = deletedSongs.filter { song ->
            downloadedSongDeleteVisibility.owns(session.visibilityToken, song)
        }
        val ownedRestoredSongs = restoredSongs.filter { song ->
            downloadedSongDeleteVisibility.owns(session.visibilityToken, song) &&
                !downloadedSongDeleteVisibility.wasPhysicallyDeleted(
                    session.visibilityToken,
                    song
                )
        }
        val currentSongs = downloadedSongsMutable.value
        val restorationSourceSongs = (
            session.previousSongs +
                session.visibilityToken.baselineSongsByIdentity.values
            ).distinctBy { song -> song.deletionIdentity().trim() }
        val mergedSongs = mergeDownloadedSongsAfterDelete(
            currentSongs = currentSongs,
            previousSongs = restorationSourceSongs,
            deletedSongs = ownedDeletedSongs,
            restoredSongs = ownedRestoredSongs
        )
        shouldPersistCatalog = ownedDeletedSongs.isNotEmpty() ||
            ownedRestoredSongs.isNotEmpty() ||
            deletedSongs.any { song ->
                downloadedSongDeleteVisibility.wasPhysicallyDeleted(
                    session.visibilityToken,
                    song
                )
            }
        downloadedSongDeleteVisibility.finish(session.visibilityToken)
        if (mergedSongs != currentSongs) {
            publishDownloadedSongs(context, mergedSongs, persistCatalog = false)
        }
        mergedSongs
    }
    if (shouldPersistCatalog) {
        scheduleDownloadedSongsCatalogPersist(context)
    }
    return settledSongs
}

internal suspend fun GlobalDownloadManager.deleteDownloadedSongsOnIo(
    appContext: Context,
    session: DownloadedSongDeleteSession
): DownloadedSongDeleteResult {
    val startedAtMs = SystemClock.elapsedRealtime()
    val targetSongs = session.targetSongs
    val deletesEntireCatalog = session.fullLibraryDelete
    val confirmedDeletedReferences = ConcurrentHashMap.newKeySet<String>()
    fun interruptedResult() = resolveConfirmedFullLibraryDeleteResult(
        targetSongs = targetSongs,
        snapshotComplete = false,
        requestedReferences = confirmedDeletedReferences,
        deletedReferences = confirmedDeletedReferences,
        fallback = DownloadedSongDeleteResult(
            emptyList(), targetSongs,
            physicalCleanupPending = deletesEntireCatalog && session.deleteIntentDurable
        )
    )
    try {
        if (deletesEntireCatalog && !session.deleteIntentDurable) {
            updateDownloadedSongDeleteProgress(
                session = session,
                phase = DownloadedSongDeletePhase.FAILED,
                failedReferenceCount = targetSongs.size
            )
            settleDownloadedSongDeleteSession(
                context = appContext,
                session = session,
                deletedSongs = emptyList(),
                restoredSongs = targetSongs
            )
            scheduleCatalogReconcile(appContext, forceRefresh = true)
            NPLogger.e(
                TAG,
                "全选删除因恢复意图未落盘而延后，避免无凭据物理删除"
            )
            return DownloadedSongDeleteResult(
                deletedSongs = emptyList(),
                failedSongs = targetSongs
            )
        }
        updateDownloadedSongDeleteProgress(
            session = session,
            phase = DownloadedSongDeletePhase.STOPPING_DOWNLOADS
        )
        if (deletesEntireCatalog) {
            NPLogger.d(
                TAG,
                "全选删除下载目录，先取消活动下载并保留清理标记直到收敛: songs=${targetSongs.size}"
            )
            val cancellationSettled = withTimeoutOrNull(
                DOWNLOAD_CLEAR_FENCE_WAIT_TIMEOUT_MS
            ) {
                session.clearJob?.join() ?: cancelAllDownloadTasksAndWait()
                while (!isFullLibraryDeleteCancellationSettled(appContext)) {
                    delay(DOWNLOAD_CLEAR_FENCE_WAIT_POLL_MS)
                }
                true
            } == true
            if (!cancellationSettled) {
                updateDownloadedSongDeleteProgress(
                    session = session,
                    phase = DownloadedSongDeletePhase.WAITING_FOR_DOWNLOADS
                )
                // 旧 full-delete fence 可能是上一次进程留下的恢复任务。不能在
                // 持有目录 mutation lease 时无限等待它，更不能与其并发删除文件
                settleDownloadedSongDeleteSession(
                    context = appContext,
                    session = session,
                    deletedSongs = emptyList(),
                    restoredSongs = targetSongs
                )
                NPLogger.w(
                    TAG,
                    "全选删除仍在等待旧下载清理，已保留恢复意图重试: " +
                        "timeoutMs=$DOWNLOAD_CLEAR_FENCE_WAIT_TIMEOUT_MS"
                )
                return DownloadedSongDeleteResult(
                    deletedSongs = emptyList(),
                    failedSongs = targetSongs,
                    physicalCleanupPending = true
                )
            }
        } else {
            val cancellationKeys = try {
                val durableKeys = DownloadExecutionRoomStore
                    .listOperationIdentitiesForStableKeys(appContext, session.deletionKeys)
                    .mapTo(linkedSetOf()) { identity -> identity.stableKey }
                val taskStatuses = taskStore.currentTasks().associate { task ->
                    task.song.stableKey() to task.status
                }
                val activeKeys = session.deletionKeys.filterTo(linkedSetOf()) { songKey ->
                    AudioDownloadManager.isSongDownloadActive(songKey)
                }
                selectDeletionCancellationKeys(
                    requestedKeys = session.deletionKeys,
                    taskStatuses = taskStatuses,
                    durableKeys = durableKeys,
                    activeKeys = activeKeys
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "删除前读取下载 owner 失败，保守取消目标任务: ${error.message}", error)
                session.deletionKeys
            }
            requestDownloadTaskCancellation(cancellationKeys)?.join()
            val activeCancellationKeys =
                awaitDownloadCancellationsSettled(cancellationKeys)
            if (activeCancellationKeys.isNotEmpty()) {
                updateDownloadedSongDeleteProgress(
                    session = session,
                    phase = DownloadedSongDeletePhase.WAITING_FOR_DOWNLOADS
                )
                settleDownloadedSongDeleteSession(
                    context = appContext,
                    session = session,
                    deletedSongs = emptyList(),
                    restoredSongs = targetSongs
                )
                scheduleCatalogReconcile(appContext, forceRefresh = true)
                return DownloadedSongDeleteResult(
                    deletedSongs = emptyList(),
                    failedSongs = targetSongs
                )
            }
        }
        updateDownloadedSongDeleteProgress(
            session = session,
            phase = DownloadedSongDeletePhase.READING_DELETE_PLAN
        )
        val fullLibraryDeletePlan = if (deletesEntireCatalog) {
            try {
                managedDownloadDeletePlanner.buildFullLibraryDeletePlan(appContext, targetSongs).takeIf { plan ->
                    PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(
                        appContext, ManagedDownloadStorage.currentSnapshotCacheKey(appContext), plan.requestedReferences
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "全选删除兜底快照失败，保留恢复意图: ${error.message}",
                    error
                )
                null
            }
        } else {
            null
        }
        val deletePlans = if (deletesEntireCatalog) {
            emptyList()
        } else {
            buildManagedDownloadDeletePlans(
                context = appContext,
                songs = targetSongs
            )
        }
        var requestedReferences = mergeManagedRequestedReferences(
            deletePlans.map(ManagedDownloadSongDeletePlan::requestedReferences) +
                listOfNotNull(fullLibraryDeletePlan?.requestedReferences)
        )
        NPLogger.d(
            TAG,
            "批量删除下载开始: songs=${targetSongs.size}, references=${requestedReferences.size}, " +
                "visible=${downloadedSongsMutable.value.size}"
        )
        updateDownloadedSongDeleteProgress(
            session = session,
            phase = DownloadedSongDeletePhase.DELETING_REFERENCES,
            totalReferenceCount = requestedReferences.size
        )
        val completedReferenceCount = AtomicInteger(0)
        val failedReferenceCount = AtomicInteger(0)
        val onDeleteAttemptFinished: (String, Boolean) -> Unit = { reference, deleted ->
            if (deleted) {
                confirmedDeletedReferences += reference
                completedReferenceCount.incrementAndGet()
            } else {
                failedReferenceCount.incrementAndGet()
            }
            updateDownloadedSongDeleteProgress(
                session = session,
                phase = DownloadedSongDeletePhase.DELETING_REFERENCES,
                totalReferenceCount = requestedReferences.size,
                completedReferenceCount = completedReferenceCount.get(),
                failedReferenceCount = failedReferenceCount.get()
            )
        }
        var deletedReferences = if (requestedReferences.isNotEmpty()) {
            if (deletesEntireCatalog) {
                ManagedDownloadStorage.deleteFullLibraryReferences(
                    context = appContext,
                    references = requestedReferences,
                    onDeleteAttemptFinished = onDeleteAttemptFinished
                )
            } else {
                ManagedDownloadStorage.deleteReferences(
                    context = appContext,
                    references = requestedReferences,
                    onDeleteAttemptFinished = onDeleteAttemptFinished
                )
            }
        } else {
            emptySet()
        }
        updateDownloadedSongDeleteProgress(
            session = session,
            phase = DownloadedSongDeletePhase.VERIFYING_REFERENCES,
            totalReferenceCount = requestedReferences.size,
            completedReferenceCount = deletedReferences.size,
            failedReferenceCount = (requestedReferences.size - deletedReferences.size)
                .coerceAtLeast(0)
        )
        var fullLibrarySnapshotComplete = deletesEntireCatalog &&
            fullLibraryDeletePlan?.snapshotComplete == true
        var verifiedRemainingReferences: Set<String>? = null
        if (deletesEntireCatalog && fullLibraryDeletePlan != null) {
            // 收尾协程可能在第一轮快照后刚好写出 pending。只做一次有界复查，
            // 在清空栅栏内把这类尾部引用一并删除，避免留下永久 .pending
            val verificationPlan = try {
                managedDownloadDeletePlanner.buildFullLibraryDeletePlan(appContext, targetSongs).takeIf { plan ->
                    PersistentDownloadedSongDeleteIntentStore.mergeOwnedReferences(
                        appContext, ManagedDownloadStorage.currentSnapshotCacheKey(appContext), plan.requestedReferences
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                NPLogger.w(
                    TAG,
                    "全选删除尾部快照读取失败，保留恢复意图: ${error.message}",
                    error
                )
                null
            }
            if (verificationPlan?.snapshotComplete == true) {
                fullLibrarySnapshotComplete = true
                val verificationReferences = verificationPlan.requestedReferences
                val residualReferences = verificationReferences - deletedReferences
                requestedReferences = mergeManagedRequestedReferences(
                    listOf(requestedReferences, verificationReferences)
                )
                var residualDeleteFailed = false
                var residualDeletedReferences = emptySet<String>()
                if (residualReferences.isNotEmpty()) {
                    val residualDeleted = try {
                        ManagedDownloadStorage.deleteFullLibraryReferences(
                            context = appContext,
                            references = residualReferences,
                            onDeleteAttemptFinished = onDeleteAttemptFinished
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        fullLibrarySnapshotComplete = false
                        NPLogger.w(
                            TAG,
                            "全选删除尾部引用清理失败，保留恢复意图: ${error.message}",
                            error
                        )
                        residualDeleteFailed = true
                        emptySet()
                    }
                    residualDeletedReferences = residualDeleted
                    deletedReferences = deletedReferences + residualDeleted
                }
                updateDownloadedSongDeleteProgress(
                    session = session,
                    phase = DownloadedSongDeletePhase.VERIFYING_REFERENCES,
                    totalReferenceCount = requestedReferences.size,
                    completedReferenceCount = deletedReferences.size,
                    failedReferenceCount = (
                        requestedReferences.size - deletedReferences.size
                    ).coerceAtLeast(0)
                )
                if (!residualDeleteFailed) {
                    // 复查快照代表当前物理目录事实。即使另一轮幂等删除已经
                    // 抢先移除了对象，不能再用“本轮返回 deleted 为空”复活 catalog
                    // 复查快照可能仍返回第一轮已经删除的旧引用。两轮删除结果都
                    // 必须从快照中扣除，否则幂等删除会被误判成残留并复活歌曲
                    verifiedRemainingReferences = resolveFullLibraryRemainingReferences(
                        verificationReferences = verificationReferences,
                        deletedReferences = deletedReferences,
                        residualDeletedReferences = residualDeletedReferences
                    )
                }
                NPLogger.d(
                    TAG,
                    "全选删除完成尾部快照复查: " +
                        "discovered=${verificationReferences.size}, " +
                        "residual=${residualReferences.size}"
                )
            } else {
                fullLibrarySnapshotComplete = false
                NPLogger.w(
                    TAG,
                    "全选删除尾部快照不完整，保留恢复意图等待下一轮"
                )
            }
        }
        val remainingReferences = verifiedRemainingReferences
            ?: (requestedReferences - deletedReferences)
        val perSongDeletionResult = if (deletesEntireCatalog) {
            DownloadedSongDeleteResult(
                deletedSongs = emptyList(),
                failedSongs = targetSongs
            )
        } else {
            resolveDownloadedSongDeleteResult(
                deletePlans = deletePlans,
                deletedReferences = deletedReferences
            )
        }
        val confirmedMissingAudioReferences = if (
            deletesEntireCatalog && (!fullLibrarySnapshotComplete || remainingReferences.isNotEmpty())
        ) {
            val deletedIndex = ManagedDownloadDeleteReferenceIndex(deletedReferences)
            findConfirmedMissingDownloadedSongs(
                appContext,
                targetSongs.filter { deletedIndex.resolve(it.deletionIdentity()) == null }
            ).mapTo(linkedSetOf(), DownloadedSong::deletionIdentity)
        } else {
            emptySet()
        }
        val deletionResult = resolveConfirmedFullLibraryDeleteResult(
            targetSongs = targetSongs,
            snapshotComplete = fullLibrarySnapshotComplete,
            requestedReferences = requestedReferences,
            deletedReferences = deletedReferences,
            remainingReferences = remainingReferences,
            fallback = perSongDeletionResult,
            confirmedMissingAudioReferences = confirmedMissingAudioReferences
        )
        val fullLibrarySnapshotIncomplete = deletesEntireCatalog &&
            !fullLibrarySnapshotComplete
        if (deletionResult.failedSongs.isNotEmpty()) {
            NPLogger.w(
                TAG,
                "批量删除下载音频不完整: failed=${deletionResult.failedSongs.size}"
            )
        }

        updateDownloadedSongDeleteProgress(
            session = session,
            phase = DownloadedSongDeletePhase.FINALIZING,
            totalReferenceCount = requestedReferences.size,
            completedReferenceCount = deletedReferences.size,
            failedReferenceCount = remainingReferences.size
        )

        // 物理引用删除后只做一次批量凭据收尾。旧实现按歌曲逐条打开
        // Room 事务和 fast index 分片，847 首歌曲会把删除屏障长时间挂住
        var artifactCleanupComplete = true
        var fastIndexCleanupComplete = true
        var artifactCleanupSummary = ""
        var fastIndexFailureCount = 0
        if (
            deletesEntireCatalog &&
                deletionResult.failedSongs.isEmpty() &&
                remainingReferences.isEmpty() &&
                !fullLibrarySnapshotIncomplete
        ) {
            val artifactResult = runCatching {
                managedDownloadArtifactCoordinator
                    .deleteAllAfterCancellationSettled(appContext)
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "全选删除批量清理 artifact 失败，保留恢复意图: " +
                        error.message,
                    error
                )
            }.getOrNull()
            artifactCleanupComplete = artifactResult?.isComplete == true
            artifactCleanupSummary = artifactResult?.let { result ->
                "requested=${result.requestedCount}, removed=${result.removedCount}, " +
                    "missing=${result.missingCount}, raced=${result.racedCount}"
            } ?: "unavailable"
            if (!artifactCleanupComplete) {
                NPLogger.w(
                    TAG,
                    "全选删除发现 artifact 租约仍在变化，保留恢复意图: " +
                        artifactCleanupSummary
                )
            }
            fastIndexCleanupComplete = runCatching {
                ManagedDownloadStorage.clearFastIndexForConfirmedEmptyLibrary(
                    appContext
                )
            }.onFailure { error ->
                NPLogger.w(
                    TAG,
                    "全选删除清空 fast index 异常，保留恢复意图: " +
                        error.message,
                    error
                )
            }.getOrDefault(false)
            if (!fastIndexCleanupComplete) {
                fastIndexFailureCount = 1
            }
        } else if (!deletesEntireCatalog && deletionResult.deletedSongs.isNotEmpty()) {
            val stableKeys = deletionResult.deletedSongs.mapNotNull { song ->
                song.remoteSourceStableKeyOrNull()
                    ?: song.stableKey?.trim()?.takeIf(String::isNotBlank)
            }.toSet()
            if (stableKeys.isNotEmpty()) {
                val artifactResult = runCatching {
                    managedDownloadArtifactCoordinator.deleteByStableKeys(
                        context = appContext,
                        stableKeys = stableKeys
                    )
                }.onFailure { error ->
                    NPLogger.w(
                        TAG,
                        "批量删除 artifact 凭据失败，交给对账重试: " +
                            error.message,
                        error
                    )
                }.getOrNull()
                artifactCleanupComplete = artifactResult?.isComplete == true
                artifactCleanupSummary = artifactResult?.let { result ->
                    "requested=${result.requestedCount}, removed=${result.removedCount}, " +
                        "missing=${result.missingCount}, raced=${result.racedCount}"
                } ?: "unavailable"
                val indexResults = runCatching {
                    ManagedDownloadStorage.removeFastIndexEntries(
                        context = appContext,
                        stableKeys = stableKeys
                    )
                }.onFailure { error ->
                    NPLogger.w(
                        TAG,
                        "批量删除 fast index 异常，交给对账重试: ${error.message}",
                        error
                    )
                }.getOrDefault(emptyList())
                fastIndexFailureCount = indexResults.count { result ->
                    result is ManagedLibraryFastIndexMutationResult.Failed
                }
                fastIndexCleanupComplete = fastIndexFailureCount == 0
            }
        }
        // 先完成 artifact 和索引的批量收尾，再触发 catalog 持久化，
        // 避免延迟中的 catalog job 把刚删除的 Room 行重新写回来
        settleDownloadedSongDeleteSession(
            context = appContext,
            session = session,
            deletedSongs = deletionResult.deletedSongs,
            restoredSongs = deletionResult.failedSongs
        )
        if (
            deletesEntireCatalog &&
                deletionResult.failedSongs.isEmpty() &&
                remainingReferences.isEmpty() &&
                !fullLibrarySnapshotIncomplete &&
                artifactCleanupComplete &&
                fastIndexCleanupComplete
        ) {
            // settle 阶段只合并 catalog 写入，先确认新 catalog 已落盘
            // 再清理删除意图，避免进程在两者之间死亡后恢复出白色歌曲
            cancelScheduledDownloadedSongsCatalogPersist()
            val catalogPersisted = catalogPersistenceMutex.withLock {
                persistConfirmedEmptyDownloadedSongsCatalog(appContext)
            }
            val deleteIntentCleared = catalogPersisted &&
                PersistentDownloadedSongDeleteIntentStore.clear(appContext)
            if (catalogPersisted && !deleteIntentCleared) {
                NPLogger.w(
                    TAG,
                    "全选删除已完成但恢复意图未清理，后续启动将进行幂等复查"
                )
            } else if (!catalogPersisted) {
                NPLogger.w(
                    TAG,
                    "全选删除 catalog 未确认落盘，保留恢复意图等待重试"
                )
            } else {
                // 文件和 catalog 都已经确认删除。清掉任务清空的残留展示状态，
                // 不要求重启应用才能退出阶段 4/4。
                clearPersistedDownloadClearProgress(appContext)
                finishReleasedTaskClearState(appContext)
            }
        } else if (deletesEntireCatalog && fullLibrarySnapshotIncomplete) {
            NPLogger.w(
                TAG,
                "全选删除兜底快照不完整，保留恢复意图等待完整复查"
            )
        } else if (
            deletesEntireCatalog &&
                (!artifactCleanupComplete || !fastIndexCleanupComplete)
        ) {
            NPLogger.w(
                TAG,
                "全选删除凭据收尾未完成，保留恢复意图: " +
                    "artifact=$artifactCleanupSummary, " +
                    "fastIndexComplete=$fastIndexCleanupComplete"
            )
        }
        if (deletesEntireCatalog &&
            PersistentDownloadedSongDeleteIntentStore.hasPending(appContext) &&
            (deletionResult.failedSongs.isNotEmpty() || remainingReferences.isNotEmpty() ||
                fullLibrarySnapshotIncomplete || !artifactCleanupComplete || !fastIndexCleanupComplete)
        ) {
            if (finishUnconfirmedFullLibraryDelete(appContext)) {
                NPLogger.w(TAG, "全选删除仍有未确认文件，已归档引用并释放下载栅栏")
            }
        }
        if (
            !artifactCleanupComplete ||
                fastIndexFailureCount > 0
        ) {
            NPLogger.w(
                TAG,
                "批量删除凭据对账待复查: artifact=$artifactCleanupSummary, " +
                    "fastIndexFailed=$fastIndexFailureCount"
            )
        }
        scheduleCatalogReconcile(
            appContext,
            forceRefresh = deletionResult.failedSongs.isNotEmpty() ||
                remainingReferences.isNotEmpty() ||
                fullLibrarySnapshotIncomplete ||
                fastIndexFailureCount > 0
        )
        val cleanupPending = deletesEntireCatalog &&
            PersistentDownloadedSongDeleteIntentStore.hasPending(appContext)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        NPLogger.i(
            TAG,
            "批量删除下载结束: songs=${targetSongs.size}, requested=${requestedReferences.size}, " +
                "deleted=${deletedReferences.size}, failed=${deletionResult.failedSongs.size}, " +
                "physicalCleanupPending=$cleanupPending, elapsedMs=$elapsedMs, " +
                "targetMs=5000, overBudget=${elapsedMs > 5_000L}"
        )
        updateDownloadedSongDeleteProgress(
            session = session,
            phase = if (deletionResult.failedSongs.isEmpty() &&
                remainingReferences.isEmpty() && !fullLibrarySnapshotIncomplete &&
                artifactCleanupComplete && fastIndexCleanupComplete && !cleanupPending
            ) {
                DownloadedSongDeletePhase.COMPLETED
            } else {
                DownloadedSongDeletePhase.FAILED
            },
            totalReferenceCount = requestedReferences.size,
            completedReferenceCount = deletedReferences.size,
            failedReferenceCount = maxOf(
                remainingReferences.size,
                fullLibraryDeletePlan?.unresolvedPendingReferences?.size ?: 0
            )
        )
        return deletionResult.copy(physicalCleanupPending = cleanupPending)
    } catch (error: CancellationException) {
        val result = interruptedResult()
        updateDownloadedSongDeleteProgress(
            session = session,
            phase = DownloadedSongDeletePhase.FAILED,
            failedReferenceCount = targetSongs.size
        )
        settleDownloadedSongDeleteSession(
            context = appContext,
            session = session,
            deletedSongs = result.deletedSongs,
            restoredSongs = result.failedSongs
        )
        scheduleCatalogReconcile(appContext, forceRefresh = true)
        throw error
    } catch (error: Exception) {
        val result = interruptedResult()
        updateDownloadedSongDeleteProgress(
            session = session,
            phase = DownloadedSongDeletePhase.FAILED,
            failedReferenceCount = targetSongs.size
        )
        settleDownloadedSongDeleteSession(
            context = appContext,
            session = session,
            deletedSongs = result.deletedSongs,
            restoredSongs = result.failedSongs
        )
        scheduleCatalogReconcile(appContext, forceRefresh = true)
        NPLogger.e(TAG, "删除下载文件失败: ${error.message}", error)
        return result
    }
}

internal fun GlobalDownloadManager.updateDownloadedSongDeleteProgress(
    session: DownloadedSongDeleteSession,
    phase: DownloadedSongDeletePhase,
    totalReferenceCount: Int? = null,
    completedReferenceCount: Int? = null,
    failedReferenceCount: Int? = null
) {
    downloadedSongDeleteProgressMutable.update { current ->
        if (current?.deleteId != session.deleteId) {
            current
        } else {
            current.copy(
                phase = phase,
                totalReferenceCount = totalReferenceCount
                    ?: current.totalReferenceCount,
                completedReferenceCount = completedReferenceCount
                    ?: current.completedReferenceCount,
                failedReferenceCount = failedReferenceCount
                    ?: current.failedReferenceCount
            )
        }
    }
}

internal fun GlobalDownloadManager.probeDownloadedSongReferences(
    context: Context,
    song: DownloadedSong
): DownloadedSongReferenceProbe {
    var sawMissing = false
    var sawUncertain = false
    for (reference in downloadedSongPlaybackReferenceCandidates(song)) {
        if (reference.contains(DOWNLOAD_STAGING_DIR_NAME, ignoreCase = true)) {
            sawUncertain = true
            continue
        }
        if (!isOptimisticPlaybackCatalogEntryAllowed(
                context = context,
                reference = reference
            )
        ) {
            sawUncertain = true
            continue
        }
        when (ManagedDownloadReferenceLookup.inspect(context, reference)) {
            ManagedDownloadReferenceLookup.Result.Present -> {
                return DownloadedSongReferenceProbe(
                    reference = ManagedDownloadStorage.toPlayableUri(reference) ?: reference,
                    sawMissing = sawMissing,
                    sawUncertain = sawUncertain
                )
            }
            ManagedDownloadReferenceLookup.Result.Missing -> sawMissing = true
            is ManagedDownloadReferenceLookup.Result.PermissionLost,
            is ManagedDownloadReferenceLookup.Result.ProviderFailure,
            ManagedDownloadReferenceLookup.Result.OutOfScope -> sawUncertain = true
        }
    }
    return DownloadedSongReferenceProbe(
        sawMissing = sawMissing,
        sawUncertain = sawUncertain
    )
}

internal fun GlobalDownloadManager.scheduleDownloadedSongReferenceReconcile(
    context: Context,
    song: DownloadedSong,
    probe: DownloadedSongReferenceProbe
) {
    if (!probe.sawMissing) return
    val songKey = song.remoteSourceStableKeyOrNull()
        ?: song.stableKey?.trim()?.takeIf(String::isNotBlank)
    val hasActiveDownload = songKey?.let(AudioDownloadManager::isSongDownloadActive)
        ?: true
    if (
        shouldEvictMissingDownloadedSongCatalogEntry(
            sawMissing = true,
            sawUncertain = probe.sawUncertain,
            hasActiveDownload = hasActiveDownload
        )
    ) {
        removeMissingDownloadedSongEntry(context, song)
        return
    }
    // 只有所有候选都给出 Missing 证据时才允许强制对账
    scheduleCatalogReconcile(
        context = context,
        forceRefresh = !probe.sawUncertain
    )
}

internal suspend fun GlobalDownloadManager.resolveDownloadedPlaybackWithRetry(
    context: Context,
    downloadedSong: DownloadedSong,
    sourceSong: SongItem,
    playbackReference: String
): DownloadedPlaybackResolution? {
    repeat(DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS) { attemptIndex ->
        val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = context,
            restorePersisted = false
        )
        val cachedAudio = snapshot?.let { currentSnapshot ->
            currentSnapshot.audioEntriesByLookupKey[playbackReference]
                ?: currentSnapshot.pendingAudioEntries.firstOrNull { entry ->
                    entry.reference == playbackReference ||
                        entry.mediaUri == playbackReference ||
                        entry.localFilePath == playbackReference
                }
                ?: ManagedDownloadStorage.findDownloadedAudio(currentSnapshot, sourceSong)
                ?: ManagedDownloadStorage.findPendingDownloadedAudio(currentSnapshot, sourceSong)
        }
        val verifiedAudio = snapshot?.let { currentSnapshot ->
            cachedAudio?.let { audio ->
                resolvePlayableManagedAudioSnapshot(
                    context = context,
                    snapshot = currentSnapshot,
                    candidate = audio,
                    song = sourceSong
                )
            }
        }
        val bridgedAudio = AudioDownloadManager.peekCompletedAudioReference(sourceSong)
        val storedAudio = verifiedAudio?.audio ?: cachedAudio ?: bridgedAudio
        val localReference = verifiedAudio?.reference
            ?: AudioDownloadManager.getLocalPlaybackUri(
                context = context,
                song = sourceSong
            )
        if (!localReference.isNullOrBlank()) {
            return DownloadedPlaybackResolution(
                snapshot = verifiedAudio?.snapshot ?: snapshot,
                audio = storedAudio,
                reference = localReference
            )
        }

        val directReferenceProbe = probeDownloadedSongReferences(
            context = context,
            song = downloadedSong
        )
        directReferenceProbe.reference?.let { directReference ->
            return DownloadedPlaybackResolution(
                snapshot = snapshot,
                audio = storedAudio,
                reference = directReference
            )
        }
        if (attemptIndex == 0) {
            scheduleDownloadedSongReferenceReconcile(
                context = context,
                song = downloadedSong,
                probe = directReferenceProbe
            )
        }

        if (attemptIndex + 1 < DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS) {
            val retryNumber = attemptIndex + 1
            val delayMs = (DOWNLOADED_PLAYBACK_RETRY_BASE_DELAY_MS * retryNumber)
                .coerceAtMost(DOWNLOADED_PLAYBACK_RETRY_MAX_DELAY_MS)
            NPLogger.d(
                TAG,
                "刚提交音频的本地引用尚未稳定，等待重试: " +
                    "song=${sourceSong.name}, retry=$retryNumber/" +
                    "$DOWNLOADED_PLAYBACK_RESOLUTION_ATTEMPTS, delayMs=$delayMs"
            )
            if (attemptIndex == 0) {
                scheduleCatalogReconcile(context, forceRefresh = false)
            }
            delay(delayMs)
            yield()
        }
    }
    return null
}

internal fun GlobalDownloadManager.isLatestDownloadedPlaybackRequest(requestGeneration: Long): Boolean {
    return shouldApplyDownloadedPlaybackRequest(
        requestGeneration = requestGeneration,
        latestGeneration = downloadedPlaybackRequestGeneration.get()
    )
}

internal fun GlobalDownloadManager.hydrateDownloadedSidecarLyricsFast(
    context: Context,
    song: SongItem,
    refreshIfMissing: Boolean = false
): SongItem {
    val lyrics = runCatching {
        if (refreshIfMissing) {
            AudioDownloadManager.getLyricsBundle(context, song)
        } else {
            AudioDownloadManager.getLyricsBundleFast(
                context = context,
                song = song,
                allowColdSafProbe = false
            )
        }
    }.onFailure { error ->
        NPLogger.w(TAG, "下载播放首屏歌词读取失败: ${error.message}")
    }.getOrNull() ?: return song
    return song.copy(
        matchedLyric = if (lyrics.hasOriginalSidecar) {
            lyrics.lyric
        } else {
            song.matchedLyric
        },
        matchedTranslatedLyric = if (lyrics.hasTranslatedSidecar) {
            lyrics.translatedLyric
        } else {
            song.matchedTranslatedLyric
        },
        matchedRomanizedLyric = if (lyrics.hasRomanizedSidecar) {
            lyrics.romanizedLyric
        } else {
            song.matchedRomanizedLyric
        }
    )
}

internal fun GlobalDownloadManager.scheduleDownloadedPlaybackReferenceValidation(
    context: Context,
    song: DownloadedSong,
    playbackReference: String
) {
    scope.launch {
        when (val evidence = ManagedDownloadReferenceLookup.inspect(context, playbackReference)) {
            ManagedDownloadReferenceLookup.Result.Present -> return@launch
            ManagedDownloadReferenceLookup.Result.Missing -> {
                NPLogger.w(
                    TAG,
                    "下载文件后台校验确认缺失: " +
                        "${song.name}, reference=$playbackReference"
                )
                scheduleCatalogReconcile(context, forceRefresh = true)
            }
            is ManagedDownloadReferenceLookup.Result.PermissionLost,
            is ManagedDownloadReferenceLookup.Result.ProviderFailure,
            ManagedDownloadReferenceLookup.Result.OutOfScope -> {
                NPLogger.w(
                    TAG,
                    "下载文件后台校验未取得 Missing 证据，保留目录: " +
                        "${song.name}, reference=$playbackReference, evidence=$evidence"
                )
            }
        }
    }
}

internal fun GlobalDownloadManager.removeMissingDownloadedSongEntry(
    context: Context,
    song: DownloadedSong
) {
    val previousSongs = downloadedSongsMutable.value
    val updatedSongs = previousSongs.filterNot { candidate ->
        matchesDownloadedSongCatalogEntry(candidate, song)
    }
    if (updatedSongs != previousSongs) {
        publishDownloadedSongs(context, updatedSongs, persistCatalog = true)
        NPLogger.w(
            TAG,
            "移除已确认缺失的下载目录条目: song=${song.name}, " +
                "reference=${resolveDownloadedSongPlaybackReference(song)}"
        )
    }
    scheduleCatalogReconcile(context, forceRefresh = true)
}

internal fun GlobalDownloadManager.resolveAudioDuration(context: Context, location: String): Long {
    val uri = when {
        location.startsWith("/") -> Uri.fromFile(File(location))
        else -> location.toUri()
    }
    val quickDuration = runCatching {
        LocalMediaSupport.inspectQuick(context, uri).durationMs
    }.getOrNull()
    if (quickDuration != null && quickDuration > 0L) {
        return quickDuration
    }
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrElse { error ->
        NPLogger.w(TAG, "读取下载音频时长失败: ${error.message}")
        0L
    }
}

internal fun GlobalDownloadManager.isOptimisticPlaybackCatalogEntryAllowed(
    context: Context,
    reference: String
): Boolean {
    if (!reference.contains(PENDING_AUDIO_WRITE_MARKER, ignoreCase = true)) {
        return true
    }
    val snapshot = ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
        context = context,
        restorePersisted = false
    ) ?: return shouldAllowPendingCatalogPlayback(
        referenceIsPending = true,
        catalogEntryAvailable = true,
        snapshotAvailable = false
    )
    val audio = snapshot.audioEntriesByLookupKey[reference]
        ?: snapshot.pendingAudioEntries.firstOrNull { entry ->
            entry.reference == reference ||
                entry.mediaUri == reference ||
                entry.localFilePath == reference
        }
        ?: snapshot.audioEntries.firstOrNull { entry ->
            entry.reference == reference || entry.mediaUri == reference
        }
        ?: return shouldAllowPendingCatalogPlayback(
            referenceIsPending = true,
            catalogEntryAvailable = true,
            snapshotAvailable = false
        )
    val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
    val durableCoreCommitAvailable = isReadableManagedAudioPlaybackAllowed(
        audioIsPending = true,
        downloadActive = false,
        downloadCancelled = false,
        metadata = metadata
    )
    return shouldAllowPendingCatalogPlayback(
        referenceIsPending = true,
        catalogEntryAvailable = true,
        snapshotAvailable = true,
        durableCoreCommitAvailable = durableCoreCommitAvailable
    ) && isReadableManagedAudioPlaybackAllowed(
        audioIsPending = audio.isPendingAudioWrite,
        downloadActive = false,
        downloadCancelled = false,
        metadata = metadata,
        allowLegacyPublishedAudio = !audio.isPendingAudioWrite
    )
}

internal suspend fun GlobalDownloadManager.restorePersistedDownloadedSongs(context: Context): Boolean {
    val persistedSongs = downloadedSongCatalogStore.restore(context)
    if (persistedSongs == null) {
        restoreFastIndexPreview(context)
        return false
    }
    val restoredSongs = filterMissingDeleteRecoveryPreview(context, persistedSongs)
    publishDownloadedSongs(context, restoredSongs, persistCatalog = false)
    runCatching {
        managedDownloadArtifactCoordinator.reconcileCatalog(context, restoredSongs)
    }.onFailure { error ->
        NPLogger.w(TAG, "回填下载 artifact 索引失败: ${error.message}")
    }
    // Room 恢复会拒绝其他根目录的 catalog，因此访问 SAF 前先发布当前 catalog
    downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
    return true
}

internal suspend fun GlobalDownloadManager.restoreFastIndexPreview(context: Context): Boolean {
    val snapshot = ManagedDownloadStorage.restoreFastIndexPreview(context) ?: return false
    val rebuildPlan = ManagedLibraryRebuilder.plan(
        snapshot = snapshot,
        allowIncompleteRootPreview = true
    )
    val songs = filterMissingDeleteRecoveryPreview(context, rebuildDownloadedSongs(
        context = context,
        snapshot = snapshot,
        rebuildPlan = rebuildPlan,
        failureLogPrefix = "解析 Managed fast index 预览失败",
        verifySnapshotReferences = false
    ))
    if (songs.isEmpty()) return false
    publishDownloadedSongs(context, songs, persistCatalog = false)
    downloadedSongCatalogRootKey = ManagedDownloadStorage.currentSnapshotRootKey(context)
    NPLogger.d(TAG, "从 Managed SAF fast index 恢复预览: songs=${songs.size}")
    return true
}

internal fun GlobalDownloadManager.persistDownloadedSongsCatalog(
    context: Context,
    songs: List<DownloadedSong>
): Boolean {
    val persisted = downloadedSongCatalogStore.persist(context, songs)
    if (!persisted) {
        scheduleCatalogReconcile(context, forceRefresh = true)
    }
    return persisted
}

internal fun GlobalDownloadManager.persistConfirmedEmptyDownloadedSongsCatalog(context: Context): Boolean {
    val persisted = downloadedSongCatalogStore.persistConfirmedEmpty(context)
    if (!persisted) {
        scheduleCatalogReconcile(context, forceRefresh = true)
    }
    return persisted
}
