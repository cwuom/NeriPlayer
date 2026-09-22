package moe.ouom.neriplayer.core.download.manager.batch

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.PreExistingDownloadedAudioAction
import moe.ouom.neriplayer.core.download.isUnfinalizedDownloadedMetadata
import moe.ouom.neriplayer.core.download.resolvePreExistingDownloadedAudioAction
import moe.ouom.neriplayer.core.download.shouldDeferQueuedDownloadStartForNetwork
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutation
import moe.ouom.neriplayer.core.download.manager.admission.admitDownloadMutationForStableKeys
import moe.ouom.neriplayer.core.download.manager.admission.awaitDownloadAdmissionTicketForStableKeys
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrent
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadAdmissionTicketCurrentForStableKeys
import moe.ouom.neriplayer.core.download.manager.admission.isDownloadClearFenceActive
import moe.ouom.neriplayer.core.download.manager.admission.openDownloadAdmissionTicketForStableKeysOrNull
import moe.ouom.neriplayer.core.download.manager.admission.promoteUserInitiatedInFlightRequests
import moe.ouom.neriplayer.core.download.manager.admission.resolveOperationRequestsForBatchBinding
import moe.ouom.neriplayer.core.download.manager.admission.stageAndPromotePendingDownloadQueue
import moe.ouom.neriplayer.core.download.manager.catalog.awaitDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.catalog.deferDownloadForDeleteCleanup
import moe.ouom.neriplayer.core.download.manager.catalog.releaseDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.manager.catalog.scheduleDeleteCleanupRetry
import moe.ouom.neriplayer.core.download.manager.commit.finalizeCompletedDownload
import moe.ouom.neriplayer.core.download.manager.runtime.deferQueuedDownloadStartForNetworkPolicyIfNeeded
import moe.ouom.neriplayer.core.download.manager.runtime.findExistingDownloadedAudio
import moe.ouom.neriplayer.core.download.manager.runtime.findFastCachedDownloadedSong
import moe.ouom.neriplayer.core.download.manager.runtime.publishOptimisticDownloadedSongs
import moe.ouom.neriplayer.core.download.manager.runtime.settleAlreadyDownloadedOperation
import moe.ouom.neriplayer.core.download.manager.runtime.settleAndRemoveRecoveredTask
import moe.ouom.neriplayer.core.download.manager.runtime.shouldSkipDownload
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.model.BatchDownloadTerminalState
import moe.ouom.neriplayer.core.download.model.DownloadStatus
import moe.ouom.neriplayer.core.download.model.QueuedDownloadRequest
import moe.ouom.neriplayer.core.download.model.selectBatchDownloadCandidates
import moe.ouom.neriplayer.core.download.policy.isDownloadFinalizationDurablySettled
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.BatchDownloadSession
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.PreparedBatchArtifact
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.BatchOperationScheduleMetadata
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.download.artifact.ManagedDownloadArtifactClaim
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.clear.DownloadStorageMutationDeferredException
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceLookup
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.currentDownloadParallelism
import moe.ouom.neriplayer.core.player.download.resolveDownloadDispatchWindow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull


