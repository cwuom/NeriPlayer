package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.GlobalDownloadManager.MobileDataDownloadBatchIdentity
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.WifiBoundNetworkPolicySnapshot
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.MobileDataDownloadInterruptionRequest
import moe.ouom.neriplayer.core.download.GlobalDownloadManager.RecoveryDirectSettlementResult
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.ManagedDownloadDirectoryMutationFence
import moe.ouom.neriplayer.core.download.execution.WifiBoundDownloadWakeWorker
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull


internal suspend fun GlobalDownloadManager.deferPendingDownloadRecoveryForNetworkPolicyIfNeeded(
    context: Context,
    reason: String,
    admissionTicket: Long
): Set<String> {
    if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) {
        return emptySet()
    }
    if (ManagedDownloadDirectoryMutationFence.isActive(context)) {
        NPLogger.d(
            TAG,
            "目录迁移期间不改写网络等待状态，迁移完成后再恢复: reason=$reason"
        )
        return emptySet()
    }
    val networkType = context.currentDownloadNetworkTypeOrNull()
    if (!shouldDeferPendingDownloadRecoveryForNetwork(
            networkType = networkType,
            mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed
        )
    ) {
        return emptySet()
    }
    val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()
    val networkGeneration = AudioDownloadManager.currentDownloadNetworkGeneration()

    val rehomeAdmitted = admitDownloadMutation(context, admissionTicket) {
        DownloadExecutionRoomStore.rehomeActiveOperationsToCurrentLibrary(context)
    }
    if (!rehomeAdmitted || ManagedDownloadDirectoryMutationFence.isActive(context)) {
        return emptySet()
    }
    val recoveryPlan = resolvePendingDownloadRecoveryPlan(context)
    if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) {
        return emptySet()
    }
    val waitingTaskSongs = currentWaitingNetworkTaskSongs()
    val recoverableWaitingKeys = recoveryPlan.recoveryCandidateKeys +
        waitingTaskSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
    if (!admitDownloadMutation(context, admissionTicket) {
            removeObsoleteWaitingNetworkTasks(recoverableWaitingKeys)
        }
    ) {
        return emptySet()
    }
    var directSettlementResult = RecoveryDirectSettlementResult(
        settledSongKeys = emptySet(),
        settledOperationIds = emptySet(),
        failedSongKeys = emptySet()
    )
    val settledEntriesPurged = admitDownloadMutation(context, admissionTicket) {
        directSettlementResult = settlePendingDownloadRecoveryDirectHits(
            context = context,
            settlements = recoveryPlan.directSettlements
        )
        recoveryPlan.workingFilesToDelete.forEach(
            ManagedDownloadStorage::deleteWorkingDownloadArtifacts
        )
        purgeSettledRecoveryEntries(
            context = context,
            operationIds = recoveryPlan.settledOperationIds +
                directSettlementResult.settledOperationIds,
            songKeys = recoveryPlan.settledSongKeys +
                directSettlementResult.settledSongKeys
        )
    }
    if (!settledEntriesPurged) {
        return emptySet()
    }
    if (recoveryPlan.recoveryCandidates.isEmpty() && waitingTaskSongs.isEmpty()) {
        if (recoveryPlan.pendingQueuedDownloads.isEmpty() && recoveryPlan.pendingDownloads.isEmpty()) {
            if (!admitDownloadMutation(context, admissionTicket) {
                    DownloadExecutionRoomStore.purgeAllCancelled(context)
                }
            ) {
                return emptySet()
            }
        }
        return emptySet()
    }

    val resumableSongKeys = recoveryPlan.resumableSongs
        .asSequence()
        .filterNot { song ->
            song.stableKey() in directSettlementResult.settledSongKeys
        }
        .mapTo(linkedSetOf()) { song -> song.stableKey() }
    val wifiBoundResumableSongs = recoveryPlan.recoveryCandidates
        .asSequence()
        .filter { candidate ->
            candidate.song.stableKey() in resumableSongKeys &&
                shouldPauseDownloadForWifiDisconnect(candidate.requiresWifiNetwork)
        }
        .map(PendingDownloadRecoveryCandidate::song)
        .toList()
    val wifiBoundWaitingSongs = wifiBoundSongsForNetworkPolicy(
        context = context,
        songs = waitingTaskSongs.filterNot { song ->
            song.stableKey() in directSettlementResult.settledSongKeys
        }
    )
    val waitingSongs = (wifiBoundResumableSongs + wifiBoundWaitingSongs)
        .distinctBy(SongItem::stableKey)
    if (waitingSongs.isEmpty()) {
        return emptySet()
    }

    val waitingSongKeys = waitingSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
    val waitingBatchIdentities = captureNetworkPolicyBatchIdentities(
        context = context,
        stableKeys = waitingSongKeys
    )
    var waitingStateCommitted = false
    val waitingStateAdmitted = admitDownloadMutation(context, admissionTicket) {
        waitingStateCommitted = mutateWifiBoundNetworkPolicyIfStillRequired(
            context = context,
            snapshotEpoch = networkPolicyEpoch
        ) {
            AudioDownloadManager.pauseDownloadsForNetworkPolicy(waitingSongKeys)
            taskStore.prepareDownloadTasks(
                songs = waitingSongs,
                status = DownloadStatus.WAITING_NETWORK,
                replaceExistingActiveTasks = true
            )
            mobileDataDownloadOverrideAllowed = false
        }
    }
    if (!waitingStateAdmitted || !waitingStateCommitted) {
        NPLogger.d(TAG, "启动恢复网络策略已过期，保留 WIFI 恢复路径: reason=$reason")
        return emptySet()
    }
    if (waitingBatchIdentities.isNotEmpty()) {
        runCatching {
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context = context.applicationContext,
                identities = waitingBatchIdentities.map { identity -> identity.toRoomBatchIdentity() },
                networkGeneration = networkGeneration,
                expectedNetworkGeneration = null
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "持久化启动恢复批次网络等待状态失败: ${error.message}",
                error
            )
        }
    }
    if (!isDownloadAdmissionTicketCurrent(context, admissionTicket)) {
        return emptySet()
    }
    if (!admitDownloadMutation(context, admissionTicket) {
            scheduleWifiBoundDownloadWakeTasks(context, waitingSongKeys)
        }
    ) {
        return emptySet()
    }
    NPLogger.w(
        TAG,
        "启动下载恢复网络尚未确认，已保守等待: reason=$reason, " +
            "networkType=${networkType ?: "UNKNOWN"}, count=${waitingSongs.size}"
    )
    networkType?.let { confirmedNetworkType ->
        if (!admitDownloadMutation(context, admissionTicket) {
                publishMobileDataDownloadInterruptionRequestIfNeeded(
                    context = context,
                    networkType = confirmedNetworkType,
                    fallbackTaskCount = waitingSongs.size,
                    reason = reason,
                    forceAuthoritativeRecount = true,
                    batchIdentities = waitingBatchIdentities,
                    networkGeneration = networkGeneration
                )
            }
        ) {
            return emptySet()
        }
    }
    if (!admitDownloadMutation(context, admissionTicket) {
            recoverWifiBoundDownloadsIfNetworkPolicyExpired(
                context = context,
                snapshotEpoch = networkPolicyEpoch,
                reason = "startup_network_policy_stale_$reason"
            )
        }
    ) {
        return emptySet()
    }
    return waitingSongKeys
}

