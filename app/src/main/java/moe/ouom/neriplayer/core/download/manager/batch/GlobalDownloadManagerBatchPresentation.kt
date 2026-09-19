package moe.ouom.neriplayer.core.download.manager.batch

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.shouldKeepCancellationCleanup
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.openDownloadAdmissionTicketOrNull
import moe.ouom.neriplayer.core.download.manager.catalog.awaitAllDownloadedSongDeletions
import moe.ouom.neriplayer.core.download.manager.runtime.findFastCachedDownloadedSong
import moe.ouom.neriplayer.core.download.manager.runtime.isMetadataOwnedBySong
import moe.ouom.neriplayer.core.download.manager.runtime.settleAlreadyDownloadedOperation
import moe.ouom.neriplayer.core.download.model.BatchDownloadPresentationState
import moe.ouom.neriplayer.core.download.model.BatchDownloadTerminalState
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.downloadProgressFraction
import moe.ouom.neriplayer.core.download.model.isFinalizedDownloadedAudioEntry
import moe.ouom.neriplayer.core.download.model.resumeBatchDownloadPresentationForRetry
import moe.ouom.neriplayer.core.download.model.shouldApplyTaskMutation
import moe.ouom.neriplayer.core.download.policy.shouldScheduleCancellationConvergence
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PendingDownloadRecoveryDirectSettlement
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.RecoveryDirectSettlementResult
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.BatchDownloadSession
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.catalog.DownloadedSongCatalogIndex
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection


internal fun GlobalDownloadManager.removeDownloadTasks(expectedAttemptIdsBySongKey: Map<String, Long>) {
    taskStore.removeDownloadTasks(expectedAttemptIdsBySongKey)
}

internal fun GlobalDownloadManager.scheduleCompletedTaskRemoval(
    context: Context,
    songKey: String,
    expectedAttemptId: Long? = null,
    admissionTicket: Long? = null
) {
    val appContext = context.applicationContext
    val capturedAdmissionTicket = admissionTicket
        ?: openDownloadAdmissionTicketOrNull(
            context = appContext,
            stableKey = songKey
        )
        ?: return
    scope.launch {
        delay(DOWNLOAD_TASK_COMPLETED_RETENTION_MS)
        admitDownloadMutation(
            context = appContext,
            admissionTicket = capturedAdmissionTicket,
            stableKey = songKey
        ) {
            val task = taskStore.findTask(songKey) ?: return@admitDownloadMutation
            if (
                shouldApplyTaskMutation(task, expectedAttemptId) &&
                task.status == DownloadStatus.COMPLETED
            ) {
                removeDownloadTask(songKey, expectedAttemptId = expectedAttemptId)
            }
        }
    }
}

internal fun GlobalDownloadManager.scheduleCatalogReconcile(context: Context, forceRefresh: Boolean) {
    val appContext = context.applicationContext
    synchronized(catalogPersistenceLock) {
        pendingCatalogReconcileForceRefresh = pendingCatalogReconcileForceRefresh || forceRefresh
        // 清空期间只保留对账意图，避免延迟扫描把已清空的目录重新发布
        if (isDownloadClearFenceActive(appContext)) {
            return
        }
        if (catalogReconcileJob?.isActive == true) {
            return
        }
        catalogReconcileJob = scope.launch {
            delay(DOWNLOAD_CATALOG_RECONCILE_DELAY_MS)
            if (!awaitAllDownloadedSongDeletions()) {
                // 删除仍未收敛时保留对账意图，等待删除完成后的明确触发，
                // 不能把超时当作目录已经稳定，也不能在后台无限轮询
                synchronized(catalogPersistenceLock) {
                    catalogReconcileJob = null
                }
                return@launch
            }
            if (isDownloadClearFenceActive(appContext)) {
                synchronized(catalogPersistenceLock) {
                    catalogReconcileJob = null
                }
                return@launch
            }
            val shouldForceRefresh = synchronized(catalogPersistenceLock) {
                val requestedForceRefresh = pendingCatalogReconcileForceRefresh
                pendingCatalogReconcileForceRefresh = false
                catalogReconcileJob = null
                requestedForceRefresh
            }
            if (isDownloadClearFenceActive(appContext)) {
                return@launch
            }
            scanLocalFiles(appContext, forceRefresh = shouldForceRefresh)
        }
    }
}

internal fun GlobalDownloadManager.rememberPendingDownloadQueue(
    context: Context,
    songs: List<SongItem>,
    userInitiated: Boolean = false,
    requiresWifiNetwork: Boolean = true,
    downloadAudioQuality: DownloadAudioQualitySelection? = null
): List<String> {
    if (songs.isEmpty()) {
        return emptyList()
    }
    val appContext = context.applicationContext
    return songs.distinctBy(SongItem::stableKey).flatMap { song ->
        PersistentDownloadClearFenceStore.withSchedulingPermit(
            context = appContext,
            onFenceActive = { emptyList() },
            stableKey = song.stableKey()
        ) {
            ManagedDownloadStorage.upsertPendingDownloadQueue(
                context = appContext,
                songs = listOf(song),
                userInitiated = userInitiated,
                requiresWifiNetwork = requiresWifiNetwork,
                downloadAudioQuality = downloadAudioQuality
            )
        }
    }
}

internal fun GlobalDownloadManager.beginDownloadRequestGeneration(songs: Collection<SongItem>): Long {
    val snapshot = requestGenerationTracker.begin(songs)
    NPLogger.d(TAG, "登记下载请求代际: generation=${snapshot.generation}, songs=${snapshot.songCount}")
    return snapshot.generation
}

internal fun GlobalDownloadManager.reuseOrBeginDownloadRequestGeneration(
    song: SongItem,
    attemptId: Long
): Long {
    val songKey = song.stableKey()
    val reuseCurrent = taskStore.isDownloadAttemptActive(
        songKey = songKey,
        expectedAttemptId = attemptId
    )
    val currentGeneration = requestGenerationTracker.currentGeneration(songKey)
    val generation = requestGenerationTracker.reuseOrBegin(
        song = song,
        reuseCurrent = reuseCurrent
    )
    if (reuseCurrent && currentGeneration != null) {
        NPLogger.d(
            TAG,
            "复用下载请求代际: generation=$generation, song=${song.name}"
        )
    } else {
        NPLogger.d(TAG, "登记下载请求代际: generation=$generation, songs=1")
    }
    return generation
}

internal data class DownloadBatchSnapshotSelection(
    val identity: DownloadExecutionRoomStore.DownloadBatchIdentity?,
    val songs: List<SongItem>,
    val reusedRequests: List<DownloadExecutionRequest> = emptyList()
)

