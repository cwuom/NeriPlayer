package moe.ouom.neriplayer.core.download.execution.persistence

import moe.ouom.neriplayer.core.download.execution.state.planDownloadRetry
import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.stableKey

private val POST_CORE_RETRY_RECORD_STATES = setOf(
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "DEGRADED_COMPLETE"
)

internal suspend fun DownloadExecutionRoomStore.recordPostCoreRetryFailureImpl(
    operationId: String,
    stableKey: String,
    expectedAttemptId: Long?,
    errorCode: String,
    database: NeriUserDataDatabase,
    nowMs: Long
): DownloadExecutionRoomStore.PostCoreRetryRecord? {
    val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedStableKey = stableKey.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedAttemptId = expectedAttemptId?.takeIf { it > 0L }
    val normalizedErrorCode = errorCode.trim().takeIf(String::isNotBlank) ?: return null
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(normalizedOperationId) ?: return@withTransaction null
        if (
            header.stableKey != normalizedStableKey ||
                header.stopRequestedByUser ||
                header.state !in POST_CORE_RETRY_RECORD_STATES
        ) {
            return@withTransaction null
        }
        val request = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header).request
            ?: return@withTransaction null
        if (
            request.song.stableKey() != normalizedStableKey ||
                normalizedAttemptId != null &&
                request.attemptId != null &&
                request.attemptId != normalizedAttemptId
        ) {
            return@withTransaction null
        }
        // core 提交会清除传输期限，但不代表整条下载已经成功，不能因此重新给予完整重试预算
        val retryPlan = planDownloadRetry(
            currentRetryCount = header.retryCount,
            errorCode = normalizedErrorCode,
            nowMs = nowMs
        )
        val nextRetryAtMs = retryPlan.nextRetryAtMs ?: nowMs
        val changed = dao.recordPostCoreRetryFailure(
            operationId = normalizedOperationId,
            expectedState = header.state,
            expectedRetryCount = header.retryCount,
            expectedUpdatedAtMs = header.updatedAtMs,
            retryCount = retryPlan.retryCount,
            nextRetryAtMs = nextRetryAtMs,
            updatedAtMs = nowMs,
            errorCode = normalizedErrorCode
        ) > 0
        if (!changed) return@withTransaction null
        val persisted = dao.findHeader(normalizedOperationId) ?: return@withTransaction null
        DownloadExecutionRoomStore.PostCoreRetryRecord(
            retryCount = persisted.retryCount,
            nextRetryAtMs = persisted.nextRetryAtMs ?: nextRetryAtMs,
            updatedAtMs = persisted.updatedAtMs
        )
    }
}

internal suspend fun DownloadExecutionRoomStore.markPostCoreRetryExhaustedImpl(
    operationId: String,
    stableKey: String,
    expectedAttemptId: Long?,
    minimumRetryCount: Int,
    errorCode: String,
    database: NeriUserDataDatabase,
    nowMs: Long
): Boolean {
    val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank) ?: return false
    val normalizedStableKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    val normalizedAttemptId = expectedAttemptId?.takeIf { it > 0L }
    val normalizedRetryCount = minimumRetryCount.coerceAtLeast(1)
    val normalizedErrorCode = errorCode.trim().takeIf(String::isNotBlank) ?: return false
    return database.withTransaction {
        val operationDao = database.downloadOperationDao()
        val header = operationDao.findHeader(normalizedOperationId)
            ?: return@withTransaction false
        if (
            header.stableKey != normalizedStableKey ||
                header.state != "DEGRADED_COMPLETE" ||
                header.stopRequestedByUser ||
                header.retryCount < normalizedRetryCount
        ) {
            return@withTransaction false
        }
        val request = DownloadExecutionRoomStore.Access
            .readRequestFromHeader(operationDao, header)
            .request
            ?: return@withTransaction false
        if (
            request.song.stableKey() != normalizedStableKey ||
                normalizedAttemptId != null &&
                request.attemptId != null &&
                request.attemptId != normalizedAttemptId
        ) {
            return@withTransaction false
        }

        val batchDao = database.downloadBatchDao()
        val matchingMembers = batchDao.findMembersByOperation(normalizedOperationId)
            .filter { member ->
                member.stableKey == normalizedStableKey &&
                    (normalizedAttemptId == null ||
                        member.attemptId == null ||
                        member.attemptId == normalizedAttemptId)
            }
        if (matchingMembers.any { member ->
                member.terminalBits != DownloadBatchMemberTerminal.NONE &&
                    member.terminalBits != DownloadBatchMemberTerminal.FAILED
            }
        ) {
            return@withTransaction false
        }
        matchingMembers
            .filter { member -> member.terminalBits == DownloadBatchMemberTerminal.NONE }
            .forEach { member ->
                val batch = batchDao.findBatchById(member.batchId)
                    ?: error("post-core batch missing: ${member.batchId}")
                check(batch.stateBits and DownloadBatchState.CLEARING == 0)
                check(batch.stateBits and DownloadBatchState.CANCELLED == 0)
                val changed = batchDao.markMemberTerminalCAS(
                    batchId = member.batchId,
                    stableKey = member.stableKey,
                    operationId = normalizedOperationId,
                    attemptId = member.attemptId ?: normalizedAttemptId,
                    terminalBits = DownloadBatchMemberTerminal.FAILED,
                    fraction = member.maxFractionMilli,
                    nowMs = nowMs
                )
                check(changed == 1) {
                    "post-core batch failure CAS rejected: ${member.batchId}/${member.stableKey}"
                }
            }

        val transitioned = operationDao.transitionPostCoreRetryExhausted(
            operationId = normalizedOperationId,
            stableKey = normalizedStableKey,
            minimumRetryCount = normalizedRetryCount,
            expectedUpdatedAtMs = header.updatedAtMs,
            updatedAtMs = nowMs,
            errorCode = normalizedErrorCode
        )
        check(transitioned == 1) {
            "post-core retry exhaustion CAS rejected: $normalizedOperationId"
        }
        matchingMembers.map { member -> member.batchId }.distinct().forEach { batchId ->
            batchDao.findBatchById(batchId)?.let { batch ->
                batchDao.markCompletedIfAllMembersTerminal(
                    batchId = batch.batchId,
                    generation = batch.generation,
                    nowMs = nowMs
                )
            }
        }
        operationDao.deleteHostAdmission(normalizedOperationId)
        true
    }
}

internal suspend fun DownloadExecutionRoomStore.repairPrematurePostCoreBatchCompletionsImpl(
    operationIds: Collection<String>,
    database: NeriUserDataDatabase,
    nowMs: Long
): Int {
    val normalizedIds = operationIds.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (normalizedIds.isEmpty()) return 0
    return normalizedIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE)
        .sumOf { chunk ->
            database.withTransaction {
                val dao = database.downloadBatchDao()
                val repaired = dao.clearPrematurePostCoreCompletions(
                    operationIds = chunk,
                    nowMs = nowMs
                )
                dao.reopenBatchesForPostCoreOperations(
                    operationIds = chunk,
                    nowMs = nowMs
                )
                repaired
            }
        }
}