internal fun GlobalDownloadManager.currentWaitingNetworkTaskSongs(): List<SongItem> {
    return taskStore.currentTasks()
        .asSequence()
        .filter { task -> task.status == DownloadStatus.WAITING_NETWORK }
        .map(DownloadTask::song)
        .distinctBy { song -> song.stableKey() }
        .toList()
}

internal fun GlobalDownloadManager.currentActiveNetworkPolicyTasks(): List<DownloadTask> {
    return taskStore.currentTasks().filter { task ->
        task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING
    }
}

internal suspend fun GlobalDownloadManager.captureNetworkPolicyBatchIdentities(
    context: Context,
    stableKeys: Collection<String>
): List<MobileDataDownloadBatchIdentity> {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) return emptyList()
    return try {
        DownloadExecutionRoomStore.findOpenBatchIdentitiesForStableKeys(
            context = context.applicationContext,
            stableKeys = keys
        )
            .map { identity ->
                MobileDataDownloadBatchIdentity(
                    batchId = identity.batchId,
                    generation = identity.generation
                )
            }
            .distinct()
            .sortedWith(
                compareBy<MobileDataDownloadBatchIdentity> { identity -> identity.batchId }
                    .thenBy { identity -> identity.generation }
            )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "捕获批次网络策略身份失败，保守不扩大恢复范围: ${error.message}",
            error
        )
        emptyList()
    }
}