internal fun GlobalDownloadManager.startBatchDownload(
    context: Context,
    songs: List<SongItem>,
    skipTrafficRiskPrompt: Boolean,
    cleanupBeforeStart: Boolean = true,
    deferForNetworkPolicy: Boolean = false,
    userInitiated: Boolean = true,
    requestedAdmissionTicket: Long? = null,
    awaitAdmissionWhenUnavailable: Boolean = true
): Job? {
    if (songs.isEmpty()) return null

    val startupStartedAtNs = System.nanoTime()
    val appContext = context.applicationContext
    var requestedSongs = songs.distinctBy(SongItem::stableKey)
    if (requestedSongs.isEmpty()) {
        return null
    }
    var requestedSongKeys = requestedSongs.mapTo(linkedSetOf()) { song ->
        song.stableKey()
    }
    // 先分配 UI id，但不发布卡片。批次和成员必须先在同一条 Room 事务中落盘，避免出现只有内存总数的短暂批次
    val batchPresentationId = batchDownloadPresentationIdGenerator.incrementAndGet()
    fun logStartupPhase(phase: String, count: Int) {
        val elapsedMs = ((System.nanoTime() - startupStartedAtNs) / 1_000_000L).coerceAtLeast(0L)
        NPLogger.d(
            TAG,
            "批量下载阶段: presentationId=$batchPresentationId, phase=$phase, " +
                "elapsedMs=$elapsedMs, count=$count"
        )
    }
    logStartupPhase("requested", requestedSongs.size)
    // 在等待其它批次准备前固定票据，避免旧请求跨过一次清空后获取新代次
    val capturedAdmissionTicket = requestedAdmissionTicket
        ?: openDownloadAdmissionTicketForStableKeysOrNull(
            context = appContext,
            stableKeys = requestedSongKeys
        )
    val startupJob = scope.launchBatchDownloadStartup(
        beforeStartup = admission@{
            val clearBlockedSongs = requestedSongs.filter { song ->
                isDownloadClearFenceActive(appContext, stableKey = song.stableKey())
            }
            val deletionSettled = if (clearBlockedSongs.isEmpty()) {
                awaitDownloadedSongDeletion(requestedSongKeys)
            } else {
                true
            }
            if (!deletionSettled) {
                val deferred = deferDownloadForDeleteCleanup(
                    context = appContext,
                    songs = requestedSongs,
                    userInitiated = userInitiated,
                    admissionTicket = capturedAdmissionTicket,
                    batchPresentationId = batchPresentationId
                )
                if (!deferred) {
                    if (batchPresentationId != 0L) {
                        clearBatchDownloadPresentation(batchPresentationId)
                    }
                    NPLogger.d(
                        TAG,
                        "删除清理等待意图已过期，放弃批量下载请求: " +
                            "requested=${requestedSongs.size}"
                    )
                } else {
                    scheduleDeleteCleanupRetry(
                        context = appContext,
                        songKeys = requestedSongKeys,
                        admissionTicket = capturedAdmissionTicket
                    )
                }
                return@admission null
            }
            capturedAdmissionTicket
                ?: if (awaitAdmissionWhenUnavailable) {
                    awaitDownloadAdmissionTicketForStableKeys(
                        context = appContext,
                        stableKeys = requestedSongKeys
                    )
                } else {
                    NPLogger.d(
                        TAG,
                        "清空期间跳过无票据批量下载请求: requested=${requestedSongs.size}"
                    )
                    return@admission null
                }
        }
    ) startup@{ admissionTicket ->
        if (!isDownloadAdmissionTicketCurrentForStableKeys(
                context = appContext,
                admissionTicket = admissionTicket,
                stableKeys = requestedSongKeys
            )
        ) {
            return@startup
        }
        val initialDownloadLibrarySnapshot = ManagedDownloadStorage
            .cachedDownloadLibrarySnapshot(
                context = appContext,
                restorePersisted = true
            )
        val batchCompletionCatalogIndex = loadBatchCompletionCatalogIndex(appContext)
        val preflightProbe = BatchDownloadPreflightProbe { reference ->
            ManagedDownloadReferenceLookup.inspectWithSize(appContext, reference)
        }
        var initiallyCompletedSongKeys = findStrictlyCompletedBatchSongKeys(
            songs = requestedSongs,
            snapshot = initialDownloadLibrarySnapshot
        )
        // 清单索引通常先于 SAF 全量快照恢复。对索引命中的正式引用做有界
        // Present 校验，避免第二次全选在 snapshot=false 时重新创建整批 operation
        // （完整目录对账仍在后台继续，未知条目绝不乐观跳过）
        initiallyCompletedSongKeys = initiallyCompletedSongKeys +
            findFastCompletedBatchSongKeys(
                context = appContext,
                songs = requestedSongs,
                alreadyCompletedSongKeys = initiallyCompletedSongKeys,
                catalogIndex = batchCompletionCatalogIndex,
                preflightProbe = preflightProbe
            )
        var preparedSnapshot: DownloadBatchSnapshotSelection? = null
        var durableBatchIdentity: DownloadExecutionRoomStore.DownloadBatchIdentity? = null
        val batchCreated = admitDownloadMutationForStableKeys(
            context = appContext,
            admissionTicket = admissionTicket,
            stableKeys = requestedSongKeys
        ) snapshotAdmission@{ admittedSongKeys ->
            val admittedSongs = requestedSongs.filter { song ->
                song.stableKey() in admittedSongKeys &&
                    song.stableKey() !in initiallyCompletedSongKeys
            }
            // 显式恢复可能释放旧 owner，必须在新批次归属查询前完成
            if (userInitiated && !clearSongCancellationForFreshStart(
                    context = appContext,
                    songKeys = admittedSongs.map(SongItem::stableKey)
                )
            ) {
                return@snapshotAdmission
            }
            preparedSnapshot = ensureDurableBatchSnapshot(
                context = appContext,
                presentationId = batchPresentationId,
                songs = requestedSongs,
                initiallyCompletedSongKeys = initiallyCompletedSongKeys,
                excludedOperationIds = requestedSongKeys.flatMap { key ->
                    cancellationOperationIdsForSong(key)
                }.toSet()
            )
            val prepared = preparedSnapshot ?: return@snapshotAdmission
            durableBatchIdentity = prepared.identity
            requestedSongs = prepared.songs
            requestedSongKeys = requestedSongs.mapTo(linkedSetOf(), SongItem::stableKey)
            initiallyCompletedSongKeys = initiallyCompletedSongKeys.intersect(requestedSongKeys)
            if (durableBatchIdentity != null) {
                beginBatchDownloadPresentation(
                    songs = requestedSongs,
                    batchId = batchPresentationId
                )
                seedInitialBatchDownloadPresentation(
                    context = appContext,
                    batchId = batchPresentationId,
                    songs = requestedSongs,
                    snapshot = initialDownloadLibrarySnapshot,
                    knownCompletedSongKeys = initiallyCompletedSongKeys
                )
            }
        }
        if (!batchCreated || preparedSnapshot == null) {
            NPLogger.w(
                TAG,
                "持久批次创建失败，不发布只有内存的批次卡: requested=${requestedSongs.size}"
            )
            return@startup
        }
        val reusedRequests = checkNotNull(preparedSnapshot).reusedRequests
        resumeOwnedBatchDownloadRequests(
            context = appContext,
            requests = reusedRequests,
            admissionTicket = admissionTicket,
            userInitiated = userInitiated
        )
        if (requestedSongs.isEmpty()) {
            logStartupPhase("preflight_done", 0)
            logStartupPhase("durable_queue_ready", reusedRequests.size)
            return@startup
        }
        var existingRequestsToRecover = emptyList<DownloadExecutionRequest>()
        val admitted = admitDownloadMutationForStableKeys(
            context = appContext,
            admissionTicket = admissionTicket,
            stableKeys = requestedSongKeys
        ) admission@{ admittedSongKeys ->
            val admittedSongs = requestedSongs.filter { song ->
                song.stableKey() in admittedSongKeys &&
                    song.stableKey() !in initiallyCompletedSongKeys
            }
            val rejectedSongKeys = requestedSongKeys - admittedSongKeys
            if (rejectedSongKeys.isNotEmpty()) {
                cancelBatchDownloadPresentationMembers(
                    batchId = batchPresentationId,
                    songKeys = rejectedSongKeys,
                    identity = checkNotNull(durableBatchIdentity)
                )
            }
            val initiallyCompletedAdmittedKeys = admittedSongKeys
                .intersect(initiallyCompletedSongKeys)
            if (initiallyCompletedAdmittedKeys.isNotEmpty()) {
                // 旧 operation 可能仍留在 QUEUED/RETRYABLE/RUNNING；已确认
                // 音频的批次成员先固定为完成，再让 operation 自身做幂等终态收口
                DownloadExecutionRoomStore.markInitialBatchMembersCompleted(
                    context = appContext,
                    identity = checkNotNull(durableBatchIdentity),
                    stableKeys = initiallyCompletedAdmittedKeys
                )
                val oldRequests = DownloadExecutionRoomStore
                    .findReadableOperationsBySongKeys(
                        context = appContext,
                        songKeys = initiallyCompletedAdmittedKeys,
                        states = DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_STATES +
                            listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
                        excludeUserStoppedOperations = true
                    )
                oldRequests.values.forEach { request ->
                    val settled = settleAlreadyDownloadedOperation(
                        context = appContext,
                        song = request.song,
                        operationId = request.operationId,
                        expectedAttemptId = request.attemptId,
                        reason = "BATCH_PREFLIGHT_ALREADY_PRESENT"
                    )
                    if (settled) {
                        removeDownloadTask(
                            songKey = request.song.stableKey(),
                            expectedAttemptId = request.attemptId
                        )
                        forgetPendingDownloadQueueEntriesForOperation(
                            context = appContext,
                            songKey = request.song.stableKey(),
                            operationId = request.operationId
                        )
                    }
                }
            }
            if (admittedSongs.isEmpty()) {
                logStartupPhase("preflight_done", initiallyCompletedSongKeys.size)
                scheduleCompletedBatchDownloadPresentationRemoval(batchPresentationId)
                NPLogger.d(
                    TAG,
                    "批量下载选择全部命中已完成音频，跳过建队: " +
                        "requested=${requestedSongs.size}, " +
                        "completed=${initiallyCompletedAdmittedKeys.size}"
                )
                return@admission
            }
            val inFlightOperationsBySongKey =
                DownloadExecutionRoomStore.findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = admittedSongs.map(SongItem::stableKey),
                    states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                    excludeUserStoppedOperations = true,
                    excludedOperationIds = admittedSongs
                        .flatMap { song ->
                            cancellationOperationIdsForSong(song.stableKey())
                        }
                        .toSet()
                )
            val inFlightOperationRequests = promoteUserInitiatedInFlightRequests(
                context = appContext,
                requests = admittedSongs.mapNotNull { song ->
                    val songKey = song.stableKey()
                    inFlightOperationsBySongKey[songKey]
                        ?.takeIf { request -> request.song.stableKey() == songKey }
                },
                userInitiated = userInitiated
            )
            val inFlightOperationSongKeys = inFlightOperationRequests
                .mapTo(linkedSetOf()) { request -> request.song.stableKey() }
            existingRequestsToRecover = inFlightOperationRequests.filter { request ->
                !DownloadExecutionHosts.default.isExecuting(request.operationId)
            }
            DownloadExecutionRoomStore.attachBatchIdentity(
                context = appContext,
                identity = checkNotNull(durableBatchIdentity),
                requests = inFlightOperationRequests
            )
            bindBatchDownloadPresentationAttempts(
                batchId = batchPresentationId,
                attemptIdsBySongKey = taskStore.currentTasks()
                    .asSequence()
                    .filter { task -> task.song.stableKey() in admittedSongKeys }
                    .associate { task -> task.song.stableKey() to task.attemptId },
                operationIdsBySongKey = inFlightOperationRequests.associate { request ->
                    request.song.stableKey() to request.operationId
                }
            )
            val candidateSongs = selectBatchDownloadCandidates(
                songs = admittedSongs,
                inFlightSongKeys = inFlightOperationSongKeys
            )
            if (candidateSongs.isEmpty()) {
                NPLogger.d(
                    TAG,
                    "批量下载请求已有持久化运行 operation，准备恢复交接: " +
                        "requested=${requestedSongs.size}, " +
                        "recoveryCandidates=${existingRequestsToRecover.size}"
                )
                if (inFlightOperationSongKeys.isEmpty()) {
                    clearBatchDownloadPresentation(batchPresentationId)
                }
                return@admission
            }
            val candidateSongKeys = candidateSongs.mapTo(linkedSetOf()) { song ->
                song.stableKey()
            }
            if (!clearSongCancellationForFreshStart(appContext, candidateSongKeys)) {
                NPLogger.w(
                    TAG,
                    "批量下载暂缓，旧取消 operation 快照尚未完成: " +
                        "songs=${candidateSongKeys.size}"
                )
                return@admission
            }
            // 清除旧停止栅栏可能刚刚释放一个 core operation。初始查询发生在
            // 清除之前，必须再次读取，否则该 operation 不会进入恢复列表，也不会
            // 被新的等待队列接管，最终会在下载管理中留下一个永远不动的任务
            val postClearInFlightOperationsBySongKey =
                DownloadExecutionRoomStore.findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = candidateSongKeys,
                    states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                    excludeUserStoppedOperations = true,
                    excludedOperationIds = candidateSongs
                        .flatMap { song ->
                            cancellationOperationIdsForSong(song.stableKey())
                        }
                        .toSet()
                )
            val postClearInFlightOperationRequests =
                promoteUserInitiatedInFlightRequests(
                    context = appContext,
                    requests = candidateSongs.mapNotNull { song ->
                        val songKey = song.stableKey()
                        postClearInFlightOperationsBySongKey[songKey]
                            ?.takeIf { request -> request.song.stableKey() == songKey }
                    },
                    userInitiated = userInitiated
                )
            DownloadExecutionRoomStore.attachBatchIdentity(
                context = appContext,
                identity = checkNotNull(durableBatchIdentity),
                requests = postClearInFlightOperationRequests
            )
            val postClearInFlightSongKeys = postClearInFlightOperationRequests
                .mapTo(linkedSetOf()) { request -> request.song.stableKey() }
            existingRequestsToRecover = (
                existingRequestsToRecover +
                    postClearInFlightOperationRequests.filter { request ->
                        !DownloadExecutionHosts.default.isExecuting(request.operationId)
                    }
                ).distinctBy(DownloadExecutionRequest::operationId)
            val stageCandidateSongs = candidateSongs.filterNot { song ->
                song.stableKey() in postClearInFlightSongKeys
            }
            if (stageCandidateSongs.isEmpty()) {
                NPLogger.d(
                    TAG,
                    "批量下载清除停止栅栏后发现可恢复 operation，跳过重复建队: " +
                        "recovery=${postClearInFlightOperationRequests.size}"
                )
                return@admission
            }
            // 先用缓存快照做快速对账，不让冷 SAF 全量扫描阻塞首页传输。完整快照由后台对账补齐，未知成员不能被当成已完成
            val existingOperationRequestsBySongKey = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = stageCandidateSongs.map(SongItem::stableKey),
                    // 等待目录变更的 operation 也必须保留给下面的 staging 路径
                    // 复用并提升，不能被预筛提前移出，否则会留下无人接管的等待 operation
                    states = DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_STATES +
                        listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
                    excludeUserStoppedOperations = true
                )
            val existingOperationHeaders = DownloadExecutionRoomStore.readOperationHeaders(
                context = appContext,
                operationIds = existingOperationRequestsBySongKey.values.map(
                    DownloadExecutionRequest::operationId
                )
            )
            val existingOperationSongKeys = existingOperationRequestsBySongKey
                .filter { (_, request) ->
                    val state = existingOperationHeaders[request.operationId]?.state
                    state !in DownloadExecutionRoomStore.DIRECT_CACHED_COMPLETION_SOURCE_STATES
                }
                .keys
            if (initialDownloadLibrarySnapshot == null ||
                !initialDownloadLibrarySnapshot.rootEntriesComplete
            ) {
                scheduleCatalogReconcile(appContext, forceRefresh = true)
                // 不阻塞首个传输；完整快照在后台有界刷新并与 fast index 合并
                scope.launch {
                    buildBatchDownloadLibrarySnapshot(appContext)
                }
            }
            seedInitialBatchDownloadPresentation(
                context = appContext,
                batchId = batchPresentationId,
                songs = requestedSongs,
                snapshot = initialDownloadLibrarySnapshot,
                knownCompletedSongKeys = initiallyCompletedSongKeys
            )
            val fastCompletedSongKeys = findFastCompletedBatchSongKeys(
                context = appContext,
                songs = stageCandidateSongs,
                alreadyCompletedSongKeys = initiallyCompletedSongKeys,
                catalogIndex = batchCompletionCatalogIndex,
                preflightProbe = preflightProbe
            )
            initiallyCompletedSongKeys = initiallyCompletedSongKeys + fastCompletedSongKeys
            logStartupPhase("preflight_done", initiallyCompletedSongKeys.size)
            seedInitialBatchDownloadPresentation(
                context = appContext,
                batchId = batchPresentationId,
                songs = requestedSongs,
                snapshot = initialDownloadLibrarySnapshot,
                knownCompletedSongKeys = initiallyCompletedSongKeys
            )
            val preflightCompletedSongKeys = (
                findStrictlyCompletedBatchSongKeys(
                    songs = stageCandidateSongs,
                    snapshot = initialDownloadLibrarySnapshot
                ) + fastCompletedSongKeys
                ).filterNot { songKey -> songKey in existingOperationSongKeys }
            if (preflightCompletedSongKeys.isNotEmpty()) {
                DownloadExecutionRoomStore.markInitialBatchMembersCompleted(
                    context = appContext,
                    identity = checkNotNull(durableBatchIdentity),
                    stableKeys = preflightCompletedSongKeys
                )
                existingOperationRequestsBySongKey
                    .filterKeys(preflightCompletedSongKeys::contains)
                    .values
                    .forEach { request ->
                        val settled = settleAlreadyDownloadedOperation(
                            context = appContext,
                            song = request.song,
                            operationId = request.operationId,
                            expectedAttemptId = request.attemptId,
                            reason = "BATCH_PREFLIGHT_ALREADY_PRESENT"
                        )
                        if (settled) {
                            removeDownloadTask(
                                songKey = request.song.stableKey(),
                                expectedAttemptId = request.attemptId
                            )
                            forgetPendingDownloadQueueEntriesForOperation(
                                context = appContext,
                                songKey = request.song.stableKey(),
                                operationId = request.operationId
                            )
                        }
                    }
            }
            val songsToStage = stageCandidateSongs.filterNot { song ->
                song.stableKey() in preflightCompletedSongKeys
            }
            if (preflightCompletedSongKeys.isNotEmpty()) {
                NPLogger.d(
                    TAG,
                    "批量下载启动前快速结算已完成歌曲: " +
                        "completed=${preflightCompletedSongKeys.size}, " +
                        "remaining=${songsToStage.size}, total=${stageCandidateSongs.size}"
                )
            }
            if (songsToStage.isEmpty()) {
                scheduleCompletedBatchDownloadPresentationRemoval(batchPresentationId)
                return@admission
            }
            if (
                maybeRequestTrafficRiskDownloadConfirmation(
                    context = appContext,
                    songs = songsToStage,
                    isBatch = true,
                    skipTrafficRiskPrompt = skipTrafficRiskPrompt
                )
            ) {
                clearBatchDownloadPresentation(batchPresentationId)
                return@admission
            }
            val canStartFirstDurablePageImmediately = cleanupBeforeStart &&
                !shouldDeferQueuedDownloadStartForNetwork(
                    networkType = appContext.currentDownloadNetworkTypeOrNull(),
                    mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed,
                    deferForNetworkPolicy = deferForNetworkPolicy
                )
            var firstDurablePagePumpScheduled = false
            val stagedQueue = stageAndPromotePendingDownloadQueue(
                context = appContext,
                songs = songsToStage,
                userInitiated = userInitiated,
                batchIdentity = durableBatchIdentity,
                // 首页 operation 已和持久批次绑定，立即唤醒共享泵
                // 后续页按原队列顺序提升，不再阻塞第一首开始传输
                onPageReady = { _, page ->
                    if (
                        !firstDurablePagePumpScheduled &&
                            canStartFirstDurablePageImmediately &&
                            page.operationIds.isNotEmpty()
                    ) {
                        firstDurablePagePumpScheduled = wakeDownloadExecutionPump(
                            context = appContext,
                            reason = "batch_first_durable_page"
                        )
                        if (firstDurablePagePumpScheduled) {
                            logStartupPhase("first_durable_page_ready", page.operationIds.size)
                        }
                    }
                }
            )
            if (stagedQueue.skippedSongKeys.isNotEmpty()) {
                NPLogger.w(
                    TAG,
                    "批量下载暂未能提升部分持久化意图，保留批次成员等待恢复: " +
                        "skipped=${stagedQueue.skippedSongKeys.size}, " +
                        "requested=${songsToStage.size}"
                )
            }
            val operationIds = stagedQueue.operationIds
            val operationIdsBySongKey = stagedQueue.operationIdsBySongKey
            val batchIdentity = checkNotNull(durableBatchIdentity)
            var operationHeaders = DownloadExecutionRoomStore.readOperationHeaders(
                context = appContext,
                operationIds = operationIds
            )
            val operationIdsMissingBatchIdentity = operationIds.filter { operationId ->
                val header = operationHeaders[operationId]
                header?.batchId != batchIdentity.batchId ||
                    header.batchGeneration != batchIdentity.generation
            }
            if (operationIdsMissingBatchIdentity.isNotEmpty()) {
                val operationRequestsForBinding = resolveOperationRequestsForBatchBinding(
                    context = appContext,
                    operationIds = operationIdsMissingBatchIdentity,
                    knownRequests = stagedQueue.operationRequestsBySongKey.values
                )
                DownloadExecutionRoomStore.attachBatchIdentity(
                    context = appContext,
                    identity = batchIdentity,
                    requests = operationRequestsForBinding
                )
                operationHeaders = DownloadExecutionRoomStore.readOperationHeaders(
                    context = appContext,
                    operationIds = operationIds
                )
            }
            logStartupPhase("durable_queue_ready", operationIds.size)
            val handedOffSongKeys = operationIdsBySongKey
                .filterValues { operationId ->
                    operationHeaders[operationId]?.state in
                        DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES
                }
                .keys
            if (handedOffSongKeys.isNotEmpty()) {
                bindBatchDownloadPresentationAttempts(
                    batchId = batchPresentationId,
                    attemptIdsBySongKey = taskStore.currentTasks()
                        .asSequence()
                        .filter { task -> task.song.stableKey() in handedOffSongKeys }
                        .associate { task -> task.song.stableKey() to task.attemptId },
                    operationIdsBySongKey = operationIdsBySongKey
                        .filterKeys(handedOffSongKeys::contains)
                )
            }
            val schedulableSongs = songsToStage.filter { song ->
                val operationId = operationIdsBySongKey[song.stableKey()]
                operationId != null &&
                    operationHeaders[operationId]?.state in
                    DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
            }
            if (schedulableSongs.isEmpty()) {
                NPLogger.d(
                    TAG,
                    "批量下载 operation 已被其他执行接管: " +
                        "requested=${songsToStage.size}"
                )
                if (
                    inFlightOperationSongKeys.isEmpty() &&
                        postClearInFlightSongKeys.isEmpty() &&
                        handedOffSongKeys.isEmpty() &&
                        stagedQueue.skippedSongKeys.isEmpty()
                ) {
                    clearBatchDownloadPresentation(batchPresentationId)
                }
                return@admission
            }
            val requestGeneration = beginDownloadRequestGeneration(schedulableSongs)
            startBatchDownloadConfirmed(
                context = appContext,
                songs = schedulableSongs,
                cleanupBeforeStart = cleanupBeforeStart,
                requestGeneration = requestGeneration,
                admissionTicket = admissionTicket,
                deferForNetworkPolicy = deferForNetworkPolicy,
                userInitiated = userInitiated,
                operationIdsBySongKey = operationIdsBySongKey,
                operationRequestsBySongKey = stagedQueue.operationRequestsBySongKey,
                batchPresentationId = batchPresentationId,
                durableBatchIdentity = durableBatchIdentity,
                initialDownloadLibrarySnapshot = initialDownloadLibrarySnapshot
            )
        }
        if (admitted) {
            recoverInFlightDownloadOperations(
                context = appContext,
                requests = existingRequestsToRecover,
                admissionTicket = admissionTicket
            )
        }
        if (!admitted) {
            NPLogger.d(
                TAG,
                "清空任务已使批量下载请求过期: requested=${requestedSongs.size}"
            )
            if (batchPresentationId != 0L) {
                clearBatchDownloadPresentation(batchPresentationId)
            }
        }
    }
    registerActiveBatchDownloadJob(startupJob)
    return startupJob
}

