package moe.ouom.neriplayer.core.download.execution.persistence

import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.host.normalizeDownloadOperationId
import moe.ouom.neriplayer.core.download.execution.state.DownloadOperationState
import moe.ouom.neriplayer.core.download.execution.state.isRetryDeadlineReady
import moe.ouom.neriplayer.core.download.execution.state.planDownloadRetry
import moe.ouom.neriplayer.core.download.execution.state.isAutomaticDownloadRetryExhausted
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.CachedNetworkPolicy
import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.model.stableKey

internal fun DownloadExecutionRoomStore.canStartBatchForCurrentNetworkImpl(
    batch: DownloadBatchEntity,
    currentNetworkGeneration: Long?
): Boolean {
    if (batch.stateBits and DownloadBatchState.CLEARING != 0) return false
    val hasMobileDataAllowance =
        batch.stateBits and DownloadBatchState.USER_MOBILE_ALLOWED != 0
    if (hasMobileDataAllowance) {
        // 许可属于这批任务，网络重新连接或进程重启不能撤销用户已确认的继续意图
        if (currentNetworkGeneration != null && currentNetworkGeneration < 0L) return false
    }
    if (batch.stateBits and DownloadBatchState.NETWORK_WAIT != 0) return false
    return true
}

internal suspend fun DownloadExecutionRoomStore.upsertImpl(
    context: Context,
    request: DownloadExecutionRequest,
    state: String,
    queueOrder: Int = 0,
    createdAtMs: Long? = null,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
) {
    val persistedNetworkPolicy = database.withTransaction {
        val requestedCreatedAtMs = createdAtMs ?: System.currentTimeMillis()
        val song = request.song
        val dao = database.downloadOperationDao()
        val existingHeader = dao.findHeader(request.operationId)
        val payloadUpdatedAtMs = nextPayloadUpdatedAt(
            previousUpdatedAtMs = existingHeader?.updatedAtMs,
            requestedAtMs = requestedCreatedAtMs
        )
        val existing = existingHeader?.let { header ->
            readSourceHintJson(dao, header)?.let { sourceHintJson ->
                header.toEntity(sourceHintJson)
            }
        }
        val restartForNewAttempt = shouldRestartOperation(
            existingState = existingHeader?.state,
            requestedState = state,
            userInitiated = request.userInitiated
        )
        val existingRequest = existing?.let(::requestFromEntity)
        val preservedBatchId = request.batchId ?: existingRequest?.batchId ?: existingHeader?.batchId
        val preservedBatchGeneration = request.batchGeneration
            ?: existingRequest?.batchGeneration
            ?: existingHeader?.batchGeneration
        val effectiveUserInitiated = request.userInitiated ||
            existingRequest?.userInitiated == true
        val batchAllowsMobile = preservedBatchId?.let { batchId ->
            preservedBatchGeneration?.let { generation ->
                database.downloadBatchDao().findBatch(batchId, generation)
                    ?.stateBits?.and(DownloadBatchState.USER_MOBILE_ALLOWED) == DownloadBatchState.USER_MOBILE_ALLOWED
            }
        } == true
        val requestWithMonotonicIntent = request.copy(
            // 迟到的排队快照不能撤销执行器已经绑定的代次
            attemptId = maxOf(request.attemptId ?: 0L, existingRequest?.attemptId ?: 0L)
                .takeIf { it > 0L },
            userInitiated = effectiveUserInitiated,
            requiresFreshTransfer = request.requiresFreshTransfer ||
                existingRequest?.requiresFreshTransfer == true,
            requiresWifiNetwork = request.requiresWifiNetwork && !batchAllowsMobile
        )
        val persistedRequest = when {
            restartForNewAttempt -> requestWithMonotonicIntent.copy(
                artifactLeaseId = UUID.randomUUID().toString(),
                batchId = preservedBatchId,
                batchGeneration = preservedBatchGeneration
            )
            existingRequest != null -> requestWithMonotonicIntent.copy(
                artifactLeaseId = existingRequest.artifactLeaseId,
                batchId = preservedBatchId,
                batchGeneration = preservedBatchGeneration
            )
            else -> requestWithMonotonicIntent
        }
        dao.upsert(
            DownloadOperationEntity(
                operationId = request.operationId,
                stableKey = song.stableKey(),
                libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(context),
                // 重新排队不能把已有 operation 的持久状态倒退
                state = if (restartForNewAttempt) state else existingHeader?.state ?: state,
                queueOrder = if (queueOrder == 0) {
                    existingHeader?.queueOrder ?: 0
                } else {
                    queueOrder
                },
                sourceHintJson = requestToJson(persistedRequest).toString(),
                stagingDirName = request.operationId,
                bytesWritten = existingHeader?.bytesWritten ?: 0L,
                totalBytes = existingHeader?.totalBytes,
                resumeJson = readResumeJson(dao, existingHeader),
                retryCount = existingHeader?.retryCount ?: 0,
                // 更新载荷不能清掉持久重试期限，只有明确重开任务才能重置
                nextRetryAtMs = existingHeader?.nextRetryAtMs?.takeUnless { restartForNewAttempt },
                lastErrorCode = existingHeader?.lastErrorCode,
                stopRequestedByUser = if (restartForNewAttempt) {
                    false
                } else {
                    existingHeader?.stopRequestedByUser ?: false
                },
                createdAtMs = existingHeader?.createdAtMs ?: requestedCreatedAtMs,
                updatedAtMs = payloadUpdatedAtMs,
                hostProcessToken = existingHeader?.hostProcessToken,
                hostAdmittedAtMs = existingHeader?.hostAdmittedAtMs,
                batchId = persistedRequest.batchId,
                batchGeneration = persistedRequest.batchGeneration
            )
        )
        CachedNetworkPolicy(
            requiresWifiNetwork = persistedRequest.requiresWifiNetwork,
            updatedAtMs = payloadUpdatedAtMs
        )
    }
    networkPolicyByOperationId.compute(request.operationId) { _, current ->
        if (current == null || persistedNetworkPolicy.updatedAtMs >= current.updatedAtMs) {
            persistedNetworkPolicy
        } else {
            current
        }
    }
}