internal suspend fun GlobalDownloadManager.captureWifiBoundNetworkPolicySnapshot(
    context: Context,
    candidateTasks: List<DownloadTask> = currentActiveNetworkPolicyTasks()
): WifiBoundNetworkPolicySnapshot {
    val appContext = context.applicationContext
    val durableRequirements = durableNetworkPolicyBySongKey(appContext)
    val policyBoundTasks = candidateTasks
        .distinctBy { task -> task.song.stableKey() }
        .filter { task ->
            durableRequirements[task.song.stableKey()] ?: true
        }
    val waitingSongs = currentWaitingNetworkTaskSongs().filter { song ->
        durableRequirements[song.stableKey()] ?: true
    }
    val durableSongKeys = durableRequirements
        .filterValues { requiresWifiNetwork -> requiresWifiNetwork }
        .keys
    val policyBoundSongKeys = buildSet {
        policyBoundTasks.forEach { task -> add(task.song.stableKey()) }
        waitingSongs.forEach { song -> add(song.stableKey()) }
        addAll(durableSongKeys)
    }
    val batchIdentities = captureNetworkPolicyBatchIdentities(
        context = appContext,
        stableKeys = policyBoundSongKeys
    )
    val pendingBatchSongKeys = try {
        DownloadExecutionRoomStore.findPendingStableKeysForOpenBatches(
            context = appContext,
            identities = batchIdentities.map { identity -> identity.toRoomBatchIdentity() }
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "读取网络等待批次成员失败，保留 operation 级等待: ${error.message}",
            error
        )
        emptySet()
    }
    val affectedSongKeys = policyBoundSongKeys + pendingBatchSongKeys
    val displayWaitingTasks = taskStore.currentTasks().filter { task ->
        (task.status == DownloadStatus.QUEUED ||
            task.status == DownloadStatus.DOWNLOADING) &&
            task.song.stableKey() in policyBoundSongKeys
    }
    return WifiBoundNetworkPolicySnapshot(
        policyBoundTasks = policyBoundTasks,
        displayWaitingTasks = displayWaitingTasks,
        waitingSongs = waitingSongs,
        durableSongKeys = durableSongKeys,
        policyBoundSongKeys = policyBoundSongKeys,
        affectedSongKeys = affectedSongKeys,
        batchIdentities = batchIdentities
    )
}

internal suspend fun GlobalDownloadManager.durableNetworkPolicyBySongKey(
    context: Context
): Map<String, Boolean> {
    return try {
        DownloadExecutionRoomStore.readLatestOperationNetworkPoliciesByStatesAnyLibrary(
            context = context.applicationContext,
            states = NETWORK_POLICY_OPERATION_STATES,
            excludeUserStoppedOperations = true
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "读取持久下载网络策略投影失败，内存任务按仅 WIFI 保守处理: " +
                "error=${error.message}",
            error
        )
        emptyMap()
    }
}

internal suspend fun GlobalDownloadManager.wifiBoundSongsForNetworkPolicy(
    context: Context,
    songs: List<SongItem>
): List<SongItem> {
    val distinctSongs = songs.distinctBy(SongItem::stableKey)
    if (distinctSongs.isEmpty()) {
        return emptyList()
    }
    val requiresWifiBySongKey = durableWifiRequirementBySongKey(
        context = context,
        songKeys = distinctSongs.map(SongItem::stableKey)
    )
    return distinctSongs.filter { song ->
        shouldPauseDownloadForWifiDisconnect(
            requiresWifiNetwork = requiresWifiBySongKey[song.stableKey()] ?: true
        )
    }
}