internal suspend fun GlobalDownloadManager.startBatchDownloadConfirmed(
    context: Context,
    songs: List<SongItem>,
    cleanupBeforeStart: Boolean,
    requestGeneration: Long,
    admissionTicket: Long,
    deferForNetworkPolicy: Boolean,
    operationIdsBySongKey: Map<String, String>,
    operationRequestsBySongKey: Map<String, DownloadExecutionRequest>,
    batchPresentationId: Long,
    durableBatchIdentity: DownloadExecutionRoomStore.DownloadBatchIdentity? = null,
    initialDownloadLibrarySnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null,
    userInitiated: Boolean = true
) {
    if (songs.isEmpty()) return

    runBatchDownloadSession(
        context = context.applicationContext,
        songs = songs,
        cleanupBeforeStart = cleanupBeforeStart,
        requestGeneration = requestGeneration,
        admissionTicket = admissionTicket,
        deferForNetworkPolicy = deferForNetworkPolicy,
        operationIdsBySongKey = operationIdsBySongKey,
        operationRequestsBySongKey = operationRequestsBySongKey,
        batchPresentationId = batchPresentationId,
        durableBatchIdentity = durableBatchIdentity,
        initialDownloadLibrarySnapshot = initialDownloadLibrarySnapshot,
        userInitiated = userInitiated
    )
}