internal suspend fun GlobalDownloadManager.ensureDurableBatchSnapshot(
    context: Context,
    presentationId: Long,
    songs: Collection<SongItem>,
    initiallyCompletedSongKeys: Set<String> = emptySet(),
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    excludedOperationIds: Set<String> = emptySet()
): DownloadBatchSnapshotSelection? {
    if (presentationId <= 0L || songs.isEmpty()) return null
    durableBatchIdentityByPresentationId[presentationId]?.let { identity ->
        return DownloadBatchSnapshotSelection(identity, songs.distinctBy(SongItem::stableKey))
    }
    val appContext = context.applicationContext
    val prepared = try {
        database.withTransaction {
            val selectedSongs = songs.distinctBy(SongItem::stableKey)
            val states = DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_STATES +
                WAITING_STORAGE_MUTATION_OPERATION_STATE
            val libraryId = DownloadExecutionRoomStore.currentLibraryId(appContext)
            // 先查归属小字段，首次大批下载没有旧 owner 时不读取任何歌曲载荷
            val ownedIds = selectedSongs.map(SongItem::stableKey)
                .filterNot(cancellationForceNewSongKeys::contains)
                .chunked(DownloadExecutionRoomStore.SQLITE_IN_QUERY_CHUNK_SIZE)
                .flatMap { keys ->
                    database.downloadBatchDao().findOpenOwnedOperationIds(libraryId, keys, states)
                }.filterNot(excludedOperationIds::contains)
            val ownedSnapshots = DownloadExecutionRoomStore.readOperationSnapshots(
                appContext, ownedIds, database
            )
            val ownedHeaders = DownloadExecutionRoomStore.readOperationHeaders(appContext, ownedIds, database)
            val reusedRequests = ownedIds.mapNotNull(ownedSnapshots::get)
                .map { it.request }.distinctBy { it.song.stableKey() }
                .filter { request ->
                    ownedHeaders[request.operationId]?.libraryId == libraryId ||
                        DownloadExecutionRoomStore.rehomeOperationToCurrentLibrary(
                            appContext, request.operationId, request.song.stableKey(), states, database
                        )
                }
            val reusedKeys = reusedRequests.mapTo(hashSetOf()) { it.song.stableKey() }
            val newSongs = selectedSongs.filterNot { it.stableKey() in reusedKeys }
            val identity = if (newSongs.isEmpty()) null else {
                DownloadExecutionRoomStore.createBatchSnapshot(
                    context = appContext,
                    songs = newSongs,
                    initiallyCompletedSongKeys = initiallyCompletedSongKeys,
                    clearEpoch = PersistentDownloadClearFenceStore.currentEpoch(appContext),
                    networkGeneration = AudioDownloadManager.currentDownloadNetworkGeneration(),
                    database = database
                )
            }
            DownloadBatchSnapshotSelection(identity, newSongs, reusedRequests)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "创建持久下载批次失败，保留请求等待显式重试: " +
                "presentationId=$presentationId, songs=${songs.size}, error=${error.message}",
            error
        )
        return null
    }
    val identity = prepared.identity ?: return prepared
    val storedIdentity = durableBatchIdentityByPresentationId.putIfAbsent(
        presentationId,
        identity
    ) ?: identity
    batchDownloadPresentationsMutable.update { presentations ->
        val presentation = presentations[presentationId] ?: return@update presentations
        presentations + (
            presentationId to presentation.copy(
                batchId = storedIdentity.batchId,
                batchGeneration = storedIdentity.generation
            )
        )
    }
    return prepared.copy(identity = storedIdentity)
}

internal fun GlobalDownloadManager.persistInitialBatchMemberCompletion(
    context: Context,
    presentationId: Long,
    stableKeys: Collection<String>
) {
    val identity = durableBatchIdentityByPresentationId[presentationId] ?: return
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    if (keys.isEmpty()) return
    scope.launch {
        runCatching {
            DownloadExecutionRoomStore.markInitialBatchMembersCompleted(
                context = context.applicationContext,
                identity = identity,
                stableKeys = keys
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "持久化批次初始完成成员失败，保留 Room 批次等待对账: " +
                    "batchId=${identity.batchId}, error=${error.message}",
                error
            )
        }
    }
}

internal fun GlobalDownloadManager.persistBatchMemberProgress(
    context: Context,
    progress: AudioDownloadManager.DownloadProgress
) {
    val operationId = progress.operationId?.takeIf(String::isNotBlank) ?: return
    val fractionMilli = (downloadProgressFraction(progress) * 1_000f).toInt()
        .coerceIn(0, 1_000)
    scope.launch {
        runCatching {
            DownloadExecutionRoomStore.updateBatchMembersForOperation(
                context = context.applicationContext,
                operationId = operationId,
                stableKey = progress.songKey,
                attemptId = progress.attemptId,
                fractionMilli = fractionMilli
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "持久化批次成员进度失败，保留 operation 检查点等待恢复: " +
                    "operationId=$operationId, error=${error.message}",
                error
            )
        }
    }
}

internal fun GlobalDownloadManager.persistBatchMemberTerminal(
    context: Context,
    songKey: String,
    attemptId: Long?,
    terminalState: BatchDownloadTerminalState,
    operationId: String? = null,
    capturedTargets: Map<DownloadExecutionRoomStore.DownloadBatchIdentity, String> = emptyMap(),
    observedFractionMilli: Int = 0
) {
    val terminalBits = when (terminalState) {
        BatchDownloadTerminalState.COMPLETED -> DownloadBatchMemberTerminal.COMPLETED
        BatchDownloadTerminalState.FAILED -> DownloadBatchMemberTerminal.FAILED
        BatchDownloadTerminalState.CANCELLED -> DownloadBatchMemberTerminal.CANCELLED
    }
    scope.launch {
        runCatching {
            val normalizedOperationId = operationId?.takeIf(String::isNotBlank)
            val identitiesByOperation = capturedTargets.toMutableMap()
            // 进程重连后 UI 投影可能尚未恢复，仍可从当前 operation 的持久身份恢复。
            // 不能再遍历异步执行时的 UI 状态，否则旧回调可能命中新批次
            if (identitiesByOperation.isEmpty()) {
                normalizedOperationId?.let { boundOperationId ->
                    val request = DownloadExecutionRoomStore.read(
                        context = context.applicationContext,
                        operationId = boundOperationId
                    )
                    if (request?.batchId != null && request.batchGeneration != null) {
                        identitiesByOperation.putIfAbsent(
                            DownloadExecutionRoomStore.DownloadBatchIdentity(
                                batchId = request.batchId,
                                generation = request.batchGeneration
                            ),
                            boundOperationId
                        )
                    }
                }
            }
            if (identitiesByOperation.isEmpty()) {
                NPLogger.d(
                    TAG,
                    "批次终态回调缺少匹配的 batch/operation 身份，拒绝按 stableKey 写入: " +
                        "songKey=$songKey, operationId=$normalizedOperationId, attemptId=$attemptId"
                )
            } else {
                identitiesByOperation.forEach { (identity, boundOperationId) ->
                    DownloadExecutionRoomStore.markBatchMemberTerminal(
                        context = context.applicationContext,
                        identity = identity,
                        stableKey = songKey,
                        operationId = boundOperationId,
                        attemptId = attemptId,
                        terminalBits = terminalBits,
                        fractionMilli = observedFractionMilli
                    )
                }
            }
            normalizedOperationId?.let { boundOperationId ->
                // UI 投影缺失或已被清理时，按 operation 直接回写 Room 批次成员，
                // 让末尾终态不依赖内存里的 memberOperationIds
                DownloadExecutionRoomStore.markBatchMembersForOperation(
                    context = context.applicationContext,
                    operationId = boundOperationId,
                    stableKey = songKey,
                    attemptId = attemptId,
                    terminalBits = terminalBits,
                    fractionMilli = observedFractionMilli
                )
            }
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "持久化批次成员终态失败，保留 operation 状态等待恢复: " +
                    "songKey=$songKey, error=${error.message}",
                error
            )
        }
    }
}