internal fun DownloadExecutionRoomStore.cacheNetworkPolicyImpl(
    operationId: String,
    requiresWifiNetwork: Boolean,
    updatedAtMs: Long
) {
    if (operationId.isNotBlank()) {
        val policy = CachedNetworkPolicy(
            requiresWifiNetwork = requiresWifiNetwork,
            updatedAtMs = updatedAtMs
        )
        networkPolicyByOperationId.compute(operationId) { _, current ->
            if (current == null || updatedAtMs >= current.updatedAtMs) policy else current
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.updateStateImpl(
    context: Context,
    operationId: String,
    state: String,
    errorCode: String? = null,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis(),
    expectedAttemptId: Long? = null
): Boolean {
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val current = dao.findHeader(operationId) ?: return@withTransaction false
        val request = if (expectedAttemptId != null) {
            DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, current).request
                ?: return@withTransaction false
        } else null
        if (expectedAttemptId != null && request?.attemptId != expectedAttemptId) {
            return@withTransaction false
        }
        val nextState = resolveDownloadOperationState(current.state, state)
            ?: return@withTransaction false
        val settlesBatchCompletion = nextState == DownloadOperationState.COMPLETED.wireName ||
            nextState == DownloadOperationState.FINALIZED.wireName
        if (nextState == current.state) {
            if (!current.stopRequestedByUser && settlesBatchCompletion) {
                markMembersCompletedForOperationInTransaction(
                    database = database,
                    operationId = operationId,
                    stableKey = current.stableKey,
                    attemptId = null
                )
            }
            return@withTransaction !current.stopRequestedByUser
        }
        if (nextState == DownloadOperationState.RETRYABLE.wireName) {
            val retryPlan = planDownloadRetry(
                currentRetryCount = current.retryCount,
                errorCode = errorCode,
                nowMs = nowMs
            )
            if (isAutomaticDownloadRetryExhausted(errorCode, retryPlan.retryCount)) {
                val changed = dao.transitionState(
                    operationId, listOf(current.state), "INVALID", nowMs,
                    "${errorCode}_RETRY_EXHAUSTED"
                ) > 0
                if (changed) {
                    // 失败终态和批次计数一起提交，重启后不能再调度这一代任务
                    markBatchMembersForOperation(
                        context, operationId, current.stableKey, expectedAttemptId,
                        DownloadBatchMemberTerminal.FAILED, database = database, nowMs = nowMs
                    )
                    dao.deleteHostAdmission(operationId)
                }
                return@withTransaction changed
            }
            return@withTransaction dao.transitionToRetryable(
                operationId = operationId,
                expectedStates = listOf(current.state),
                expectedRetryCount = current.retryCount,
                expectedUpdatedAtMs = current.updatedAtMs,
                retryCount = retryPlan.retryCount,
                nextRetryAtMs = retryPlan.nextRetryAtMs,
                updatedAtMs = nowMs,
                errorCode = errorCode
            ) > 0
        }
        val changed = dao.transitionState(
            operationId = operationId,
            expectedStates = listOf(current.state),
            state = nextState,
            updatedAtMs = nowMs,
            errorCode = errorCode
        ) > 0
        if (changed && settlesBatchCompletion) {
            // 最终 operation 和批次成员必须原子落库，避免进程恰好在
            // UI 异步终态回写前退出后留下永久待处理成员
            markMembersCompletedForOperationInTransaction(
                database = database,
                operationId = operationId,
                stableKey = current.stableKey,
                attemptId = null
            )
        }
        changed
    }
}

internal suspend fun DownloadExecutionRoomStore.markAlreadyDownloadedCompletedImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    expectedAttemptId: Long? = null,
    errorCode: String = "DOWNLOAD_ALREADY_PRESENT",
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    val normalizedOperationId = normalizeDownloadOperationId(operationId) ?: return false
    val normalizedStableKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    val normalizedExpectedAttemptId = expectedAttemptId?.takeIf { it > 0L }
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(normalizedOperationId)
            ?: return@withTransaction false
        if (
            header.stableKey != normalizedStableKey ||
                header.stopRequestedByUser
        ) {
            return@withTransaction false
        }
        if (
            header.state == DownloadOperationState.COMPLETED.wireName ||
                header.state == DownloadOperationState.FINALIZED.wireName
        ) {
            val request = readRequestFromHeader(dao, header).request
            if (
                request?.song?.stableKey() == normalizedStableKey &&
                    (normalizedExpectedAttemptId == null ||
                        request.attemptId == null ||
                        request.attemptId == normalizedExpectedAttemptId)
            ) {
                markMembersCompletedForOperationInTransaction(
                    database = database,
                    operationId = normalizedOperationId,
                    stableKey = normalizedStableKey,
                    attemptId = request.attemptId ?: normalizedExpectedAttemptId
                )
                // 终态 operation 不再需要占用宿主准入；即使是旧进程留下的
                // handoff 记录也要在本事务内释放，避免新任务等待租约过期
                dao.deleteHostAdmission(normalizedOperationId)
                return@withTransaction true
            }
            return@withTransaction false
        }
        if (header.state !in DIRECT_CACHED_COMPLETION_SOURCE_STATES) {
            return@withTransaction false
        }
        val decoded = readRequestFromHeader(dao, header)
        val request = decoded.request ?: run {
            if (decoded.payloadWasRead) {
                invalidateMalformedPayloadInTransaction(database, header)
            }
            return@withTransaction false
        }
        if (
            request.song.stableKey() != normalizedStableKey ||
                normalizedExpectedAttemptId != null &&
                    request.attemptId != null &&
                    request.attemptId != normalizedExpectedAttemptId
        ) {
            return@withTransaction false
        }
        val nextState = resolveDownloadOperationState(
            currentState = header.state,
            requestedState = DownloadOperationState.COMPLETED.wireName
        ) ?: return@withTransaction false
        val changed = dao.transitionDirectCachedStateAtVersion(
            operationId = normalizedOperationId,
            stableKey = normalizedStableKey,
            expectedStates = listOf(header.state),
            expectedUpdatedAtMs = header.updatedAtMs,
            state = nextState,
            updatedAtMs = nowMs,
            errorCode = errorCode
        ) > 0
        if (!changed) {
            val settledState = dao.findState(normalizedOperationId)
            if (settledState in setOf(
                DownloadOperationState.COMPLETED.wireName,
                DownloadOperationState.FINALIZED.wireName
            )) {
                dao.deleteHostAdmission(normalizedOperationId)
                return@withTransaction true
            }
            return@withTransaction false
        }
        markMembersCompletedForOperationInTransaction(
            database = database,
            operationId = normalizedOperationId,
            stableKey = normalizedStableKey,
            attemptId = request.attemptId ?: normalizedExpectedAttemptId
        )
        // 收口成功后立即释放旧宿主准入，下一首可以马上补位
        dao.deleteHostAdmission(normalizedOperationId)
        true
    }
}