internal suspend fun GlobalDownloadManager.durableWifiRequirementBySongKey(
    context: Context,
    songKeys: Collection<String>
): Map<String, Boolean> {
    val requestedSongKeys = songKeys.map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    if (requestedSongKeys.isEmpty()) {
        return emptyMap()
    }
    return try {
        DownloadExecutionRoomStore.readLatestOperationNetworkPoliciesForStableKeys(
            context = context.applicationContext,
            stableKeys = requestedSongKeys,
            states = NETWORK_POLICY_OPERATION_STATES,
            excludeUserStoppedOperations = true
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        NPLogger.w(
            TAG,
            "读取下载网络策略失败，按仅 WIFI 保守处理: error=${error.message}",
            error
        )
        emptyMap()
    }
}

internal fun GlobalDownloadManager.scheduleWifiBoundDownloadWakeTasks(
    context: Context,
    songKeys: Set<String>
) {
    if (songKeys.isEmpty()) {
        return
    }
    val scheduled = WifiBoundDownloadWakeWorker.scheduleAll(
        context = context.applicationContext
    )
    if (!scheduled) {
        NPLogger.w(
            TAG,
            "登记批量 WIFI 恢复唤醒失败: songs=${songKeys.size}"
        )
    }
}

internal suspend fun GlobalDownloadManager.pauseActiveDownloadsForNetworkPolicyIfNeeded(
    context: Context,
    networkType: TrafficNetworkType,
    reason: String,
    admissionTicket: Long? = null,
    networkGeneration: Long? = null
): Boolean {
    if (admissionTicket != null) {
        var paused = false
        val admitted = admitArtifactRecoveryMutation(context, admissionTicket) {
            paused = pauseActiveDownloadsForNetworkPolicyIfNeeded(
                context = context,
                networkType = networkType,
                reason = reason,
                networkGeneration = networkGeneration
            )
        }
        return admitted && paused
    }
    if (networkType == TrafficNetworkType.WIFI) {
        return false
    }
    val interruptionSnapshotEpoch = mobileDataDownloadInterruptionEpoch.get()
    val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()
    val capturedNetworkGeneration = networkGeneration
        ?: AudioDownloadManager.currentDownloadNetworkGeneration()
    val policySnapshot = captureWifiBoundNetworkPolicySnapshot(
        context = context,
        candidateTasks = currentActiveNetworkPolicyTasks()
    )
    if (!hasWifiBoundNetworkPolicyDownloads(
            activeTaskCount = policySnapshot.policyBoundTasks.size,
            persistedQueuedCount = policySnapshot.durableSongKeys.size +
                policySnapshot.waitingSongs.size
        )
    ) {
        return false
    }
    if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
        NPLogger.d(TAG, "活动下载网络策略已过期，保留 WIFI 恢复路径: reason=$reason")
        return false
    }
    NPLogger.w(
        TAG,
        "非 WIFI 网络下检测到仅 WIFI 下载，先暂停并等待用户选择: " +
            "reason=$reason, networkType=$networkType, " +
            "activeTasks=${policySnapshot.policyBoundTasks.size}, " +
            "persisted=${policySnapshot.durableSongKeys.size}, " +
            "waiting=${policySnapshot.waitingSongs.size}, " +
            "affected=${policySnapshot.taskCount}"
    )
    val paused = pauseDownloadTasksForNetworkPolicy(
        context = context,
        policySnapshot = policySnapshot,
        networkPolicyEpoch = networkPolicyEpoch,
        networkGeneration = capturedNetworkGeneration
    )
    if (paused) {
        publishMobileDataDownloadInterruptionRequestIfNeeded(
            context = context,
            networkType = networkType,
            fallbackTaskCount = policySnapshot.taskCount.coerceAtLeast(1),
            reason = reason,
            authoritativeTaskCount = policySnapshot.taskCount,
            interruptionSnapshotEpoch = interruptionSnapshotEpoch,
            batchIdentities = policySnapshot.batchIdentities,
            networkGeneration = capturedNetworkGeneration
        )
    }
    if (!paused) {
        recoverWifiBoundDownloadsIfNetworkPolicyExpired(
            context = context,
            snapshotEpoch = networkPolicyEpoch,
            reason = "active_network_policy_stale_$reason"
        )
    }
    return paused
}