internal fun GlobalDownloadManager.beginBatchDownloadPresentation(
    songs: Collection<SongItem>,
    batchId: Long = batchDownloadPresentationIdGenerator.incrementAndGet()
): Long {
    val activeAttemptIdsBySongKey = taskStore.currentTasks()
        .associate { task -> task.song.stableKey() to task.attemptId }
    val memberAttemptIds = songs
        .asSequence()
        .map(SongItem::stableKey)
        .filter(String::isNotBlank)
        .distinct()
        .associateWith { songKey ->
            activeAttemptIdsBySongKey[songKey]
                ?.takeIf { attemptId -> attemptId > 0L }
        }
    val durableIdentity = durableBatchIdentityByPresentationId[batchId]
    batchDownloadPresentationsMutable.update { presentations ->
        presentations + (
            batchId to BatchDownloadPresentationState(
                id = batchId,
                memberAttemptIds = memberAttemptIds,
                batchId = durableIdentity?.batchId,
                batchGeneration = durableIdentity?.generation
            )
        )
    }
    return batchId
}

internal fun GlobalDownloadManager.seedInitialBatchDownloadPresentation(
    context: Context,
    batchId: Long,
    songs: Collection<SongItem>,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null,
    knownCompletedSongKeys: Set<String> = emptySet()
) {
    if (batchId <= 0L || songs.isEmpty() || isDownloadClearFenceActive(context)) {
        return
    }
    val currentSnapshot = snapshot?.takeIf { it.rootEntriesComplete }
    val activeTaskSongKeys = taskStore.currentTasks()
        .asSequence()
        .filter { task ->
            task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.WAITING_NETWORK
        }
        .mapTo(linkedSetOf()) { task -> task.song.stableKey() }
    val completedSongKeys = findStrictlyCompletedBatchSongKeys(
        songs = songs,
        snapshot = currentSnapshot
    ).filterTo(linkedSetOf()) { songKey ->
        songKey !in activeTaskSongKeys &&
            !isDownloadClearFenceActive(context, stableKey = songKey)
    }
    val explicitlyKnownCompletedKeys = knownCompletedSongKeys
        .map(String::trim)
        .filter(String::isNotBlank)
        // 这些 key 已经过 artifact/catalog 的可读性预检；旧 task 行不能
        // 把已经提交的音频重新伪装成未完成，否则尾项会一直被重试
        .filter { songKey -> !isDownloadClearFenceActive(context, stableKey = songKey) }
        .toSet()
    val allCompletedSongKeys = completedSongKeys + explicitlyKnownCompletedKeys
    if (allCompletedSongKeys.isEmpty()) {
        return
    }
    batchDownloadPresentationsMutable.update { presentations ->
        val presentation = presentations[batchId] ?: return@update presentations
        val newlyCompletedKeys = allCompletedSongKeys.filter { songKey ->
            songKey in presentation.memberAttemptIds &&
                presentation.terminalStates[songKey] == null
        }
        if (newlyCompletedKeys.isEmpty()) {
            return@update presentations
        }
        val completedStates = newlyCompletedKeys.associateWith {
            BatchDownloadTerminalState.COMPLETED
        }
        val completedFractions = newlyCompletedKeys.associateWith { 1f }
        presentations + (
            batchId to presentation.copy(
                terminalStates = presentation.terminalStates + completedStates,
                maximumObservedFractions =
                    presentation.maximumObservedFractions + completedFractions,
                initiallyCompletedSongKeys =
                    presentation.initiallyCompletedSongKeys + newlyCompletedKeys
            )
        )
    }
    persistInitialBatchMemberCompletion(
        context = context,
        presentationId = batchId,
        stableKeys = allCompletedSongKeys
    )
    NPLogger.d(
        TAG,
        "批量下载复用已存在音频的初始进度: " +
            "completed=${allCompletedSongKeys.size}, total=${songs.size}"
    )
}

internal fun GlobalDownloadManager.isUsableInitialDownloadedAudio(
    audio: ManagedDownloadStorage.StoredEntry
): Boolean {
    return !audio.isPendingAudioWrite &&
        (audio.sizeBytes > 0L || !audio.sizeKnown) &&
        ManagedDownloadStorage.resolveStoredEntryPlaybackUri(audio) != null
}

internal suspend fun GlobalDownloadManager.findFastCompletedBatchSongKeys(
    context: Context,
    songs: Collection<SongItem>,
    alreadyCompletedSongKeys: Set<String> = emptySet(),
    catalogIndex: DownloadedSongCatalogIndex = downloadedSongCatalogIndex,
    preflightProbe: BatchDownloadPreflightProbe = BatchDownloadPreflightProbe { reference ->
        ManagedDownloadReferenceLookup.inspect(context, reference)
    }
): Set<String> {
    val coroutineContext = currentCoroutineContext()
    coroutineContext.ensureActive()
    val activeTaskSongKeys = taskStore.currentTasks()
        .asSequence()
        .filter { task -> task.status != DownloadStatus.COMPLETED }
        .mapTo(linkedSetOf()) { task -> task.song.stableKey() }
    val candidates = songs
        .asSequence()
        .filter { song -> song.stableKey() !in alreadyCompletedSongKeys }
        .toList()
    if (candidates.isEmpty()) return emptySet()
    val catalogCompleted = linkedSetOf<String>()
    val catalogCandidates = candidates
        .asSequence()
        .filter { song -> song.stableKey() !in activeTaskSongKeys }
        .filter { song -> catalogIndex.find(song) != null }
    val inspectReference: (String) -> ManagedDownloadReferenceLookup.Result? = { reference ->
        coroutineContext.ensureActive()
        preflightProbe.inspect(reference)
    }
    for ((index, song) in catalogCandidates.withIndex()) {
        coroutineContext.ensureActive()
        if (preflightProbe.isExhausted) break
        findFastCachedDownloadedSong(
            context = context,
            song = song,
            catalogIndex = catalogIndex,
            inspectReference = inspectReference
        )?.let {
            song.stableKey().takeIf(String::isNotBlank)?.let(catalogCompleted::add)
        }
        if ((index + 1) % BATCH_FAST_COMPLETION_PROBE_CHUNK_SIZE == 0) {
            yield()
        }
    }
    coroutineContext.ensureActive()
    val artifactCompleted = runCatching {
        managedDownloadArtifactCoordinator.findReadableCompletedStableKeys(
            context = context,
            stableKeys = candidates.map(SongItem::stableKey).filterNot(catalogCompleted::contains),
            inspectReference = inspectReference
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            TAG,
            "批量预检读取 artifact 完成索引失败，保留常规准备路径: ${error.message}",
            error
        )
    }.getOrDefault(emptySet())
    return catalogCompleted + artifactCompleted
}

