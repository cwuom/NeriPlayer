package moe.ouom.neriplayer.core.download.manager.admission

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.isWifiBoundNetworkPolicyObservationCurrent
import moe.ouom.neriplayer.core.download.manager.batch.cancellationOperationIdsForSong
import moe.ouom.neriplayer.core.download.manager.recovery.recoverPendingAudioWritesFromRoot
import moe.ouom.neriplayer.core.download.manager.recovery.recoverUnfinalizedPublishedAudioFromRoot
import moe.ouom.neriplayer.core.download.manager.runtime.wakeDownloadExecutionPump
import moe.ouom.neriplayer.core.download.policy.nextDownloadOperationCreatedAtMs
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.StagedPendingDownloadQueue
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.core.download.execution.clear.DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.clear.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.resolveDownloadAudioQualitySelection
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull


internal fun GlobalDownloadManager.completeStartupProgressRestoreReady() {
    if (!startupProgressRestoreReady.isCompleted) {
        startupProgressRestoreReady.complete(Unit)
    }
}

internal fun GlobalDownloadManager.isDownloadClearFenceActive(
    context: Context,
    stableKey: String? = null,
    operationId: String? = null
): Boolean {
    val appContext = context.applicationContext
    return if (stableKey == null && operationId == null) {
        PersistentDownloadClearFenceStore.isActive(appContext)
    } else {
        PersistentDownloadClearFenceStore.isBlocked(
            context = appContext,
            stableKey = stableKey,
            operationId = operationId
        )
    }
}

internal fun GlobalDownloadManager.openDownloadAdmissionTicketOrNull(
    context: Context,
    stableKey: String? = null,
    operationId: String? = null
): Long? {
    val appContext = context.applicationContext
    if (isDownloadClearFenceActive(appContext, stableKey, operationId)) {
        return null
    }
    val ticket = downloadAdmissionGate.openTicketOrNull() ?: return null
    return ticket.takeIf {
        !isDownloadClearFenceActive(appContext, stableKey, operationId) &&
            downloadAdmissionGate.openTicketOrNull() == it
    }
}

internal fun GlobalDownloadManager.isDownloadAdmissionTicketCurrent(
    context: Context,
    admissionTicket: Long,
    stableKey: String? = null,
    operationId: String? = null
): Boolean {
    return !isDownloadClearFenceActive(context, stableKey, operationId) &&
        downloadAdmissionGate.openTicketOrNull() == admissionTicket
}

internal fun GlobalDownloadManager.openDownloadAdmissionTicketForStableKeysOrNull(
    context: Context,
    stableKeys: Collection<String>
): Long? {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    if (keys.isEmpty()) return null
    val ticket = downloadAdmissionGate.openTicketOrNull() ?: return null
    return ticket.takeIf { candidate ->
        downloadAdmissionGate.openTicketOrNull() == candidate &&
            keys.all { key -> !isDownloadClearFenceActive(context, stableKey = key) }
    }
}

internal fun GlobalDownloadManager.isDownloadAdmissionTicketCurrentForStableKeys(
    context: Context,
    admissionTicket: Long,
    stableKeys: Collection<String>
): Boolean {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    return keys.isNotEmpty() &&
        downloadAdmissionGate.openTicketOrNull() == admissionTicket &&
        keys.all { key -> !isDownloadClearFenceActive(context, stableKey = key) }
}

internal suspend fun GlobalDownloadManager.awaitDownloadAdmissionTicketForStableKeys(
    context: Context,
    stableKeys: Collection<String>
): Long {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    require(keys.isNotEmpty()) { "stableKeys must not be empty" }
    while (true) {
        val openTicket = downloadAdmissionGate.openTicketOrNull()
        if (
            openTicket != null &&
                keys.all { key -> !isDownloadClearFenceActive(context, stableKey = key) }
        ) {
            return openTicket
        }
        if (openTicket == null) {
            downloadAdmissionGate.awaitOpen()
        } else {
            delay(DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS)
        }
    }
}