internal suspend fun GlobalDownloadManager.pauseActiveDownloadsForUnknownNetwork(
    context: Context,
    reason: String,
    admissionTicket: Long? = null
): Boolean {
    if (admissionTicket != null) {
        var paused = false
        val admitted = admitArtifactRecoveryMutation(context, admissionTicket) {
            paused = pauseActiveDownloadsForUnknownNetwork(
                context = context,
                reason = reason
            )
        }
        return admitted && paused
    }
    val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()
    if (!isWifiBoundNetworkPolicyStillRequired(context, networkPolicyEpoch)) {
        return false
    }
    val policySnapshot = captureWifiBoundNetworkPolicySnapshot(
        context = context,
        candidateTasks = currentActiveNetworkPolicyTasks()
    )
    val paused = pauseDownloadTasksForNetworkPolicy(
        context = context,
        policySnapshot = policySnapshot,
        networkPolicyEpoch = networkPolicyEpoch
    )
    if (paused) {
        NPLogger.w(
            TAG,
            "下载网络状态未知，已保守暂停仅 WIFI 下载: reason=$reason"
        )
    }
    return paused
}

internal suspend fun GlobalDownloadManager.deferQueuedDownloadStartForNetworkPolicyIfNeeded(
    context: Context,
    songs: List<SongItem>,
    attemptIdsBySongKey: Map<String, Long>,
    requestGeneration: Long,
    reason: String,
    deferForNetworkPolicy: Boolean
): Set<String> {
    val networkType = context.currentDownloadNetworkTypeOrNull()
    if (
        !shouldDeferQueuedDownloadStartForNetwork(
            networkType = networkType,
            mobileDataOverrideAllowed = mobileDataDownloadOverrideAllowed,
            deferForNetworkPolicy = deferForNetworkPolicy
        )
    ) {
        return emptySet()
    }
    val networkPolicyEpoch = wifiBoundNetworkPolicyEpoch.get()
    val networkGeneration = AudioDownloadManager.currentDownloadNetworkGeneration()

    val eligibleSongs = songs
        .distinctBy { song -> song.stableKey() }
        .filter { song ->
            val songKey = song.stableKey()
            attemptIdsBySongKey.containsKey(songKey) &&
                isDownloadRequestGenerationCurrent(songKey, requestGeneration)
        }
    val waitingSongs = wifiBoundSongsForNetworkPolicy(
        context = context,
        songs = eligibleSongs
    )
    if (waitingSongs.isEmpty()) {
        return emptySet()
    }

    val waitingKeys = waitingSongs.mapTo(linkedSetOf()) { song -> song.stableKey() }
    val waitingBatchIdentities = captureNetworkPolicyBatchIdentities(
        context = context,
        stableKeys = waitingKeys
    )
    val waitingStateCommitted = mutateWifiBoundNetworkPolicyIfStillRequired(
        context = context,
        snapshotEpoch = networkPolicyEpoch
    ) {
        AudioDownloadManager.pauseDownloadsForNetworkPolicy(waitingKeys)
        waitingSongs.forEach { song ->
            val songKey = song.stableKey()
            updateTaskStatus(
                songKey = songKey,
                status = DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = attemptIdsBySongKey[songKey]
            )
        }
        mobileDataDownloadOverrideAllowed = false
    }
    if (!waitingStateCommitted) {
        NPLogger.d(TAG, "排队启动网络策略已过期，保留 WIFI 恢复路径: reason=$reason")
        return emptySet()
    }
    if (waitingBatchIdentities.isNotEmpty()) {
        runCatching {
            DownloadExecutionRoomStore.markBatchesNetworkWaiting(
                context = context.applicationContext,
                identities = waitingBatchIdentities.map { identity -> identity.toRoomBatchIdentity() },
                networkGeneration = networkGeneration,
                expectedNetworkGeneration = null
            )
        }.onFailure { error ->
            NPLogger.w(
                TAG,
                "持久化排队批次网络等待状态失败: ${error.message}",
                error
            )
        }
    }
    NPLogger.w(
        TAG,
        "下载启动网络尚未确认，已保守等待: reason=$reason, " +
            "networkType=${networkType ?: "UNKNOWN"}, count=${waitingSongs.size}"
    )
    networkType?.let { confirmedNetworkType ->
        publishMobileDataDownloadInterruptionRequestIfNeeded(
            context = context,
            networkType = confirmedNetworkType,
            fallbackTaskCount = waitingSongs.size,
            reason = reason,
            forceAuthoritativeRecount = true,
            batchIdentities = waitingBatchIdentities,
            networkGeneration = networkGeneration
        )
    }
    scheduleWifiBoundDownloadWakeTasks(context, waitingKeys)
    recoverWifiBoundDownloadsIfNetworkPolicyExpired(
        context = context,
        snapshotEpoch = networkPolicyEpoch,
        reason = "queued_network_policy_stale_$reason"
    )
    return waitingKeys
}