internal fun GlobalDownloadManager.findStrictlyCompletedBatchSongKeys(
    songs: Collection<SongItem>,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?
): Set<String> {
    val currentSnapshot = snapshot?.takeIf { it.rootEntriesComplete } ?: return emptySet()
    return songs.mapNotNull { song ->
        val songKey = song.stableKey().trim().takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val audio = ManagedDownloadStorage.findDownloadedAudioIncludingMetadataLess(
            snapshot = currentSnapshot,
            song = song
        ) ?: return@mapNotNull null
        if (!isUsableInitialDownloadedAudio(audio)) return@mapNotNull null
        val metadata = ManagedDownloadStorage.metadataForAudioEntry(
            snapshot = currentSnapshot,
            audio = audio
        )
        if (metadata == null) {
            // metadata-less 但正式、可播放且文件名与歌曲唯一匹配的音频
            // 仍是已完成内容；后续只需补写 metadata，不应再次传输
            songKey.takeIf { matchesExpectedDownloadFileName(song, audio) }
        } else if (
            isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = true,
                isPendingAudioWrite = audio.isPendingAudioWrite,
                metadata = metadata
            ) && isMetadataOwnedBySong(metadata, song)
        ) {
            songKey
        } else {
            null
        }
    }.toSet()
}

internal suspend fun GlobalDownloadManager.bindBatchDownloadPresentationAttempts(
    batchId: Long,
    attemptIdsBySongKey: Map<String, Long>,
    operationIdsBySongKey: Map<String, String> = emptyMap()
) {
    if (attemptIdsBySongKey.isEmpty()) {
        return
    }
    val presentation = batchDownloadPresentationsMutable.value[batchId] ?: return
    val newlyBoundKeys = attemptIdsBySongKey
        .filter { (songKey, attemptId) ->
            attemptId > 0L && presentation.memberAttemptIds[songKey] != attemptId
        }
        .keys
    val updatedMemberAttemptIds = presentation.memberAttemptIds.mapValues { (songKey, attemptId) ->
        attemptIdsBySongKey[songKey]
            ?.takeIf { candidate -> candidate > 0L }
            ?: attemptId
    }
    val updatedMemberOperationIds = presentation.memberOperationIds +
        operationIdsBySongKey.filterValues(String::isNotBlank)
    val reboundKeys = attemptIdsBySongKey.keys.filter { songKey ->
        val oldAttempt = presentation.memberAttemptIds[songKey]
        val newAttempt = attemptIdsBySongKey[songKey]
        val oldOperation = presentation.memberOperationIds[songKey]
        val newOperation = operationIdsBySongKey[songKey]
        (newAttempt != null && newAttempt != oldAttempt) ||
            (newOperation != null && newOperation != oldOperation)
    }.toSet()
    val updatedPresentation = presentation.copy(
        memberAttemptIds = updatedMemberAttemptIds,
        memberOperationIds = updatedMemberOperationIds,
        terminalStates = presentation.terminalStates.filterKeys { songKey ->
            songKey !in newlyBoundKeys || songKey in presentation.initiallyCompletedSongKeys
        },
        maximumObservedFractions = presentation.maximumObservedFractions
            .filterKeys { songKey -> songKey !in reboundKeys },
        initiallyCompletedSongKeys = presentation.initiallyCompletedSongKeys
            .filter { songKey -> songKey !in reboundKeys }
            .toSet()
    )
    if (updatedPresentation == presentation) {
        return
    }
    val identity = presentation.batchId?.let { durableBatchId ->
        presentation.batchGeneration?.let { generation ->
            DownloadExecutionRoomStore.DownloadBatchIdentity(durableBatchId, generation)
        }
    }
    if (identity != null) {
        val operationBindings = attemptIdsBySongKey.mapNotNull { (songKey, attemptId) ->
            val operationId = operationIdsBySongKey[songKey]
                ?.takeIf(String::isNotBlank)
                ?: presentation.memberOperationIds[songKey]
                ?: return@mapNotNull null
            DownloadExecutionRoomStore.BatchMemberBinding(
                stableKey = songKey,
                operationId = operationId,
                attemptId = attemptId
            )
        }
        if (operationBindings.isNotEmpty()) {
            val boundCount = runCatching {
                DownloadExecutionRoomStore.bindBatchMemberOperations(
                    context = AppContainer.applicationContext,
                    identity = identity,
                    bindings = operationBindings
                )
            }.getOrElse { error ->
                NPLogger.w(
                    TAG,
                    "批量展示绑定 Room 失败，保留旧展示身份: batchId=$batchId, " +
                        "error=${error.message}",
                    error
                )
                return
            }
            if (boundCount < operationBindings.size) {
                NPLogger.w(
                    TAG,
                    "批量展示绑定 Room 不完整，保留旧展示身份: batchId=$batchId, " +
                        "bound=$boundCount, expected=${operationBindings.size}"
                )
                return
            }
        }
    }
    batchDownloadPresentationsMutable.update { presentations ->
        if (presentations[batchId] != presentation) {
            presentations
        } else {
            presentations + (batchId to updatedPresentation)
        }
    }
}

internal fun GlobalDownloadManager.updateBatchDownloadPresentationProgress(
    progress: AudioDownloadManager.DownloadProgress
) {
    val songKey = progress.songKey
    val fraction = downloadProgressFraction(progress)
    batchDownloadPresentationsMutable.update { presentations ->
        var changed = false
        val updatedPresentations = presentations.mapValues { (_, presentation) ->
            val expectedAttemptId = presentation.memberAttemptIds[songKey]
            val expectedOperationId = presentation.memberOperationIds[songKey]
            val hasDurableIdentity = presentation.batchId != null &&
                presentation.batchGeneration != null
            if (
                songKey !in presentation.memberAttemptIds ||
                    (hasDurableIdentity &&
                        (expectedAttemptId == null || expectedOperationId == null)) ||
                    (expectedAttemptId != null && expectedAttemptId != progress.attemptId) ||
                    (expectedOperationId != null && expectedOperationId != progress.operationId)
            ) {
                return@mapValues presentation
            }
            val retainedFraction = presentation.maximumObservedFractions[songKey] ?: 0f
            if (fraction <= retainedFraction) {
                return@mapValues presentation
            }
            changed = true
            val updatedOperationIds = progress.operationId
                ?.takeIf(String::isNotBlank)
                ?.let { operationId -> presentation.memberOperationIds + (songKey to operationId) }
                ?: presentation.memberOperationIds
            presentation.copy(
                memberOperationIds = updatedOperationIds,
                maximumObservedFractions = presentation.maximumObservedFractions +
                    (songKey to fraction)
            )
        }
        if (changed) updatedPresentations else presentations
    }
}