internal suspend fun DownloadExecutionRoomStore.reopenCorePublicationRecoveryImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    errorCode: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    if (operationId.isBlank()) return false
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(operationId) ?: return@withTransaction false
        if (
            header.stableKey != normalizedKey ||
                header.stopRequestedByUser ||
                header.state !in setOf("COMPLETED", "FINALIZED")
        ) {
            return@withTransaction false
        }
        dao.transitionState(
            operationId = operationId,
            expectedStates = listOf(header.state),
            state = "DEGRADED_COMPLETE",
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
        ) > 0
    }
}

internal suspend fun DownloadExecutionRoomStore.reopenMissingPostCoreArtifactForFreshTransferImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    expectedAttemptId: Long?,
    errorCode: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    if (operationId.isBlank()) return false
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(operationId) ?: return@withTransaction false
        if (
            header.stableKey != normalizedKey ||
                header.stopRequestedByUser ||
                header.state !in MISSING_POST_CORE_ARTIFACT_REOPEN_STATES
        ) {
            return@withTransaction false
        }
        val request = readRequestFromHeader(dao, header).request
            ?: return@withTransaction false
        if (
            request.song.stableKey() != normalizedKey ||
                expectedAttemptId != null && request.attemptId != expectedAttemptId
        ) {
            return@withTransaction false
        }
        dao.transitionState(
            operationId = operationId,
            expectedStates = MISSING_POST_CORE_ARTIFACT_REOPEN_STATES,
            state = "RUNNING",
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
        ) > 0
    }
}