internal suspend fun GlobalDownloadManager.runBatchDownloadSession(
    context: Context,
    songs: List<SongItem>,
    cleanupBeforeStart: Boolean,
    requestGeneration: Long,
    admissionTicket: Long,
    deferForNetworkPolicy: Boolean,
    operationIdsBySongKey: Map<String, String>,
    operationRequestsBySongKey: Map<String, DownloadExecutionRequest>,
    batchPresentationId: Long,
    durableBatchIdentity: DownloadExecutionRoomStore.DownloadBatchIdentity? = null,
    initialDownloadLibrarySnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?,
    userInitiated: Boolean
) {
    val requestedSongs = songs.distinctBy(SongItem::stableKey)
        .filter { song ->
            isDownloadRequestGenerationCurrent(song.stableKey(), requestGeneration)
        }
    if (requestedSongs.isEmpty()) {
        NPLogger.d(TAG, "忽略过期批量下载启动: generation=$requestGeneration")
        clearBatchDownloadPresentation(batchPresentationId)
        return
    }
    val session = BatchDownloadSession(
        context = context,
        requestedSongs = requestedSongs,
        sourceSongCount = songs.size,
        cleanupBeforeStart = cleanupBeforeStart,
        requestGeneration = requestGeneration,
        admissionTicket = admissionTicket,
        deferForNetworkPolicy = deferForNetworkPolicy,
        userInitiated = userInitiated,
        operationIdsBySongKey = operationIdsBySongKey,
        operationRequestsBySongKey = operationRequestsBySongKey,
        batchPresentationId = batchPresentationId,
        durableBatchIdentity = durableBatchIdentity
            ?: durableBatchIdentityByPresentationId[batchPresentationId],
        initialDownloadLibrarySnapshot = initialDownloadLibrarySnapshot
    )
    try {
        prepareAndScheduleBatchDownloadSession(session)
    } catch (_: CancellationException) {
        cancelPreparedBatchDownloadSession(session)
    } catch (error: Exception) {
        failPreparedBatchDownloadSession(session, error)
    }
}