internal suspend fun GlobalDownloadManager.publishMobileDataDownloadInterruptionRequestIfNeeded(
    context: Context,
    networkType: TrafficNetworkType,
    fallbackTaskCount: Int,
    reason: String,
    authoritativeTaskCount: Int? = null,
    forceAuthoritativeRecount: Boolean = false,
    interruptionSnapshotEpoch: Long? = null,
    batchIdentities: Collection<MobileDataDownloadBatchIdentity> = emptyList(),
    networkGeneration: Long = AudioDownloadManager.currentDownloadNetworkGeneration()
) {
    mobileDataDownloadInterruptionRequestMutex.withLock {
        val capturedBatchIdentities = batchIdentities
            .distinct()
            .sortedWith(
                compareBy<MobileDataDownloadBatchIdentity> { identity -> identity.batchId }
                    .thenBy { identity -> identity.generation }
            )
        if (onWifiBoundDownloadNetworkRestored(context, "dialog_$reason", networkGeneration)) {
            return@withLock
        }
        val publicationEpoch = interruptionSnapshotEpoch
            ?: mobileDataDownloadInterruptionEpoch.get()
        if (!isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = publicationEpoch,
                currentEpoch = mobileDataDownloadInterruptionEpoch.get()
            )
        ) {
            NPLogger.d(TAG, "移动网络下载提示统计已过期: reason=$reason")
            return@withLock
        }
        if (isDownloadClearFenceActive(context.applicationContext)) {
            return@withLock
        }
        val existingRequest = mobileDataDownloadInterruptionRequestMutable.value
        val requestScopeChanged = existingRequest != null &&
            (existingRequest.networkGeneration != networkGeneration ||
                existingRequest.batchIdentities != capturedBatchIdentities)
        val normalizedFallbackTaskCount = fallbackTaskCount.coerceAtLeast(1)
        if (
            existingRequest != null &&
                !requestScopeChanged &&
                authoritativeTaskCount == null &&
                !forceAuthoritativeRecount &&
                existingRequest.networkType == networkType &&
                normalizedFallbackTaskCount <= existingRequest.taskCount
        ) {
            if (onWifiBoundDownloadNetworkRestored(context, "dialog_recheck_$reason", networkGeneration)) {
                return@withLock
            }
            return@withLock
        }
        if (
            existingRequest == null &&
                !AppContainer.settingsRepo.mobileDataHighRiskPromptEnabledFlow.first()
        ) {
            NPLogger.d(
                TAG,
                "移动网络下载提示已关闭，任务保持等待 WIFI: reason=$reason, " +
                    "networkType=$networkType, taskCount=${fallbackTaskCount.coerceAtLeast(1)}"
            )
            return@withLock
        }
        val observedTaskCount = authoritativeTaskCount ?: when {
            forceAuthoritativeRecount ||
                existingRequest == null ||
                fallbackTaskCount > existingRequest.taskCount -> {
                observeWifiBoundMobileDataTaskCount(context)
            }

            else -> null
        }
        if (onWifiBoundDownloadNetworkRestored(context, "dialog_recheck_$reason", networkGeneration)) {
            return@withLock
        }
        if (
            !isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = publicationEpoch,
                currentEpoch = mobileDataDownloadInterruptionEpoch.get()
            ) ||
                isDownloadClearFenceActive(context.applicationContext)
        ) {
            NPLogger.d(TAG, "移动网络下载提示发布已过期: reason=$reason")
            return@withLock
        }
        val normalizedTaskCount = resolveMobileDataDownloadInterruptionTaskCount(
            existingTaskCount = existingRequest?.taskCount,
            observedTaskCount = observedTaskCount,
            fallbackTaskCount = fallbackTaskCount
        )
        if (normalizedTaskCount == 0) {
            if (existingRequest != null) {
                dismissMobileDataDownloadInterruptionRequest()
            }
            NPLogger.d(
                TAG,
                "移动网络下载提示权威重算为空，撤销提示: reason=$reason"
            )
            return@withLock
        }
        if (existingRequest != null && !requestScopeChanged) {
            if (existingRequest.taskCount != normalizedTaskCount) {
                val updatedRequest = existingRequest.copy(
                    taskCount = normalizedTaskCount
                )
                mobileDataDownloadInterruptionRequestMutable.value = updatedRequest
                if (
                    !isMobileDataDownloadInterruptionSnapshotCurrent(
                        snapshotEpoch = publicationEpoch,
                        currentEpoch = mobileDataDownloadInterruptionEpoch.get()
                    ) ||
                        isDownloadClearFenceActive(context.applicationContext)
                ) {
                    if (mobileDataDownloadInterruptionRequestMutable.value?.id == updatedRequest.id) {
                        mobileDataDownloadInterruptionRequestMutable.value = null
                    }
                    return@withLock
                }
            }
            NPLogger.d(
                TAG,
                "移动网络下载确认请求已存在，更新等待数量: reason=$reason, " +
                    "requestId=${existingRequest.id}, taskCount=$normalizedTaskCount"
            )
            return@withLock
        }
        val request = MobileDataDownloadInterruptionRequest(
            id = mobileDataInterruptionRequestIdGenerator.incrementAndGet(),
            networkType = networkType,
            taskCount = normalizedTaskCount,
            batchIdentities = capturedBatchIdentities,
            networkGeneration = networkGeneration
        )
        mobileDataDownloadInterruptionRequestMutable.value = request
        if (
            !isMobileDataDownloadInterruptionSnapshotCurrent(
                snapshotEpoch = publicationEpoch,
                currentEpoch = mobileDataDownloadInterruptionEpoch.get()
            ) ||
            isDownloadClearFenceActive(context.applicationContext)
        ) {
            if (mobileDataDownloadInterruptionRequestMutable.value?.id == request.id) {
                mobileDataDownloadInterruptionRequestMutable.value = null
            }
            return@withLock
        }
        NPLogger.w(
            TAG,
            "已发出移动网络下载确认请求: reason=$reason, networkType=$networkType, " +
                "taskCount=${request.taskCount}, requestId=${request.id}"
        )
    }
}

internal suspend fun GlobalDownloadManager.observeWifiBoundMobileDataTaskCount(context: Context): Int? {
    return runCatching {
        captureWifiBoundNetworkPolicySnapshot(
            context = context,
            candidateTasks = currentActiveNetworkPolicyTasks()
        ).taskCount
    }.onFailure { error ->
        NPLogger.w(
            TAG,
            "重算移动网络下载提示数量失败，保留当前入口计数: " +
                "reason=${error.message}",
            error
        )
    }.getOrNull()
}

internal fun GlobalDownloadManager.removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys: Set<String>) {
    taskStore.removeObsoleteWaitingNetworkTasks(recoveryCandidateKeys)
}