internal suspend fun DownloadExecutionRoomStore.markScheduleRejectedRetryableImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    errorCode: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val current = dao.findHeader(operationId) ?: return@withTransaction false
        if (
            current.stableKey != normalizedKey ||
                current.stopRequestedByUser ||
                current.state !in REUSABLE_OPERATION_STATES
        ) {
            return@withTransaction false
        }
        if (current.state == DownloadOperationState.RETRYABLE.wireName) {
            return@withTransaction true
        }
        val retryPlan = planDownloadRetry(
            currentRetryCount = current.retryCount,
            errorCode = errorCode,
            nowMs = nowMs
        )
        dao.transitionToRetryable(
            operationId = operationId,
            expectedStates = listOf(current.state),
            expectedRetryCount = current.retryCount,
            expectedUpdatedAtMs = current.updatedAtMs,
            retryCount = retryPlan.retryCount,
            nextRetryAtMs = retryPlan.nextRetryAtMs,
            updatedAtMs = nowMs,
            errorCode = errorCode
        ) > 0
    }
}

internal suspend fun DownloadExecutionRoomStore.markWaitingForStorageMutationImpl(
    context: Context,
    operationId: String,
    errorCode: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    // 复用带 CAS 的统一状态机，不能让空间等待把已经提交的核心文件降级
    return updateState(
        context = context,
        operationId = operationId,
        state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
        errorCode = errorCode,
        database = database
    )
}