internal fun GlobalDownloadManager.markBatchDownloadPresentationTerminal(
    songKey: String,
    attemptId: Long?,
    terminalState: BatchDownloadTerminalState,
    operationId: String? = null
) {
    val normalizedAttemptId = attemptId?.takeIf { it > 0L }
    if (normalizedAttemptId == null && operationId.isNullOrBlank()) {
        return
    }
    val completedBatchIds = linkedSetOf<Long>()
    val persistedTargets = linkedMapOf<
        DownloadExecutionRoomStore.DownloadBatchIdentity,
        String
    >()
    var observedFractionMilli = 0
    batchDownloadPresentationsMutable.update { presentations ->
        var changed = false
        val updatedPresentations = presentations.mapValues { (presentationId, presentation) ->
            val memberAttemptId = presentation.memberAttemptIds[songKey]
            val memberOperationId = presentation.memberOperationIds[songKey]
            val normalizedOperationId = operationId?.takeIf(String::isNotBlank)
            val effectiveOperationId = normalizedOperationId ?: memberOperationId
            val effectiveAttemptId = normalizedAttemptId ?: memberAttemptId
            val hasDurableIdentity = presentation.batchId != null &&
                presentation.batchGeneration != null
            val initialCompletion = songKey in presentation.initiallyCompletedSongKeys &&
                presentation.terminalStates[songKey] ==
                BatchDownloadTerminalState.COMPLETED
            if (
                songKey !in presentation.memberAttemptIds ||
                    (hasDurableIdentity &&
                        effectiveOperationId == null) ||
                    (memberAttemptId != null && normalizedAttemptId != null &&
                        memberAttemptId != normalizedAttemptId) ||
                    (memberOperationId != null && normalizedOperationId != null &&
                        memberOperationId != normalizedOperationId) ||
                    (presentation.terminalStates[songKey] != null && !initialCompletion)
            ) {
                return@mapValues presentation
            }
            val updatedMemberAttemptIds = if (memberAttemptId == null) {
                effectiveAttemptId?.let { value ->
                    presentation.memberAttemptIds + (songKey to value)
                } ?: presentation.memberAttemptIds
            } else {
                presentation.memberAttemptIds
            }
            val updatedMemberOperationIds = if (
                normalizedOperationId != null
            ) {
                presentation.memberOperationIds + (songKey to normalizedOperationId)
            } else {
                presentation.memberOperationIds
            }
            val updatedTerminalStates = presentation.terminalStates +
                (songKey to terminalState)
            if (hasDurableIdentity) {
                persistedTargets[DownloadExecutionRoomStore.DownloadBatchIdentity(
                    batchId = checkNotNull(presentation.batchId),
                    generation = checkNotNull(presentation.batchGeneration)
                )] = checkNotNull(effectiveOperationId)
                observedFractionMilli = maxOf(
                    observedFractionMilli,
                    ((presentation.maximumObservedFractions[songKey] ?: 0f) * 1_000f)
                        .toInt()
                        .coerceIn(0, 1_000)
                )
            }
            val updatedPresentation = presentation.copy(
                memberAttemptIds = updatedMemberAttemptIds,
                memberOperationIds = updatedMemberOperationIds,
                terminalStates = updatedTerminalStates,
                initiallyCompletedSongKeys =
                    presentation.initiallyCompletedSongKeys - songKey
            )
            if (updatedTerminalStates.size == updatedMemberAttemptIds.size) {
                completedBatchIds += presentationId
            }
            changed = true
            updatedPresentation
        }
        if (changed) updatedPresentations else presentations
    }
    completedBatchIds.forEach(::scheduleCompletedBatchDownloadPresentationRemoval)
    persistBatchMemberTerminal(
        context = AppContainer.applicationContext,
        songKey = songKey,
        attemptId = normalizedAttemptId,
        terminalState = terminalState,
        operationId = operationId,
        capturedTargets = persistedTargets,
        observedFractionMilli = observedFractionMilli
    )
}

internal fun GlobalDownloadManager.resumeBatchDownloadPresentationOnRetry(
    songKey: String,
    attemptId: Long
) {
    batchDownloadPresentationsMutable.update { presentations ->
        var changed = false
        val updatedPresentations = presentations.mapValues { (_, presentation) ->
            val updatedPresentation = resumeBatchDownloadPresentationForRetry(
                presentation = presentation,
                songKey = songKey,
                attemptId = attemptId
            )
            if (updatedPresentation != presentation) {
                changed = true
            }
            updatedPresentation
        }
        if (changed) updatedPresentations else presentations
    }
}

internal fun GlobalDownloadManager.clearInitialBatchDownloadPresentationOnTransferStart(
    songKey: String,
    attemptId: Long,
    operationId: String?
) {
    batchDownloadPresentationsMutable.update { presentations ->
        var changed = false
        val updatedPresentations = presentations.mapValues { (_, presentation) ->
            val expectedAttemptId = presentation.memberAttemptIds[songKey]
            val expectedOperationId = presentation.memberOperationIds[songKey]
            val hasDurableIdentity = presentation.batchId != null &&
                presentation.batchGeneration != null
            if (
                songKey !in presentation.initiallyCompletedSongKeys ||
                    (hasDurableIdentity &&
                        (expectedAttemptId == null || expectedOperationId == null)) ||
                    (expectedAttemptId != null && expectedAttemptId != attemptId)
                    || (expectedOperationId != null && expectedOperationId != operationId)
            ) {
                return@mapValues presentation
            }
            changed = true
            presentation.copy(
                terminalStates = presentation.terminalStates - songKey,
                maximumObservedFractions =
                    presentation.maximumObservedFractions - songKey,
                initiallyCompletedSongKeys =
                    presentation.initiallyCompletedSongKeys - songKey
            )
        }
        if (changed) updatedPresentations else presentations
    }
}

internal fun GlobalDownloadManager.scheduleCompletedBatchDownloadPresentationRemoval(batchId: Long) {
    scope.launch {
        delay(DOWNLOAD_TASK_COMPLETED_RETENTION_MS)
        var removed = false
        batchDownloadPresentationsMutable.update { presentations ->
            val presentation = presentations[batchId] ?: return@update presentations
            if (presentation.terminalStates.size == presentation.memberAttemptIds.size) {
                removed = true
                presentations - batchId
            } else {
                presentations
            }
        }
        if (removed) {
            durableBatchIdentityByPresentationId.remove(batchId)
        }
    }
}

internal fun GlobalDownloadManager.clearBatchDownloadPresentationWithoutOutstandingWork(
    session: BatchDownloadSession
) {
    if (
        session.pendingSongs.isNotEmpty() ||
            session.settledSongKeys.isNotEmpty() ||
            session.handedOffSongKeys.isNotEmpty()
    ) {
        return
    }
    clearBatchDownloadPresentation(session.batchPresentationId)
}

internal suspend fun GlobalDownloadManager.cancelBatchDownloadPresentationMembers(
    batchId: Long,
    songKeys: Collection<String>,
    identity: DownloadExecutionRoomStore.DownloadBatchIdentity
) {
    val keys = songKeys.map(String::trim).filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) return
    DownloadExecutionRoomStore.markBatchMembersCancelled(
        context = AppContainer.applicationContext,
        identity = identity,
        stableKeys = keys
    )
    var becameComplete = false
    batchDownloadPresentationsMutable.update { presentations ->
        val presentation = presentations[batchId] ?: return@update presentations
        val terminalStates = presentation.terminalStates + keys.associateWith {
            BatchDownloadTerminalState.CANCELLED
        }
        becameComplete = terminalStates.keys.containsAll(presentation.memberAttemptIds.keys)
        presentations + (
            batchId to presentation.copy(terminalStates = terminalStates)
        )
    }
    if (becameComplete) {
        scheduleCompletedBatchDownloadPresentationRemoval(batchId)
    }
}