internal suspend fun GlobalDownloadManager.prepareAndScheduleBatchDownloadSession(session: BatchDownloadSession) {
    if (!isDownloadAdmissionTicketCurrentForStableKeys(
            context = session.context,
            admissionTicket = session.admissionTicket,
            stableKeys = session.requestedSongs.map(SongItem::stableKey)
        )
    ) {
        NPLogger.d(
            TAG,
            "清空使批量下载准备过期: ticket=${session.admissionTicket}"
        )
        clearBatchDownloadPresentation(session.batchPresentationId)
        return
    }
    NPLogger.d(
        TAG,
        "批量下载启动: requested=${session.sourceSongCount}, " +
            "deduped=${session.requestedSongs.size}, " +
            "cleanupBeforeStart=${session.cleanupBeforeStart}, " +
            "persistedQueued=${ManagedDownloadStorage.countPendingQueuedDownloads(session.context)}"
    )
    val claimableSongs = findClaimableBatchDownloadSongs(session)
    if (claimableSongs.isEmpty()) {
        clearBatchDownloadPresentation(session.batchPresentationId)
        return
    }
    // 全部成员已持久化在 Room，但只有当前调度窗口需要预先创建 task/lease。
    // 否则 606/10000 首任务会在首个网络传输前做同量的 artifact 预处理，
    // 下载数量越多启动越慢；余量由共享泵按页接管
    val claimableWindow = claimableSongs.take(
        resolveDownloadDispatchWindow(currentDownloadParallelism(session.context))
    )
    val hasUnpreparedClaimableSongs = claimableSongs.size > claimableWindow.size
    if (!prepareBatchDownloadTasks(session, claimableWindow)) {
        clearBatchDownloadPresentation(session.batchPresentationId)
        return
    }
    val deferEarlyHandoffForNetwork = shouldDeferQueuedDownloadStartForNetwork(
        networkType = session.context.currentDownloadNetworkTypeOrNull(),
        mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed,
        deferForNetworkPolicy = session.deferForNetworkPolicy
    )
    val downloadLibrarySnapshot = session.initialDownloadLibrarySnapshot
        ?: ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
            context = session.context,
            restorePersisted = true
        )
    if (downloadLibrarySnapshot == null || !downloadLibrarySnapshot.rootEntriesComplete) {
        // 冷 SAF 目录快照缺失时保留未知状态，完整扫描只在后台对账，不阻塞首个 operation
        scheduleCatalogReconcile(session.context, forceRefresh = true)
    }
    seedInitialBatchDownloadPresentation(
        context = session.context,
        batchId = session.batchPresentationId,
        songs = session.requestedSongs,
        snapshot = downloadLibrarySnapshot
    )
    val songsToPrepare = claimableWindow.filter { song ->
        session.operationStatesBySongKey[song.stableKey()] in
            DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
    }
    if (songsToPrepare.isNotEmpty()) {
        val artifactsPrepared = admitDownloadMutationForStableKeys(
            context = session.context,
            admissionTicket = session.admissionTicket,
            stableKeys = songsToPrepare.map(SongItem::stableKey)
        ) {
            val claims = managedDownloadArtifactCoordinator.prepareMissingArtifacts(
                context = session.context,
                songs = songsToPrepare,
                leaseOwnerIds = songsToPrepare.associate { song ->
                    song.stableKey() to checkNotNull(
                        session.artifactLeaseIdsBySongKey[song.stableKey()]
                    )
                }
            )
            claims.forEach { (songKey, claim) ->
                session.artifactClaims[songKey] = claim
                claim.artifact.leaseId?.let { leaseId ->
                    managedDownloadArtifactLeases[songKey] = leaseId
                }
            }
        }
        if (!artifactsPrepared) {
            clearBatchDownloadPresentationWithoutOutstandingWork(session)
            return
        }
    }
    for (song in claimableWindow) {
        if (!isDownloadAdmissionTicketCurrent(
                context = session.context,
                admissionTicket = session.admissionTicket,
                stableKey = song.stableKey(),
                operationId = session.operationIdsBySongKey[song.stableKey()]
            )
        ) {
            NPLogger.d(
                TAG,
                "清空使批量下载准备提前结束: ticket=${session.admissionTicket}"
            )
            clearBatchDownloadPresentationWithoutOutstandingWork(session)
            return
        }
        if (!prepareBatchDownloadSong(session, song, downloadLibrarySnapshot)) {
            clearBatchDownloadPresentationWithoutOutstandingWork(session)
            return
        }
        if (!deferEarlyHandoffForNetwork && !session.shouldYieldToSharedPump) {
            // 每首准备完成即交给 dispatch window，不能反复扫描整个 pending 列表
            // 否则 847 首会退化成 O(n2)，越到队尾越慢
            val request = session.pendingSongs.lastOrNull { candidate ->
                candidate.song.stableKey() == song.stableKey() &&
                    candidate.song.stableKey() !in session.scheduledSongKeys
            }
            if (request != null) {
                val admitted = schedulePendingBatchDownload(
                    session = session,
                    request = request,
                    pendingAttemptIds = mapOf(request.song.stableKey() to request.attemptId)
                )
                if (!admitted) {
                    clearBatchDownloadPresentationWithoutOutstandingWork(session)
                    return
                }
            }
            if (session.shouldYieldToSharedPump) {
                // 宿主窗口已满时，剩余 operation 已经持久化在 Room，
                // 不必继续逐首 claim 才能让共享泵开始工作
                NPLogger.d(
                    TAG,
                    "批量准备达到共享泵背压窗口，提前交接剩余任务: " +
                        "prepared=${session.pendingSongs.size}, " +
                        "remaining=${claimableWindow.size - session.pendingSongs.size}"
                )
                break
            }
        }
    }
    if (hasUnpreparedClaimableSongs) {
        // 不把万首作业留在内存，首窗完成后让共享水泵继续按 64 条页读取
        session.shouldYieldToSharedPump = true
    }
    val settledAdmitted = if (session.settledSongKeys.isEmpty()) {
        true
    } else {
        admitDownloadMutationForStableKeys(
            context = session.context,
            admissionTicket = session.admissionTicket,
            stableKeys = session.settledSongKeys
        ) { _ ->
            val settledAttemptIds = session.settledAttemptIds.filterKeys { songKey ->
                songKey in session.settledSongKeys
            }
            removeDownloadTasks(settledAttemptIds)
            publishOptimisticDownloadedSongs(session.context, session.optimisticDownloadedSongs)
            forgetPendingDownloadQueueEntriesIfCurrent(
                context = session.context,
                songKeys = session.settledSongKeys,
                generation = session.requestGeneration
            )
        }
    }
    if (!settledAdmitted) {
        NPLogger.d(TAG, "清空使批量下载收尾发布过期")
        clearBatchDownloadPresentationWithoutOutstandingWork(session)
        return
    }
    if (session.pendingSongs.isEmpty()) {
        if (hasUnpreparedClaimableSongs) {
            ForegroundDownloadWorker.schedulePump(session.context)
            return
        }
        clearBatchDownloadPresentationWithoutOutstandingWork(session)
        NPLogger.d(
            TAG,
            "没有新的批量下载任务: requested=${session.requestedSongs.size}, " +
                "skippedLocalSongs=${session.skippedLocalSongs}, " +
                "settledSongKeys=${session.settledSongKeys.size}, " +
                "persistedQueued=${ManagedDownloadStorage.countPendingQueuedDownloads(session.context)}"
        )
        return
    }
    val pendingAttemptIds = session.pendingSongs.associate { request ->
        request.song.stableKey() to request.attemptId
    }
    var deferredSongKeys = emptySet<String>()
    val deferredAdmitted = admitDownloadMutationForStableKeys(
        context = session.context,
        admissionTicket = session.admissionTicket,
        stableKeys = session.pendingSongs.map { request -> request.song.stableKey() }
    ) { _ ->
        deferredSongKeys = deferQueuedDownloadStartForNetworkPolicyIfNeeded(
            context = session.context,
            songs = session.pendingSongs.map(QueuedDownloadRequest::song),
            attemptIdsBySongKey = pendingAttemptIds,
            requestGeneration = session.requestGeneration,
            reason = "batch_start",
            deferForNetworkPolicy = session.deferForNetworkPolicy
        )
    }
    if (!deferredAdmitted) {
        NPLogger.d(TAG, "清空使批量下载网络策略收尾过期")
        clearBatchDownloadPresentationWithoutOutstandingWork(session)
        return
    }
    val schedulableRequests = session.pendingSongs.filterNot { request ->
        request.song.stableKey() in deferredSongKeys
    }
    if (schedulableRequests.isEmpty()) {
        return
    }
    if (session.shouldYieldToSharedPump) {
        val pumpScheduled = ForegroundDownloadWorker.schedulePump(session.context)
        if (!pumpScheduled) {
            NPLogger.w(
                TAG,
                "批量下载共享泵调度失败，保留 Room 队列等待恢复: " +
                    "pendingSongs=${schedulableRequests.size}"
            )
        }
        NPLogger.d(
            TAG,
            "批量下载已切换共享泵接管: " +
                "pendingSongs=${schedulableRequests.size}, " +
                "preparedQueuedSongs=${session.preparedQueuedSongs}"
        )
        return
    }
    NPLogger.d(
        TAG,
        "批量下载正式开始: pendingSongs=${schedulableRequests.size}, " +
            "waitingForWifi=${deferredSongKeys.size}, " +
            "preparedQueuedSongs=${session.preparedQueuedSongs}, " +
            "settledSongKeys=${session.settledSongKeys.size}"
    )
    schedulePendingBatchDownloads(
        session = session,
        pendingAttemptIds = pendingAttemptIds,
        requests = schedulableRequests
    )
}