internal suspend fun DownloadExecutionRoomStore.promoteWaitingStorageMutationImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    val libraryId = currentLibraryId(context)
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(operationId) ?: return@withTransaction false
        if (
            header.stableKey != normalizedKey ||
                header.state != WAITING_STORAGE_MUTATION_OPERATION_STATE ||
                header.stopRequestedByUser
        ) {
            return@withTransaction false
        }
        val decoded = readRequestFromHeader(dao, header)
        val request = decoded.request ?: run {
            if (decoded.payloadWasRead) {
                invalidateMalformedPayloadInTransaction(database, header)
            }
            return@withTransaction false
        }
        if (request.song.stableKey() != normalizedKey) {
            invalidateMalformedPayloadInTransaction(database, header)
            return@withTransaction false
        }
        dao.promoteWaitingStorageMutation(
            operationId = operationId,
            libraryId = libraryId,
            stableKey = normalizedKey,
            updatedAtMs = System.currentTimeMillis()
        ) > 0
    }
}

internal suspend fun DownloadExecutionRoomStore.markStagingPreparedImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(operationId) ?: return@withTransaction false
        if (header.stableKey != normalizedKey) return@withTransaction false
        val request = readRequestFromHeader(dao, header).request ?: return@withTransaction false
        if (request.song.stableKey() != normalizedKey) return@withTransaction false
        if (request.preserveStaging) return@withTransaction true
        dao.updateRequestPayload(
            operationId = operationId,
            stableKey = normalizedKey,
            sourceHintJson = requestToJson(
                request.copy(preserveStaging = true)
            ).toString(),
            updatedAtMs = nextPayloadUpdatedAt(
                previousUpdatedAtMs = header.updatedAtMs
            )
        ) > 0
    }
}

internal suspend fun DownloadExecutionRoomStore.promoteUserInitiatedOperationImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): DownloadExecutionRequest? {
    val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return null
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(normalizedOperationId) ?: return@withTransaction null
        if (
            header.stableKey != normalizedKey ||
                header.state !in IN_FLIGHT_OPERATION_STATES + REUSABLE_OPERATION_STATES +
                    WAITING_STORAGE_MUTATION_OPERATION_STATE ||
                header.stopRequestedByUser
        ) {
            return@withTransaction null
        }
        val decoded = readRequestFromHeader(dao, header)
        val request = decoded.request ?: run {
            if (decoded.payloadWasRead) {
                invalidateMalformedPayloadInTransaction(database, header)
            }
            return@withTransaction null
        }
        if (request.song.stableKey() != normalizedKey) {
            invalidateMalformedPayloadInTransaction(database, header)
            return@withTransaction null
        }
        if (request.userInitiated) return@withTransaction request
        val promoted = request.copy(userInitiated = true)
        if (
            dao.updateRequestPayload(
                operationId = normalizedOperationId,
                stableKey = normalizedKey,
                sourceHintJson = requestToJson(promoted).toString(),
                updatedAtMs = nextPayloadUpdatedAt(
                    previousUpdatedAtMs = header.updatedAtMs
                )
            ) <= 0
        ) {
            return@withTransaction null
        }
        promoted
    }
}

internal suspend fun DownloadExecutionRoomStore.ensureAttemptIdImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    attemptId: Long,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    if (operationId.isBlank() || attemptId <= 0L) return false
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(operationId) ?: return@withTransaction false
        if (header.stableKey != normalizedKey) return@withTransaction false
        val request = readRequestFromHeader(dao, header).request ?: return@withTransaction false
        if (request.song.stableKey() != normalizedKey) return@withTransaction false
        if (request.attemptId == attemptId) return@withTransaction true
        if (request.attemptId?.takeIf { it > 0L } != null) return@withTransaction false
        dao.updateRequestPayload(
            operationId = operationId,
            stableKey = normalizedKey,
            sourceHintJson = requestToJson(request.copy(attemptId = attemptId)).toString(),
            updatedAtMs = nextPayloadUpdatedAt(
                previousUpdatedAtMs = header.updatedAtMs
            )
        ) > 0
    }
}