internal suspend fun GlobalDownloadManager.admitDownloadMutationForStableKeys(
    context: Context,
    admissionTicket: Long,
    stableKeys: Collection<String>,
    block: suspend (Set<String>) -> Unit
): Boolean {
    val normalizedKeys = stableKeys
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    if (normalizedKeys.isEmpty()) return false
    var ranBlock = false
    val admitted = downloadAdmissionGate.admit(admissionTicket) {
        if (normalizedKeys.any { key ->
                isDownloadClearFenceActive(context, stableKey = key)
            }
        ) {
            return@admit
        }
        ranBlock = true
        block(normalizedKeys)
    }
    return admitted && ranBlock
}

internal fun GlobalDownloadManager.scheduleStartupArtifactRecovery(context: Context) {
    val appContext = context.applicationContext
    if (!startupArtifactRecoveryActive.compareAndSet(false, true)) {
        NPLogger.d(TAG, "跳过重复的启动 artifact 收尾调度")
        return
    }
    scope.launch {
        try {
            delay(STARTUP_ARTIFACT_RECOVERY_HANDOFF_DELAY_MS)
            startupRecoveryMutex.withLock {
                val admissionTicket = downloadAdmissionGate.openTicketOrNull()
                if (
                    admissionTicket == null ||
                        isDownloadClearFenceActive(appContext) ||
                        !isDownloadAdmissionTicketCurrent(appContext, admissionTicket)
                ) {
                    NPLogger.d(TAG, "下载清空栅栏仍生效，保留启动 artifact 收尾凭据")
                } else {
                    val directoryMutationLease =
                        ManagedDownloadDirectoryMutationFence.acquireRecoveryLeaseOrNull(
                            appContext
                        )
                    if (directoryMutationLease == null) {
                        NPLogger.d(
                            TAG,
                            "目录迁移占用恢复入口，保留启动 artifact 收尾凭据"
                        )
                    } else {
                        try {
                            if (
                                !isDownloadAdmissionTicketCurrent(
                                    appContext,
                                    admissionTicket
                                )
                            ) {
                                NPLogger.d(
                                    TAG,
                                    "下载清空栅栏在启动恢复取租约后生效，保留 artifact 凭据"
                                )
                            } else {
                                recoverPendingAudioWritesFromRoot(
                                    context = appContext,
                                    admissionTicket = admissionTicket
                                )
                                yield()
                                recoverUnfinalizedPublishedAudioFromRoot(
                                    context = appContext,
                                    admissionTicket = admissionTicket
                                )
                            }
                        } finally {
                            directoryMutationLease.close()
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NPLogger.w(
                TAG,
                "启动 artifact 收尾失败，保留持久凭据等待后续重试: ${error.message}",
                error
            )
        } finally {
            startupArtifactRecoveryActive.set(false)
        }
    }
}

internal fun GlobalDownloadManager.dismissMobileDataDownloadInterruptionRequest() {
    mobileDataDownloadInterruptionEpoch.incrementAndGet()
    mobileDataDownloadInterruptionRequestMutable.value = null
}

internal fun GlobalDownloadManager.isWifiBoundNetworkPolicyStillRequired(
    context: Context,
    snapshotEpoch: Long
): Boolean {
    return !isDownloadClearFenceActive(context) &&
        isWifiBoundNetworkPolicyObservationCurrent(
            snapshotEpoch = snapshotEpoch,
            currentEpoch = wifiBoundNetworkPolicyEpoch.get(),
            currentNetworkType = context.applicationContext.currentDownloadNetworkTypeOrNull()
        )
}

internal inline fun GlobalDownloadManager.mutateWifiBoundNetworkPolicyIfStillRequired(
    context: Context,
    snapshotEpoch: Long,
    mutation: () -> Unit
): Boolean {
    return synchronized(wifiBoundNetworkPolicyMutationLock) {
        if (!isWifiBoundNetworkPolicyStillRequired(context, snapshotEpoch)) {
            false
        } else {
            mutation()
            true
        }
    }
}

internal fun GlobalDownloadManager.recoverWifiBoundDownloadsIfNetworkPolicyExpired(
    context: Context,
    snapshotEpoch: Long,
    reason: String
) {
    if (
        !isWifiBoundNetworkPolicyStillRequired(context, snapshotEpoch) &&
            context.applicationContext.currentDownloadNetworkTypeOrNull() == TrafficNetworkType.WIFI
    ) {
        recoverPendingDownloadsForNetworkRestored(context, reason)
    }
}

internal suspend fun GlobalDownloadManager.awaitDownloadAdmissionTicket(
    context: Context,
    stableKey: String? = null,
    operationId: String? = null
): Long {
    val appContext = context.applicationContext
    while (true) {
        val openTicket = downloadAdmissionGate.openTicketOrNull()
        if (
            openTicket != null &&
                !isDownloadClearFenceActive(appContext, stableKey, operationId)
        ) {
            return openTicket
        }
        if (openTicket == null) {
            downloadAdmissionGate.awaitOpen()
        } else {
            delay(DOWNLOADED_SONG_DELETE_BARRIER_POLL_MS)
        }
    }
}

internal suspend fun GlobalDownloadManager.admitDownloadMutation(
    context: Context,
    admissionTicket: Long,
    stableKey: String? = null,
    operationId: String? = null,
    block: suspend () -> Unit
): Boolean {
    val appContext = context.applicationContext
    if (
        isDownloadClearFenceActive(appContext, stableKey, operationId) ||
            downloadAdmissionGate.openTicketOrNull() != admissionTicket
    ) {
        return false
    }
    var ranBlock = false
    val admitted = downloadAdmissionGate.admit(admissionTicket) admission@{
        if (isDownloadClearFenceActive(appContext, stableKey, operationId)) {
            return@admission
        }
        ranBlock = true
        block()
    }
    return admitted && ranBlock
}

internal suspend fun GlobalDownloadManager.admitArtifactRecoveryMutation(
    context: Context,
    admissionTicket: Long?,
    block: suspend () -> Unit
): Boolean {
    if (admissionTicket == null) {
        NPLogger.d(TAG, "无下载准入票据，跳过 artifact 恢复 mutation")
        return false
    }
    return admitDownloadMutation(
        context = context,
        admissionTicket = admissionTicket,
        block = block
    )
}

internal suspend fun GlobalDownloadManager.resolveOperationRequestsForBatchBinding(
    context: Context,
    operationIds: Collection<String>,
    knownRequests: Collection<DownloadExecutionRequest>
): List<DownloadExecutionRequest> {
    val normalizedOperationIds = operationIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
    val requestedOperationIds = normalizedOperationIds.toHashSet()
    val knownRequestsByOperationId = knownRequests
        .asSequence()
        .filter { request -> request.operationId in requestedOperationIds }
        .distinctBy(DownloadExecutionRequest::operationId)
        .associateBy(DownloadExecutionRequest::operationId)
    val missingOperationIds = normalizedOperationIds
        .filterNot(knownRequestsByOperationId::containsKey)
    if (missingOperationIds.isEmpty()) {
        return normalizedOperationIds.mapNotNull(knownRequestsByOperationId::get)
    }
    val restoredRequests = DownloadExecutionRoomStore.readOperationSnapshots(
        context = context.applicationContext,
        operationIds = missingOperationIds
    ).mapValues { (_, snapshot) -> snapshot.request }
    return normalizedOperationIds.mapNotNull { operationId ->
        knownRequestsByOperationId[operationId] ?: restoredRequests[operationId]
    }
}

internal suspend fun GlobalDownloadManager.stageAndPromotePendingDownloadQueue(
    context: Context,
    songs: List<SongItem>,
    userInitiated: Boolean,
    batchIdentity: DownloadExecutionRoomStore.DownloadBatchIdentity? = null,
    onPageReady: suspend (pageIndex: Int, page: StagedPendingDownloadQueue) -> Unit =
        { _, _ -> }
): StagedPendingDownloadQueue {
    val distinctSongs = songs.distinctBy(SongItem::stableKey)
    if (distinctSongs.isEmpty()) {
        return StagedPendingDownloadQueue(
            operationIds = emptyList(),
            skippedSongKeys = emptySet()
        )
    }
    val operationCreatedAtMs = nextDownloadOperationCreatedAtMs(
        requestedAtMs = System.currentTimeMillis(),
        cancellationCutoffs = distinctSongs.mapNotNull { song ->
            cancellationOperationSnapshotCutoffs[song.stableKey()]
        }
    )
    val operationIds = linkedSetOf<String>()
    val skippedSongKeys = linkedSetOf<String>()
    val operationIdsBySongKey = linkedMapOf<String, String>()
    val operationRequestsBySongKey = linkedMapOf<String, DownloadExecutionRequest>()
    distinctSongs.chunked(BATCH_OPERATION_STAGE_PAGE_SIZE).forEachIndexed { pageIndex, page ->
        if (batchIdentity != null) {
            DownloadExecutionRoomStore.prepareBatchMembersForTransfer(
                context = context.applicationContext,
                identity = batchIdentity,
                stableKeys = page.map(SongItem::stableKey)
            )
        }
        val stagedPage = stageAndPromotePendingDownloadQueuePage(
            context = context,
            songs = page,
            userInitiated = userInitiated,
            operationCreatedAtMs = operationCreatedAtMs
        )
        if (batchIdentity != null && stagedPage.operationIds.isNotEmpty()) {
            val pageRequests = resolveOperationRequestsForBatchBinding(
                context = context.applicationContext,
                operationIds = stagedPage.operationIds,
                knownRequests = stagedPage.operationRequestsBySongKey.values
            )
            DownloadExecutionRoomStore.attachBatchIdentity(
                context = context.applicationContext,
                identity = batchIdentity,
                requests = pageRequests
            )
        }
        onPageReady(pageIndex, stagedPage)
        operationIds += stagedPage.operationIds
        skippedSongKeys += stagedPage.skippedSongKeys
        stagedPage.operationIdsBySongKey.forEach { (songKey, operationId) ->
            operationIdsBySongKey.putIfAbsent(songKey, operationId)
        }
        stagedPage.operationRequestsBySongKey.forEach { (songKey, request) ->
            operationRequestsBySongKey.putIfAbsent(songKey, request)
        }
    }
    return StagedPendingDownloadQueue(
        operationIds = operationIds.toList(),
        skippedSongKeys = skippedSongKeys,
        operationIdsBySongKey = operationIdsBySongKey,
        operationRequestsBySongKey = operationRequestsBySongKey
    )
}

internal suspend fun GlobalDownloadManager.stageAndPromotePendingDownloadQueuePage(
    context: Context,
    songs: List<SongItem>,
    userInitiated: Boolean,
    operationCreatedAtMs: Long
): StagedPendingDownloadQueue {
    val distinctSongs = songs.distinctBy(SongItem::stableKey)
    if (distinctSongs.isEmpty()) {
        return StagedPendingDownloadQueue(
            operationIds = emptyList(),
            skippedSongKeys = emptySet()
        )
    }
    // 取消后的旧 operation 仍可能短暂处于 RUNNING 或 CANCEL_REQUESTED。
    // 它们必须留给取消收敛，新的用户意图要分配全新的 operation 身份
    val excludedOperationIds = distinctSongs
        .flatMap { song -> cancellationOperationIdsForSong(song.stableKey()) }
        .toSet()
    val forceNewOperationForStableKeys = distinctSongs
        .map(SongItem::stableKey)
        .filter(cancellationForceNewSongKeys::contains)
        .toSet()
    val currentNetworkType = context.currentDownloadNetworkTypeOrNull()
    val requiresWifiNetwork = !userInitiated ||
        currentNetworkType == null || currentNetworkType == TrafficNetworkType.WIFI
    val downloadAudioQuality = resolveDownloadAudioQualitySelection(context)
    val recoveryStore = DownloadRecoveryRoomStore(context.applicationContext)
    val existingReusableOperationsBySongKey = DownloadExecutionRoomStore
        .findReadableOperationsBySongKeys(
            context = context.applicationContext,
            songKeys = distinctSongs.map(SongItem::stableKey),
            states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
            excludeUserStoppedOperations = true,
            excludedOperationIds = excludedOperationIds
        )
    // 用户再次点击下载时，复用的 QUEUED/RETRYABLE operation 也必须携带
    // 明确的重试意图，否则 post-core artifact 会被当成仅收尾任务，永远不进入真实传输
    val effectiveExistingReusableOperationsBySongKey = if (!userInitiated) {
        existingReusableOperationsBySongKey
    } else {
        val promoted = linkedMapOf<String, DownloadExecutionRequest>()
        for ((songKey, request) in existingReusableOperationsBySongKey) {
            promoted[songKey] = promoteUserInitiatedInFlightRequests(
                context = context.applicationContext,
                requests = listOf(request),
                userInitiated = true
            ).singleOrNull() ?: request
        }
        promoted
    }
    val existingReusableOperationIds = effectiveExistingReusableOperationsBySongKey.values
        .map(DownloadExecutionRequest::operationId)
        .distinct()
    val waitingBatch = recoveryStore.upsertWaitingStorageMutationWithRequests(
        songs = distinctSongs,
        nowMs = operationCreatedAtMs,
        userInitiated = userInitiated,
        requiresWifiNetwork = requiresWifiNetwork,
        downloadAudioQuality = downloadAudioQuality,
        excludedOperationIds = excludedOperationIds,
        forceNewOperationForStableKeys = forceNewOperationForStableKeys
    )
    settleSupersededCancellationOperations(
        context = context.applicationContext,
        songKeys = distinctSongs.map(SongItem::stableKey),
        excludedOperationIds = waitingBatch.operationIds
    )
    val waitingOperationIds = waitingBatch.operationIds
    forceNewOperationForStableKeys.forEach { songKey ->
        if (waitingOperationIds.any { operationId ->
                waitingBatch.requestsByOperationId[operationId]
                    ?.song
                    ?.stableKey() == songKey
            }
        ) {
            cancellationForceNewSongKeys.remove(songKey)
        }
    }
    if (ManagedDownloadDirectoryMutationFence.isActive(context)) {
        existingReusableOperationIds.forEach { operationId ->
            DownloadExecutionRoomStore.markWaitingForStorageMutation(
                context = context.applicationContext,
                operationId = operationId,
                errorCode = DIRECTORY_CHANGE_DOWNLOAD_DEFERRED_ERROR
            )
        }
        val allWaitingOperationIds = (
            waitingOperationIds + existingReusableOperationIds
            ).distinct().filter { operationId ->
                DownloadExecutionRoomStore.state(context, operationId) ==
                    WAITING_STORAGE_MUTATION_OPERATION_STATE
            }
        val waitingOperationIdentities = DownloadExecutionRoomStore
            .readOperationIdentities(
                context = context,
                operationIds = allWaitingOperationIds
            )
        val operationIdsBySongKey = waitingOperationIdentities.values
            .filter { identity ->
                identity.stableKey in distinctSongs.map(SongItem::stableKey)
            }
            .associate { identity -> identity.stableKey to identity.operationId }
        NPLogger.d(
            TAG,
            "下载目录迁移期间保留持久等待意图: waiting=${allWaitingOperationIds.size}"
        )
        return StagedPendingDownloadQueue(
            operationIds = allWaitingOperationIds,
            skippedSongKeys = emptySet(),
            operationIdsBySongKey = operationIdsBySongKey
        )
    }
    val waitingOperationMetadata = DownloadExecutionRoomStore.readOperationRequestMetadata(
        context = context,
        operationIds = waitingOperationIds.filterNot {
            it in waitingBatch.requestsByOperationId
        }
    )
    val waitingOperationIdentities = DownloadExecutionRoomStore.readOperationIdentities(
        context = context,
        operationIds = waitingOperationIds
    )
    val skippedSongKeys = linkedSetOf<String>()
    val promotableWaitingOperationIds = waitingOperationIds.filter { operationId ->
        val directRequest = waitingBatch.requestsByOperationId[operationId]
        val metadata = waitingOperationMetadata[operationId]
        val identityStableKey = waitingOperationIdentities[operationId]?.stableKey
        val stableKey = resolveBatchWaitingOperationStableKey(
            directRequest = directRequest,
            metadataStableKey = metadata?.stableKey,
            identityStableKey = identityStableKey
        )
        if (!isBatchWaitingOperationReadable(
                directRequest = directRequest,
                metadataAvailable = metadata != null,
                stableKey = stableKey,
                identityStableKey = identityStableKey
            )
        ) {
            if (!stableKey.isNullOrBlank()) skippedSongKeys += stableKey
            NPLogger.w(
                TAG,
                "下载意图在提升前不可读，保留该歌曲等待恢复并继续同批其余任务: " +
                    "operationId=$operationId, stableKey=$stableKey, " +
                    "directRequest=${directRequest != null}, " +
                    "metadata=${metadata != null}, " +
                    "identityStableKey=$identityStableKey"
            )
            false
        } else {
            true
        }
    }
    val promotedCount = recoveryStore.promoteWaitingStorageMutations(
        operationIds = promotableWaitingOperationIds
    )
    val candidateOperationIds = (
        existingReusableOperationIds + promotableWaitingOperationIds
        ).distinct()
    val candidateHeaders = DownloadExecutionRoomStore.readOperationHeaders(
        context = context,
        operationIds = candidateOperationIds
    )
    val operationIdsBySongKey = linkedMapOf<String, String>()
    val operationRequestsBySongKey = linkedMapOf<String, DownloadExecutionRequest>()
    effectiveExistingReusableOperationsBySongKey.forEach { (songKey, request) ->
        val header = candidateHeaders[request.operationId]
        if (
            header?.stableKey == songKey &&
                header.state in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES
        ) {
            operationIdsBySongKey[songKey] = request.operationId
            operationRequestsBySongKey[songKey] = request
        }
    }
    promotableWaitingOperationIds.forEach { operationId ->
        val directRequest = waitingBatch.requestsByOperationId[operationId]
        val stableKey = resolveBatchWaitingOperationStableKey(
            directRequest = directRequest,
            metadataStableKey = waitingOperationMetadata[operationId]?.stableKey,
            identityStableKey = waitingOperationIdentities[operationId]?.stableKey
        )
        val header = candidateHeaders[operationId]
        if (
            !stableKey.isNullOrBlank() &&
                header?.stableKey == stableKey &&
                header.state in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
                DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES
        ) {
            operationIdsBySongKey[stableKey] = operationId
            directRequest?.let { request ->
                operationRequestsBySongKey[stableKey] = request
            }
        } else if (!stableKey.isNullOrBlank()) {
            skippedSongKeys += stableKey
            NPLogger.w(
                TAG,
                "下载意图在存储变更期间不可提升，仅跳过该歌曲: " +
                    "operationId=$operationId, state=${header?.state}"
            )
        }
    }
    distinctSongs.forEach { song ->
        val songKey = song.stableKey()
        if (songKey !in operationIdsBySongKey && songKey !in skippedSongKeys) {
            skippedSongKeys += songKey
        }
    }
    return StagedPendingDownloadQueue(
        operationIds = distinctSongs.mapNotNull { song ->
            operationIdsBySongKey[song.stableKey()]
        }.distinct(),
        skippedSongKeys = skippedSongKeys,
        operationIdsBySongKey = operationIdsBySongKey,
        operationRequestsBySongKey = operationRequestsBySongKey
    ).also {
        NPLogger.d(
            TAG,
            "批量下载持久队列已一次性提升: " +
                "requested=${distinctSongs.size}, " +
                "promoted=$promotedCount, " +
                "operations=${it.operationIds.size}, " +
                "skipped=${it.skippedSongKeys.size}"
        )
    }
}

internal suspend fun GlobalDownloadManager.settleSupersededCancellationOperations(
    context: Context,
    songKeys: Collection<String>,
    excludedOperationIds: Collection<String>
) {
    val boundaries = songKeys.mapNotNull { songKey ->
        cancellationOperationSnapshotCutoffs[songKey]
            ?.let { cutoff ->
                DownloadExecutionRoomStore.CancellationBoundary(
                    stableKey = songKey,
                    createdAtMsAtMost = cutoff
                )
            }
    }
    if (boundaries.isEmpty()) return
    runCatching {
        DownloadExecutionRoomStore.requestCancelForStableKeysBefore(
            context = context,
            boundaries = boundaries,
            excludedOperationIds = excludedOperationIds
        )
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "替代 operation 落库后旧取消边界未立即收敛，保留重试凭据: " +
                "songs=${boundaries.size}, error=${error.message}",
            error
        )
    }
}

internal suspend fun GlobalDownloadManager.promoteUserInitiatedInFlightRequests(
    context: Context,
    requests: List<DownloadExecutionRequest>,
    userInitiated: Boolean
): List<DownloadExecutionRequest> {
    if (!userInitiated || requests.isEmpty()) return requests
    var promotedCount = 0
    val promotedRequests = requests.map { request ->
        if (request.userInitiated) {
            request
        } else {
            val promoted = DownloadExecutionRoomStore.promoteUserInitiatedOperation(
                context = context,
                operationId = request.operationId,
                stableKey = request.song.stableKey()
            )?.also { promotedCount++ }
            // promotion 与宿主保存可能交错；失败时优先读取最新 durable payload，
            // 避免把调用前的 userInitiated=false 再写回去
            promoted ?: DownloadExecutionRoomStore.read(
                context = context,
                operationId = request.operationId
            )?.takeIf { latest ->
                latest.song.stableKey() == request.song.stableKey()
            } ?: request
        }
    }
    if (promotedCount > 0) {
        NPLogger.d(
            TAG,
            "已提升用户重试意图并复用执行中 operation: promoted=$promotedCount"
        )
        // 宿主可能在提升前已经读过旧 payload。唤醒共享泵，让它在当前
        // execution 结束后立刻用最新 userInitiated 意图重新接管，而不是
        // 等待下一次网络事件或固定重试窗口。
        wakeDownloadExecutionPump(
            context = context.applicationContext,
            reason = "user_retry_intent_promoted"
        )
    }
    return promotedRequests
}

internal suspend fun GlobalDownloadManager.promoteWaitingStorageMutationsForRecovery(
    context: Context,
    admissionTicket: Long? = downloadAdmissionGate.openTicketOrNull()
): Int {
    val appContext = context.applicationContext
    if (
        ManagedDownloadDirectoryMutationFence.isActive(appContext) ||
        ManagedDownloadMigrationWorker.hasPersistedMigrationRecoveryFast(appContext)
    ) {
        return 0
    }
    val capturedAdmissionTicket = admissionTicket ?: run {
        NPLogger.d(TAG, "清空期间跳过恢复存储变更等待意图")
        return 0
    }
    if (!isDownloadAdmissionTicketCurrent(appContext, capturedAdmissionTicket)) {
        return 0
    }
    try {
        val rehomeAdmitted = admitDownloadMutation(appContext, capturedAdmissionTicket) {
            DownloadExecutionRoomStore.rehomeActiveOperationsToCurrentLibrary(appContext)
        }
        if (!rehomeAdmitted) {
            return 0
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "恢复目录迁移等待意图前重绑定失败，保留 operation 待下次重试: " +
                error.message,
            error
        )
    }
    val recoveryStore = DownloadRecoveryRoomStore(appContext)
    val waitingEntries = recoveryStore.listWaitingStorageMutations()
    if (waitingEntries.isEmpty()) {
        return 0
    }
    var promotedCount = 0
    val admitted = admitDownloadMutation(appContext, capturedAdmissionTicket) {
        waitingEntries.forEach { entry ->
            if (
                recoveryStore.promoteWaitingStorageMutation(
                    operationId = entry.request.operationId,
                    stableKey = entry.request.song.stableKey()
                )
            ) {
                promotedCount += 1
            }
        }
    }
    if (admitted && promotedCount > 0) {
        NPLogger.d(
            TAG,
            "恢复存储变更等待下载意图: promoted=$promotedCount, " +
                "waiting=${waitingEntries.size}"
        )
    }
    return promotedCount
}