internal suspend fun GlobalDownloadManager.prepareBatchDownloadTasks(
    session: BatchDownloadSession,
    songs: List<SongItem>
): Boolean {
    var preparedAttemptIds = emptyMap<String, Long>()
    val admitted = admitDownloadMutationForStableKeys(
        context = session.context,
        admissionTicket = session.admissionTicket,
        stableKeys = songs.map(SongItem::stableKey)
    ) batchTaskAdmission@{ _ ->
        val operationIds = songs.mapNotNull { song ->
            session.operationIdsBySongKey[song.stableKey()]
        }
        val operationHeaders = DownloadExecutionRoomStore.readOperationHeaders(
            context = session.context,
            operationIds = operationIds
        )
        val directRequestsByOperationId = session.operationRequestsBySongKey.values
            .associateBy(DownloadExecutionRequest::operationId)
        val fallbackMetadata = DownloadExecutionRoomStore.readOperationRequestMetadata(
            context = session.context,
            operationIds = operationIds.filterNot { operationId ->
                operationId in directRequestsByOperationId
            }
        )
        val durableAttemptIds = linkedMapOf<String, Long>()
        val taskSongs = songs.filter songFilter@{ song ->
            val songKey = song.stableKey()
            val operationId = session.operationIdsBySongKey[songKey]
                ?: return@songFilter false
            if (
                !isDownloadRequestGenerationCurrent(songKey, session.requestGeneration)
            ) {
                return@songFilter false
            }
            val header = operationHeaders[operationId]
                ?.takeIf { item ->
                    item.stableKey == songKey &&
                        item.state in DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_STATES
                }
                ?: return@songFilter false
            session.operationStatesBySongKey[songKey] = header.state
            val directRequest = directRequestsByOperationId[operationId]
                ?.takeIf { request -> request.song.stableKey() == songKey }
            val snapshot = fallbackMetadata[operationId]
            if (directRequest == null && snapshot == null) {
                return@songFilter false
            }
            val scheduleMetadata = directRequest?.let { request ->
                BatchOperationScheduleMetadata(
                    operationId = operationId,
                    preserveStaging = request.preserveStaging,
                    requiresWifiNetwork = request.requiresWifiNetwork,
                    attemptId = request.attemptId,
                    artifactLeaseId = request.artifactLeaseId,
                    userInitiated = request.userInitiated,
                    downloadAudioQuality = request.downloadAudioQuality
                )
            } ?: snapshot?.let { metadata ->
                BatchOperationScheduleMetadata(
                    operationId = operationId,
                    preserveStaging = metadata.preserveStaging,
                    requiresWifiNetwork = metadata.requiresWifiNetwork,
                    attemptId = metadata.attemptId,
                    artifactLeaseId = metadata.artifactLeaseId,
                    userInitiated = metadata.userInitiated,
                    downloadAudioQuality = metadata.downloadAudioQuality
                )
            } ?: return@songFilter false
            session.scheduleMetadataBySongKey[songKey] = scheduleMetadata
            session.artifactLeaseIdsBySongKey[songKey] = scheduleMetadata.artifactLeaseId
            scheduleMetadata.attemptId?.takeIf { attemptId -> attemptId > 0L }
                ?.let { attemptId -> durableAttemptIds[songKey] = attemptId }
            true
        }
        preparedAttemptIds = taskStore.ensureDownloadTasks(
            songs = taskSongs,
            status = DownloadStatus.QUEUED,
            durableAttemptIds = durableAttemptIds
        )
        session.preparedAttemptIds.putAll(preparedAttemptIds)
        bindBatchDownloadPresentationAttempts(
            batchId = session.batchPresentationId,
            attemptIdsBySongKey = preparedAttemptIds,
            operationIdsBySongKey = session.operationIdsBySongKey
                .filterKeys(preparedAttemptIds::containsKey)
        )
    }
    if (!admitted) {
        NPLogger.d(
            TAG,
            "清空任务已使批量任务预创建过期: requested=${songs.size}"
        )
        return false
    }
    return preparedAttemptIds.isNotEmpty()
}