internal fun GlobalDownloadManager.isDownloadRequestGenerationCurrent(
    songKey: String,
    generation: Long
): Boolean {
    return requestGenerationTracker.isCurrent(songKey, generation)
}

internal fun GlobalDownloadManager.isCancellationCleanupStillCurrent(
    songKey: String,
    cancellationGeneration: Long?
): Boolean {
    // 新请求可以在旧传输退出前登记，但旧 operation 的 lease 仍必须
    // 收敛。operation 身份已经固定时，代次变化不能中断这条清理路径
    if (
        (cancellationOperationIdsBySongKey[songKey]?.isNotEmpty() == true ||
            hasCancellationSnapshotBoundary(songKey)) &&
            (cancellationCleanupActiveSongKeys.contains(songKey) ||
                isSongCancelled(songKey) ||
                cancellationConvergenceJobs.containsKey(songKey))
    ) {
        return true
    }
    return requestGenerationTracker.shouldKeepCancellationCleanup(
        songKey = songKey,
        cancellationGeneration = cancellationGeneration,
        cancelled = isSongCancelled(songKey)
    )
}

internal fun GlobalDownloadManager.forgetPendingDownloadQueueEntries(
    context: Context,
    songKeys: Collection<String>
) {
    val keys = songKeys.filter(String::isNotBlank)
    if (keys.isEmpty()) {
        return
    }
    ManagedDownloadStorage.removePendingDownloadQueueEntries(context, keys)
}

internal suspend fun GlobalDownloadManager.purgeSettledRecoveryEntries(
    context: Context,
    operationIds: Collection<String>,
    songKeys: Collection<String>
) {
    val ids = operationIds
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    if (ids.isEmpty()) {
        // 没有稳定 operation 身份时宁可留下旧队列，下一轮恢复仍可重新判定，
        // 也不能按 stable key 抹掉刚创建的替代请求
        if (songKeys.any(String::isNotBlank)) {
            NPLogger.d(
                TAG,
                "恢复收尾缺少 operation 身份，保留持久队列: " +
                    "songs=${songKeys.count(String::isNotBlank)}"
            )
        }
        return
    }
    ManagedDownloadStorage.removePendingDownloadQueueOperationIds(
        context = context,
        operationIds = ids
    )
    DownloadExecutionRoomStore.purgeCancelledOperationIds(
        context = context,
        operationIds = ids
    )
}

internal suspend fun GlobalDownloadManager.settlePendingDownloadRecoveryDirectHits(
    context: Context,
    settlements: Collection<PendingDownloadRecoveryDirectSettlement>
): RecoveryDirectSettlementResult {
    val settledSongKeys = linkedSetOf<String>()
    val settledOperationIds = linkedSetOf<String>()
    val failedSongKeys = linkedSetOf<String>()
    settlements.forEach { settlement ->
        val settled = settleAlreadyDownloadedOperation(
            context = context,
            song = settlement.song,
            operationId = settlement.operationId,
            expectedAttemptId = settlement.attemptId,
            reason = "RECOVERY_ALREADY_PRESENT"
        )
        if (settled) {
            settledSongKeys += settlement.song.stableKey()
            settledOperationIds += settlement.operationId
            settlement.workingFile?.let(ManagedDownloadStorage::deleteWorkingDownloadArtifacts)
        } else {
            failedSongKeys += settlement.song.stableKey()
            NPLogger.w(
                TAG,
                "恢复命中已下载音频但 operation CAS 未完成，保留队列证据: " +
                    "song=${settlement.song.name}, operationId=${settlement.operationId}"
            )
        }
    }
    return RecoveryDirectSettlementResult(
        settledSongKeys = settledSongKeys,
        settledOperationIds = settledOperationIds,
        failedSongKeys = failedSongKeys
    )
}

internal fun GlobalDownloadManager.forgetPendingDownloadQueueEntriesForOperation(
    context: Context,
    songKey: String,
    operationId: String?
) {
    val normalizedOperationId = operationId
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (normalizedOperationId != null) {
        ManagedDownloadStorage.removePendingDownloadQueueOperationIds(
            context = context,
            operationIds = setOf(normalizedOperationId)
        )
        return
    }
    val currentOperationId = runCatching {
        DownloadExecutionHosts.default.operationIdForSong(
            context = context,
            songKey = songKey
        )
    }.getOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (currentOperationId != null) {
        ManagedDownloadStorage.removePendingDownloadQueueOperationIds(
            context = context,
            operationIds = setOf(currentOperationId)
        )
    } else {
        NPLogger.d(
            TAG,
            "完成收尾缺少 operation 身份，保留持久队列等待恢复: songKey=$songKey"
        )
    }
}

internal fun GlobalDownloadManager.forgetPendingDownloadQueueEntriesIfCurrent(
    context: Context,
    songKeys: Collection<String>,
    generation: Long
) {
    val currentKeys = songKeys
        .filter(String::isNotBlank)
        .filter { songKey -> isDownloadRequestGenerationCurrent(songKey, generation) }
    if (currentKeys.isEmpty()) {
        val requestedCount = songKeys.count { it.isNotBlank() }
        if (requestedCount > 0) {
            NPLogger.d(TAG, "跳过过期队列移除: generation=$generation, requested=$requestedCount")
        }
        return
    }
    forgetPendingDownloadQueueEntries(context, currentKeys)
}

internal fun GlobalDownloadManager.clearPendingDownloadQueue(
    context: Context,
    stableKeys: Collection<String>? = null
) {
    if (stableKeys == null) {
        ManagedDownloadStorage.clearPendingDownloadQueue(context)
    } else {
        ManagedDownloadStorage.removePendingDownloadQueueEntries(context, stableKeys)
    }
}

internal fun GlobalDownloadManager.requestCancellationOperationSnapshot(
    context: Context,
    songKey: String,
    snapshotAtMs: Long = System.currentTimeMillis(),
    knownOperationId: String? = null
): Unit {
    val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return
    val normalizedSnapshotAtMs = snapshotAtMs.coerceAtLeast(0L)
    val snapshotCutoff = cancellationOperationSnapshotCutoffs.putIfAbsent(
        normalizedKey,
        normalizedSnapshotAtMs
    ) ?: normalizedSnapshotAtMs
    cancellationOperationSnapshotJobs[normalizedKey]?.let { existing ->
        existing.start()
        return
    }
    val appContext = context.applicationContext
    val created = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        val persistedIds = try {
            DownloadExecutionRoomStore.findOperationIdsForSong(
                context = appContext,
                songKey = normalizedKey,
                createdAtMsAtMost = snapshotCutoff
            )
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "取消入口读取 operation 身份失败，保留后续恢复凭据: " +
                    "songKey=$normalizedKey, error=${error.message}",
                error
            )
            throw error
        }
        // 空结果只有在查询真正成功时才可作为已解析快照
        cancellationOperationSnapshotResolvedKeys.add(normalizedKey)
        val ids = buildSet {
            addAll(cancellationOperationIdsBySongKey[normalizedKey].orEmpty())
            persistedIds.mapTo(this, String::trim)
            knownOperationId
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
        if (ids.isNotEmpty()) {
            cancellationOperationIdsBySongKey[normalizedKey] = ids
        }
        ids
    }
    val selected = cancellationOperationSnapshotJobs.putIfAbsent(normalizedKey, created)
        ?: created
    if (selected === created) {
        created.invokeOnCompletion { failure ->
            cancellationOperationSnapshotJobs.remove(normalizedKey, created)
            if (failure != null) {
                cancellationOperationSnapshotResolvedKeys.remove(normalizedKey)
            }
        }
    } else {
        created.cancel()
    }
    selected.start()
}