internal suspend fun DownloadExecutionRoomStore.tryStartImpl(
    context: Context,
    operationId: String,
    allowExistingRunning: Boolean = false,
    currentNetworkGeneration: Long? = null,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        var target = dao.findHeader(operationId) ?: return@withTransaction false
        if (target.libraryId != currentLibraryId(context)) {
            if (
                target.stopRequestedByUser ||
                    target.state !in ACTIVE_OPERATION_STATES
            ) {
                return@withTransaction false
            }
            val rebound = dao.rehomeOperationLibrary(
                operationId = operationId,
                stableKey = target.stableKey,
                libraryId = currentLibraryId(context),
                states = listOf(target.state),
                updatedAtMs = System.currentTimeMillis()
            ) > 0
            if (!rebound) return@withTransaction false
            target = dao.findHeader(operationId) ?: return@withTransaction false
        }
        if (target.stopRequestedByUser) return@withTransaction false
        if (
            target.state == DownloadOperationState.RETRYABLE.wireName &&
                !isRetryDeadlineReady(target.nextRetryAtMs, nowMs)
        ) {
            return@withTransaction false
        }
        val expectedStates = buildList {
            add("PENDING_QUEUE")
            add("QUEUED")
            add("RETRYABLE")
            if (allowExistingRunning) {
                addAll(INTERRUPTED_DOWNLOAD_OPERATION_STATES)
            }
        }
        if (target.state !in expectedStates) return@withTransaction false
        if (hasOtherValidWaitingStorageMutation(database, target)) {
            return@withTransaction false
        }
        val batchId = target.batchId
        val batchGeneration = target.batchGeneration
        if (batchId != null || batchGeneration != null) {
            if (batchId == null || batchGeneration == null) {
                return@withTransaction false
            }
            val batch = database.downloadBatchDao()
                .findBatch(batchId, batchGeneration)
                ?: return@withTransaction false
            // 网络代际和 RUNNING 转换必须在同一事务内检查，避免旧确认越过新等待态
            if (!canStartBatchForCurrentNetwork(batch, currentNetworkGeneration)) {
                return@withTransaction false
            }
        }

        val contenders = dao.findAllHeadersByStableKey(
            libraryId = target.libraryId,
            stableKey = target.stableKey,
            states = EXECUTION_CONVERGENCE_STATES
        ).filterNot(DownloadOperationHeaderRow::stopRequestedByUser)
        val validContenders = buildList {
            for (header in contenders) {
                val decoded = readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) {
                        invalidateMalformedPayloadInTransaction(database, header)
                    }
                } else {
                    add(header to request)
                }
            }
        }
        val leasedIds = validContenders
            .map { (entity, _) -> entity.libraryId }
            .distinct()
            .mapNotNull { libraryId ->
                database.managedDownloadArtifactDao()
                    .find(libraryId, target.stableKey)
                    ?.leaseId
            }
            .toSet()
        val winner = validContenders.maxWithOrNull(
            compareBy<Pair<DownloadOperationHeaderRow, DownloadExecutionRequest>> { (_, request) ->
                request.artifactLeaseId in leasedIds
            }.thenBy { (header, _) -> executionConvergencePriority(header.state) }
                .thenBy { (header, _) -> header.updatedAtMs }
                .thenBy { (header, _) -> header.createdAtMs }
                .thenBy { (header, _) -> header.operationId }
        ) ?: return@withTransaction false

        if (winner.first.operationId != operationId) {
            // 另一个宿主可能正在收尾。此时不能把刚排队的替代请求
            // 提前标成 INVALID，否则取消竞态会永久吞掉新任务
            return@withTransaction false
        }
        // 只有当前 operation 已赢得稳定键仲裁时，才清理尚未开始的重复行
        validContenders
            .filter { (header, _) ->
                header.operationId != winner.first.operationId &&
                    header.state in REUSABLE_OPERATION_STATES
            }
            .forEach { (header, _) ->
                dao.transitionState(
                    operationId = header.operationId,
                    expectedStates = listOf(header.state),
                    state = "INVALID",
                    updatedAtMs = System.currentTimeMillis(),
                    errorCode = "DUPLICATE_STABLE_KEY_OPERATION"
                )
            }
        if (target.state in DURABLE_CORE_EXECUTION_STATES) {
            return@withTransaction true
        }
        dao.transitionState(
            operationId = operationId,
            expectedStates = expectedStates,
            state = "RUNNING",
            updatedAtMs = System.currentTimeMillis(),
            errorCode = null
        ) > 0
    }
}