internal suspend fun GlobalDownloadManager.findClaimableBatchDownloadSongs(
    session: BatchDownloadSession
): List<SongItem> {
    val currentRequestedSongs = session.requestedSongs.filter { song ->
        isDownloadRequestGenerationCurrent(song.stableKey(), session.requestGeneration)
    }
    if (currentRequestedSongs.isEmpty()) {
        NPLogger.d(
            TAG,
            "批量下载准备前请求已过期: generation=${session.requestGeneration}"
        )
        return emptyList()
    }
    val operationHeaders = DownloadExecutionRoomStore.readOperationHeaders(
        context = session.context,
        operationIds = currentRequestedSongs.mapNotNull { song ->
            session.operationIdsBySongKey[song.stableKey()]
        }
    )
    val claimableSongs = currentRequestedSongs.filter { song ->
        val songKey = song.stableKey()
        val operationId = session.operationIdsBySongKey[songKey]
        val header = operationId?.let(operationHeaders::get)
        header?.stableKey == songKey &&
            header.state in DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_STATES
    }
    if (claimableSongs.isEmpty()) {
        NPLogger.d(TAG, "批量下载 operation 已在 claim 前被其他执行接管")
    }
    return claimableSongs
}

internal suspend fun GlobalDownloadManager.prepareBatchDownloadSong(
    session: BatchDownloadSession,
    song: SongItem,
    downloadLibrarySnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?
): Boolean {
    val admitted = admitDownloadMutation(
        context = session.context,
        admissionTicket = session.admissionTicket,
        stableKey = song.stableKey(),
        operationId = session.operationIdsBySongKey[song.stableKey()]
    ) {
        prepareBatchDownloadSongAdmitted(
            session = session,
            song = song,
            downloadLibrarySnapshot = downloadLibrarySnapshot
        )
    }
    if (!admitted) {
        NPLogger.d(
            TAG,
            "清空使批量单项准备过期: song=${song.name}, " +
                "operationId=${session.operationIdsBySongKey[song.stableKey()]}"
        )
    }
    return admitted
}