internal suspend fun GlobalDownloadManager.captureCancellationOperationIds(
    context: Context,
    songKey: String,
    fallbackOperationId: String? = null
): Set<String> {
    val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return emptySet()
    if (
        hasCancellationSnapshotBoundary(normalizedKey) &&
            !isCancellationSnapshotResolved(normalizedKey) &&
            cancellationOperationSnapshotJobs[normalizedKey] == null
    ) {
        requestCancellationOperationSnapshot(
            context = context,
            songKey = normalizedKey
        )
    }
    val snapshotJob = cancellationOperationSnapshotJobs[normalizedKey]
    val snapshotResult = snapshotJob?.let { job ->
        withTimeoutOrNull(DOWNLOAD_CANCEL_OPERATION_SNAPSHOT_TIMEOUT_MS) {
            try {
                Result.success(job.await())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }
    val snapshotIds = snapshotResult?.getOrNull().orEmpty()
    if (snapshotResult?.isSuccess == true) {
        cancellationOperationSnapshotResolvedKeys.add(normalizedKey)
    } else if (snapshotResult != null) {
        cancellationOperationSnapshotResolvedKeys.remove(normalizedKey)
        NPLogger.w(
            TAG,
            "取消 operation 快照未完成，继续保留旧凭据: " +
                "songKey=$normalizedKey"
        )
    }
    val existingIds = if (
        cancellationCleanupActiveSongKeys.contains(normalizedKey) ||
            isSongCancelled(normalizedKey) ||
            cancellationConvergenceJobs.containsKey(normalizedKey)
    ) {
        cancellationOperationIdsBySongKey[normalizedKey].orEmpty() + snapshotIds
    } else {
        snapshotIds
    }
    val persistedResult = withTimeoutOrNull(
        DOWNLOAD_CANCEL_OPERATION_SNAPSHOT_TIMEOUT_MS
    ) {
        try {
            Result.success(
                DownloadExecutionRoomStore.findOperationIdsForSong(
                    context = context.applicationContext,
                    songKey = normalizedKey,
                    createdAtMsAtMost = cancellationOperationSnapshotCutoffs[normalizedKey]
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
    val persistedIds = persistedResult?.getOrNull().orEmpty()
    if (persistedResult?.isSuccess == true) {
        if (hasCancellationSnapshotBoundary(normalizedKey)) {
            cancellationOperationSnapshotResolvedKeys.add(normalizedKey)
        }
    } else if (persistedResult != null) {
        cancellationOperationSnapshotResolvedKeys.remove(normalizedKey)
        NPLogger.w(
            TAG,
            "读取取消 operation 身份失败，保留宿主凭据等待重试: " +
                "songKey=$normalizedKey, error=${persistedResult.exceptionOrNull()?.message}"
        )
    } else if (hasCancellationSnapshotBoundary(normalizedKey)) {
        cancellationOperationSnapshotResolvedKeys.remove(normalizedKey)
        NPLogger.w(
            TAG,
            "读取取消 operation 身份超时，保留宿主凭据等待重试: " +
                "songKey=$normalizedKey"
        )
    }
    val previousIds = cancellationOperationIdsBySongKey[normalizedKey].orEmpty()
    val ids = buildSet {
        addAll(previousIds)
        addAll(existingIds)
        persistedIds.mapTo(this, String::trim)
        fallbackOperationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
    }
    if (ids.isNotEmpty() || previousIds.isEmpty()) {
        cancellationOperationIdsBySongKey[normalizedKey] = ids
    }
    return cancellationOperationIdsBySongKey[normalizedKey].orEmpty()
}

internal fun GlobalDownloadManager.cancellationOperationIdsForSong(songKey: String): Set<String> {
    return cancellationOperationIdsBySongKey[songKey].orEmpty()
}

internal fun GlobalDownloadManager.isCancellationSnapshotResolved(songKey: String): Boolean {
    return cancellationOperationSnapshotResolvedKeys.contains(songKey)
}

internal fun GlobalDownloadManager.clearCancellationTracking(
    songKey: String,
    operationIds: Set<String>? = null
) {
    val currentIds = cancellationOperationIdsBySongKey[songKey]
    val matches = when {
        operationIds == null -> true
        operationIds.isEmpty() -> currentIds.isNullOrEmpty()
        else -> currentIds == operationIds
    }
    if (matches) {
        cancellationCleanupActiveSongKeys.remove(songKey)
        cancellationOperationIdsBySongKey.remove(songKey)
        cancellationOperationSnapshotCutoffs.remove(songKey)
        cancellationOperationSnapshotResolvedKeys.remove(songKey)
        cancellationForceNewSongKeys.remove(songKey)
        cancellationOperationSnapshotJobs.remove(songKey)?.cancel()
    }
}

internal suspend fun GlobalDownloadManager.clearSongCancellationForFreshStart(
    context: Context,
    songKeys: Collection<String>
): Boolean {
    val keys = songKeys.filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) {
        return true
    }
    val cancellationContextKeys = keys.filterTo(linkedSetOf()) { songKey ->
        cancellationCleanupActiveSongKeys.contains(songKey) ||
            isSongCancelled(songKey) ||
            cancellationConvergenceJobs.containsKey(songKey) ||
            cancellationOperationIdsBySongKey[songKey]?.isNotEmpty() == true ||
            hasCancellationSnapshotBoundary(songKey)
    }
    keys.forEach { songKey ->
        if (songKey in cancellationContextKeys) {
            requestCancellationOperationSnapshot(
                context = context,
                songKey = songKey
            )
        }
    }
    val snapshotReadiness = keys.associateWith { songKey ->
        awaitCancellationOperationSnapshot(songKey = songKey)
    }
    val snapshotsReady = snapshotReadiness.values.all { it }
    if (!snapshotsReady) {
        // 快照超时不能吞掉用户的新意图。已知旧身份继续排除；
        // 没有身份时由队列存储按 stable key 原子收集旧行并强制生成新 operation
        cancellationContextKeys
            .filter { songKey -> snapshotReadiness[songKey] != true }
            .forEach { songKey ->
            val operationIds = cancellationOperationIdsForSong(songKey)
            cancellationCleanupActiveSongKeys.add(songKey)
            if (snapshotReadiness[songKey] != true ||
                operationIds.isEmpty() ||
                !hasCancellationSnapshotBoundary(songKey)
            ) {
                cancellationForceNewSongKeys.add(songKey)
            }
            scheduleCancellationConvergence(
                context = context,
                songKey = songKey,
                cancellationGeneration = null,
                operationIds = operationIds
            )
        }
        NPLogger.w(
            TAG,
            "旧下载 operation 身份快照未完成，保留替代请求并交由收敛: " +
                "songs=${keys.size}, timeoutMs=" +
                DOWNLOAD_CANCEL_OPERATION_SNAPSHOT_TIMEOUT_MS
        )
    }
    val cancellationOperationIdsByKey = keys.associateWith { songKey ->
        if (songKey in cancellationContextKeys &&
            (snapshotReadiness[songKey] == true ||
            isCancellationSnapshotResolved(songKey)
            )
        ) {
            captureCancellationOperationIds(
                context = context,
                songKey = songKey
            )
        } else {
            cancellationOperationIdsForSong(songKey)
        }
    }
    val trackedCancellationKeys = cancellationOperationIdsByKey
        .filterValues { operationIds -> operationIds.isNotEmpty() }
        .keys
    // 已捕获的旧 operation 必须留在取消收敛的栅栏内，新的用户意图使用新身份
    // 如果释放旧 core 行，它会与替代 operation 争抢同一首歌并再次留下孤儿任务
    trackedCancellationKeys.forEach { songKey ->
        cancellationForceNewSongKeys.add(songKey)
    }
    // 先把旧行推进到取消态，避免新请求在取消收敛开始前被旧 RUNNING
    // operation 拒绝。身份集合已经按取消时间截断，不会触碰替代请求
    val cancellationPersisted = try {
        cancellationOperationIdsByKey.values
            .flatten()
            .distinct()
            .forEach { operationId ->
                DownloadExecutionRoomStore.requestCancel(
                    context = context.applicationContext,
                    operationId = operationId
                )
            }
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "旧下载 operation 取消状态未能立即落盘，交由收敛重试: " +
                "songs=${keys.size}, error=${error.message}",
            error
        )
        false
    }
    if (!cancellationPersisted) {
        NPLogger.d(TAG, "取消状态立即落盘失败，仍保留旧 operation 身份")
    }
    // 旧 operation 仍可能在传输线程中退出。先固定它的身份并保留收敛
    // job，新的请求只能使用新的 operation，不能取消或复用旧行
    cancellationOperationIdsByKey.forEach { (songKey, operationIds) ->
        if (shouldScheduleCancellationConvergence(
                operationIds = operationIds,
                snapshotBoundary = hasCancellationSnapshotBoundary(songKey)
            )
        ) {
            cancellationCleanupActiveSongKeys.add(songKey)
            scheduleCancellationConvergence(
                context = context,
                songKey = songKey,
                cancellationGeneration = null,
                operationIds = operationIds
            )
        }
    }
    // 先把进程退出留下的停止行恢复成可重试，再清除停止标记。
    // 如果顺序反过来，恢复查询看不到这些行，旧 RUNNING operation 会一直挡住泵
    val resumableKeys = keys - trackedCancellationKeys
    val prepared = runCatching {
        DownloadExecutionRoomStore.prepareExplicitResumesForStableKeys(
            context = context.applicationContext,
            stableKeys = resumableKeys
        )
        true
    }.onFailure { error ->
        NPLogger.w(TAG, "恢复用户停止的 operation 失败: ${error.message}")
    }.getOrDefault(false)
    val canUseForcedReplacement = keys.all { songKey ->
        cancellationOperationIdsByKey[songKey].orEmpty().isNotEmpty() ||
            cancellationForceNewSongKeys.contains(songKey)
    }
    if (!prepared && !canUseForcedReplacement) return false
    val cleared = runCatching {
        DownloadExecutionRoomStore.clearUserStopForFreshStart(
            context = context.applicationContext,
            stableKeys = resumableKeys
        )
        true
    }.onFailure { error ->
        NPLogger.w(TAG, "清除下载停止标记失败: ${error.message}")
    }.getOrDefault(false)
    if (!cleared && !canUseForcedReplacement) {
        return false
    }
    // 取消 operation 的删除必须由收敛路径在文件清理确认后完成，
    // 这里不能提前删除仍可能被旧宿主使用的凭据
    cancelledSongKeys.removeAll(keys)
    return true
}

internal suspend fun GlobalDownloadManager.awaitCancellationOperationSnapshot(
    songKey: String
): Boolean {
    val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return true
    if (!hasCancellationSnapshotBoundary(normalizedKey)) return true
    val snapshotJob = cancellationOperationSnapshotJobs[normalizedKey]
        ?: return isCancellationSnapshotResolved(normalizedKey)
    snapshotJob.start()
    val completed = withTimeoutOrNull(DOWNLOAD_CANCEL_OPERATION_SNAPSHOT_TIMEOUT_MS) {
        try {
            snapshotJob.await()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    } == true
    if (!completed || !isCancellationSnapshotResolved(normalizedKey)) {
        NPLogger.w(
            TAG,
            "取消 operation 身份快照超时，保留旧清理凭据: " +
                "songKey=$normalizedKey, timeoutMs=" +
            DOWNLOAD_CANCEL_OPERATION_SNAPSHOT_TIMEOUT_MS
        )
    }
    return completed && isCancellationSnapshotResolved(normalizedKey)
}

internal fun GlobalDownloadManager.markSongCancelled(songKey: String) {
    cancelledSongKeys.add(songKey)
}

internal fun GlobalDownloadManager.requestOperationCancellation(songKeys: Collection<String>) {
    val keys = songKeys.filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) {
        return
    }
    runCatching {
        val context = AppContainer.applicationContext
        val operationStore = DownloadExecutionOperationStore()
        keys.forEach { songKey ->
            val trackedOperationIds = cancellationOperationIdsForSong(songKey)
            val operationIds = if (trackedOperationIds.isNotEmpty()) {
                trackedOperationIds
            } else if (!hasCancellationSnapshotBoundary(songKey)) {
                operationStore.findOperationIdsForSong(context, songKey).also { ids ->
                    if (ids.isNotEmpty()) {
                        cancellationOperationIdsBySongKey[songKey] = ids.toSet()
                    }
                }
            } else {
                // 快照尚未完成时不能按 stableKey 查询，否则可能把刚创建
                // 的替代 operation 标成旧任务并一并取消
                emptySet()
            }
            operationIds
                .forEach { operationId ->
                    operationStore.requestCancel(context, operationId)
                }
        }
    }.onFailure { error ->
        NPLogger.w(TAG, "记录下载 operation 取消状态失败: count=${keys.size}, ${error.message}")
    }
}

internal fun GlobalDownloadManager.hasCancellationSnapshotBoundary(songKey: String): Boolean {
    return cancellationOperationSnapshotCutoffs.containsKey(songKey) ||
        cancellationOperationSnapshotJobs.containsKey(songKey)
}