internal suspend fun GlobalDownloadManager.prepareBatchDownloadSongAdmitted(
    session: BatchDownloadSession,
    song: SongItem,
    downloadLibrarySnapshot: ManagedDownloadStorage.DownloadLibrarySnapshot?
) {
    val songKey = song.stableKey()
    try {
        if (!isDownloadAdmissionTicketCurrent(
                context = session.context,
                admissionTicket = session.admissionTicket,
                stableKey = songKey,
                operationId = session.operationIdsBySongKey[songKey]
            )
        ) {
            return
        }
        if (!isDownloadRequestGenerationCurrent(songKey, session.requestGeneration)) {
            session.settledSongKeys += songKey
            session.preparedAttemptIds[songKey]?.let { attemptId ->
                session.settledAttemptIds[songKey] = attemptId
                markBatchDownloadPresentationTerminal(
                    songKey = songKey,
                    attemptId = attemptId,
                    terminalState = BatchDownloadTerminalState.CANCELLED,
                    operationId = session.operationIdsBySongKey[songKey]
                )
            }
            return
        }
        val preparedArtifact = claimAndPrepareBatchArtifact(session, song)
        when (val artifactClaim = preparedArtifact.artifactClaim) {
            is ManagedDownloadArtifactClaim.AlreadyDownloaded,
            is ManagedDownloadArtifactClaim.RepairRequired -> {
                val artifact = when (artifactClaim) {
                    is ManagedDownloadArtifactClaim.AlreadyDownloaded -> artifactClaim.artifact
                    is ManagedDownloadArtifactClaim.RepairRequired -> artifactClaim.artifact
                }
                if (
                    !preparedArtifact.requiresFinalizationRecovery &&
                        artifactClaim is ManagedDownloadArtifactClaim.AlreadyDownloaded
                ) {
                    val durableSettled = settleAlreadyDownloadedOperation(
                        context = session.context,
                        song = song,
                        operationId = preparedArtifact.operationId,
                        expectedAttemptId = preparedArtifact.attemptId,
                        reason = "BATCH_ALREADY_PRESENT"
                    )
                    if (!durableSettled) {
                        val attemptId = preparedArtifact.attemptId
                        if (attemptId != null) {
                            session.enqueue(
                                song = song,
                                attemptId = attemptId,
                                operationId = preparedArtifact.operationId
                            )
                        }
                        NPLogger.w(
                            TAG,
                            "批量已下载 operation 尚未完成 CAS，保留共享泵重试: " +
                                "song=${song.name}, operationId=${preparedArtifact.operationId}"
                        )
                        return
                    }
                    session.settledSongKeys += songKey
                    preparedArtifact.attemptId?.let { attemptId ->
                        session.settledAttemptIds[songKey] = attemptId
                        markBatchDownloadPresentationTerminal(
                            songKey = songKey,
                            attemptId = attemptId,
                            terminalState = BatchDownloadTerminalState.COMPLETED,
                            operationId = preparedArtifact.operationId
                        )
                    }
                    NPLogger.d(TAG, "批量下载跳过已完成 artifact: song=${song.name}")
                    return
                }
                if (preparedArtifact.requiresFinalizationRecovery) {
                    NPLogger.d(
                        TAG,
                        "批量下载恢复未完成资产收尾: song=${song.name}, " +
                            "artifactState=${artifact.state}, " +
                            "claim=${artifactClaim.javaClass.simpleName}"
                    )
                    if (tryFinalizePreparedBatchArtifact(session, song, preparedArtifact)) {
                        return
                    }
                }
            }

            is ManagedDownloadArtifactClaim.InFlight -> {
                val attemptId = requireNotNull(preparedArtifact.attemptId) {
                    "in-flight artifact is missing a durable retry request"
                }
                session.enqueue(
                    song = song,
                    attemptId = attemptId,
                    operationId = preparedArtifact.operationId
                )
                NPLogger.d(
                    TAG,
                    "批量下载 artifact 已由其他 operation 持有，保留宿主重试: " +
                        "song=${song.name}, operationId=${preparedArtifact.operationId}"
                )
                return
            }

            is ManagedDownloadArtifactClaim.Acquired,
            null -> Unit
        }
        val attemptId = preparedArtifact.attemptId
        if (attemptId == null) {
            preparedArtifact.acquiredLeaseId?.let { leaseId ->
                releaseDownloadArtifactClaim(session.context, song, leaseId)
            }
            return
        }
        if (preparedArtifact.requiresFinalizationRecovery) {
            session.enqueue(song, attemptId, preparedArtifact.operationId)
            return
        }
        if (shouldSkipDownload(session.context, song)) {
            session.skippedLocalSongs++
            preparedArtifact.acquiredLeaseId?.let { leaseId ->
                releaseDownloadArtifactClaim(session.context, song, leaseId)
            }
            val durableSettled = settleAlreadyDownloadedOperation(
                context = session.context,
                song = song,
                operationId = preparedArtifact.operationId,
                expectedAttemptId = attemptId,
                reason = "BATCH_LOCAL_SONG_SKIPPED"
            )
            if (!durableSettled) {
                session.enqueue(song, attemptId, preparedArtifact.operationId)
                NPLogger.w(
                    TAG,
                    "批量本地歌曲 operation 尚未完成 CAS，保留共享泵重试: " +
                        "song=${song.name}, operationId=${preparedArtifact.operationId}"
                )
                return
            }
            session.settledSongKeys += songKey
            session.settledAttemptIds[songKey] = attemptId
            markBatchDownloadPresentationTerminal(
                songKey = songKey,
                attemptId = attemptId,
                terminalState = BatchDownloadTerminalState.COMPLETED,
                operationId = session.operationIdsBySongKey[songKey]
            )
            NPLogger.d(TAG, "批量下载跳过本地歌曲: song=${song.name}, songKey=$songKey")
            return
        }
        val forceFreshTransfer = (
            preparedArtifact.artifactClaim as? ManagedDownloadArtifactClaim.Acquired
            )?.preservesExistingReference == true
        val fastCachedSong = if (forceFreshTransfer) {
            null
        } else {
            findFastCachedDownloadedSong(session.context, song)
        }
        if (fastCachedSong != null) {
            settleFastCachedBatchDownload(
                session = session,
                song = song,
                attemptId = attemptId,
                acquiredLeaseId = preparedArtifact.acquiredLeaseId,
                downloadedSong = fastCachedSong
            )
            return
        }
        val existingAudio = if (forceFreshTransfer) {
            null
        } else {
            findExistingDownloadedAudio(
                context = session.context,
                song = song,
                snapshot = downloadLibrarySnapshot,
                allowStorageLookup = false
            )
        }
        val needsFinalization = existingAudio?.let { audio ->
            isUnfinalizedDownloadedMetadata(readDownloadedMetadata(session.context, audio))
        } == true
        when (
            resolvePreExistingDownloadedAudioAction(
                hasExistingAudio = existingAudio != null,
                needsFinalization = needsFinalization
            )
        ) {
            PreExistingDownloadedAudioAction.FINALIZE_EXISTING -> {
                session.enqueue(song, attemptId, preparedArtifact.operationId)
                return
            }

            PreExistingDownloadedAudioAction.DIRECT_SETTLE -> {
                val audio = requireNotNull(existingAudio)
                settleExistingBatchDownload(
                    session = session,
                    song = song,
                    attemptId = attemptId,
                    acquiredLeaseId = preparedArtifact.acquiredLeaseId,
                    storedAudio = audio
                )
                return
            }

            PreExistingDownloadedAudioAction.CONTINUE_DOWNLOAD -> Unit
        }
        val operationId = session.operationIdsBySongKey[songKey]
        if (operationId == null) {
            preparedArtifact.acquiredLeaseId?.let { leaseId ->
                releaseDownloadArtifactClaim(session.context, song, leaseId)
            }
            session.settledSongKeys += songKey
            session.settledAttemptIds[songKey] = attemptId
            markBatchDownloadPresentationTerminal(
                songKey = songKey,
                attemptId = attemptId,
                terminalState = BatchDownloadTerminalState.FAILED,
                operationId = operationId
            )
            NPLogger.w(TAG, "批量下载缺少持久化 operation: song=${song.name}")
            return
        }
        session.enqueue(song, attemptId, operationId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        handleBatchDownloadPreparationFailure(session, song)
        NPLogger.e(
            TAG,
            "批量下载单项准备失败: song=${song.name}, error=${error.message}",
            error
        )
    }
}

internal suspend fun GlobalDownloadManager.tryFinalizePreparedBatchArtifact(
    session: BatchDownloadSession,
    song: SongItem,
    preparedArtifact: PreparedBatchArtifact
): Boolean {
    if (!preparedArtifact.requiresFinalizationRecovery) return false
    val artifact = when (val claim = preparedArtifact.artifactClaim) {
        is ManagedDownloadArtifactClaim.AlreadyDownloaded -> claim.artifact
        is ManagedDownloadArtifactClaim.RepairRequired -> claim.artifact
        else -> return false
    }
    val attemptId = preparedArtifact.attemptId
    val storedAudio = findPendingAudioForFinalization(
        context = session.context,
        song = song,
        operationId = preparedArtifact.operationId,
        preferredAudioName = artifact.audioName,
        preferredAudioReference = artifact.audioReference
    ) ?: run {
        NPLogger.w(
            TAG,
            "批量下载暂未找到 artifact 对应音频，保留 recovery operation: " +
                "song=${song.name}, operationId=${preparedArtifact.operationId}"
        )
        return false
    }
    val expectedLeaseId = preparedArtifact.acquiredLeaseId ?: artifact.leaseId
    try {
        finalizeCompletedDownload(
            context = session.context,
            song = song,
            expectedAttemptId = attemptId,
            operationId = preparedArtifact.operationId,
            expectedArtifactLeaseId = expectedLeaseId,
            storedAudioHint = storedAudio,
            allowMissingTask = true,
            admissionTicket = session.admissionTicket
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: DownloadStorageMutationDeferredException) {
        NPLogger.d(
            TAG,
            "批量下载 artifact 收尾遇到目录迁移，保留 recovery operation: " +
                "song=${song.name}, operationId=${preparedArtifact.operationId}"
        )
        return false
    } catch (error: Exception) {
        NPLogger.w(
            TAG,
            "批量下载 artifact 收尾失败，保留 recovery operation: " +
                "song=${song.name}, error=${error.message}",
            error
        )
        return false
    }
    val task = taskStore.findTask(song.stableKey())
    val matchingCompletedTask = task?.takeIf { currentTask ->
        currentTask.status == DownloadStatus.COMPLETED &&
            (attemptId == null || currentTask.attemptId == attemptId)
    }
    val operationState = runCatching {
        DownloadExecutionRoomStore.state(
            context = session.context,
            operationId = preparedArtifact.operationId
        )
    }.getOrNull()
    val artifactState = runCatching {
        managedDownloadArtifactCoordinator.currentStateAnyRoot(
            context = session.context,
            song = song,
            expectedLeaseId = expectedLeaseId
        )
    }.getOrNull()
    val artifactFinalized = isDownloadFinalizationDurablySettled(
        operationState = operationState,
        artifactState = artifactState?.name
    )
    if (artifactState == null && operationState == null) {
        NPLogger.w(
            TAG,
            "批量下载 artifact 收尾后读取状态失败: " +
                "song=${song.name}, operationId=${preparedArtifact.operationId}"
        )
    }
    if (!artifactFinalized) {
        NPLogger.w(
            TAG,
            "批量下载 artifact 收尾未确认完成，保留 recovery operation: " +
                "song=${song.name}, operationId=${preparedArtifact.operationId}, " +
                "attemptId=$attemptId, taskStatus=${task?.status}, " +
                "taskAttemptId=${task?.attemptId}"
        )
        return false
    }
    val songKey = song.stableKey()
    val settledTaskAttemptId = settleAndRemoveRecoveredTask(
        songKey = songKey,
        expectedAttemptId = attemptId,
        promoteStatus = true
    )
    session.settledSongKeys += songKey
    (
        settledTaskAttemptId ?:
            matchingCompletedTask?.attemptId ?:
            attemptId
        )?.let { settledAttemptId ->
        session.settledAttemptIds[songKey] = settledAttemptId
        markBatchDownloadPresentationTerminal(
            songKey = songKey,
            attemptId = settledAttemptId,
            terminalState = BatchDownloadTerminalState.COMPLETED,
            operationId = preparedArtifact.operationId
        )
    }
    NPLogger.d(
        TAG,
        "批量下载 artifact 收尾完成，跳过重复宿主调度: " +
            "song=${song.name}, operationId=${preparedArtifact.operationId}"
    )
    return true
}
