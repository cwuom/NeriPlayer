package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.StateEntry
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.CoreCommitJournalRecovery
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.OperationIdentity
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.DownloadBatchIdentity
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.DownloadBatchClearCapture
import android.content.Context
import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.stableKey

internal suspend fun DownloadExecutionRoomStore.updateBatchMembersForOperationImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    attemptId: Long?,
    fractionMilli: Int,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val normalizedOperationId = normalizeDownloadOperationId(operationId) ?: return 0
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return 0
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        val incomingAttemptId = attemptId?.takeIf { it > 0L }
        dao.findMembersByOperation(normalizedOperationId)
            .filter { member ->
                member.stableKey == normalizedKey &&
                    (incomingAttemptId == null ||
                        member.attemptId == null ||
                        member.attemptId == incomingAttemptId)
            }
            .sumOf { member ->
                // operation 身份已经限定了批次边界；恢复回调缺少 attempt 时，
                // 使用成员自身的 attempt 让 DAO 的 CAS 仍能命中旧版行
                val effectiveAttemptId = member.attemptId ?: incomingAttemptId
                dao.updateMemberFractionMaxCAS(
                    batchId = member.batchId,
                    stableKey = member.stableKey,
                    operationId = normalizedOperationId,
                    attemptId = effectiveAttemptId,
                    fraction = fractionMilli.coerceIn(0, 1000),
                    nowMs = nowMs
                )
            }
    }
}

internal suspend fun DownloadExecutionRoomStore.markBatchMembersForOperationImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    attemptId: Long?,
    terminalBits: Int,
    fractionMilli: Int = 0,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    require(terminalBits in DownloadBatchMemberTerminal.VALID_BITS)
    require(terminalBits != DownloadBatchMemberTerminal.NONE)
    val normalizedOperationId = normalizeDownloadOperationId(operationId) ?: return 0
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return 0
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        val incomingAttemptId = attemptId?.takeIf { it > 0L }
        var changed = 0
        dao.findMembersByOperation(normalizedOperationId)
            .filter { member ->
                member.stableKey == normalizedKey &&
                    (incomingAttemptId == null ||
                        member.attemptId == null ||
                        member.attemptId == incomingAttemptId)
            }
            .forEach { member ->
                // operation 身份已经限定了批次边界；恢复回调缺少 attempt 时，
                // 使用成员自身的 attempt 让 DAO 的 CAS 仍能命中旧版行
                val effectiveAttemptId = member.attemptId ?: incomingAttemptId
                changed += dao.markMemberTerminalCAS(
                    batchId = member.batchId,
                    stableKey = member.stableKey,
                    operationId = normalizedOperationId,
                    attemptId = effectiveAttemptId,
                    terminalBits = terminalBits,
                    fraction = if (terminalBits == DownloadBatchMemberTerminal.COMPLETED) {
                        1000
                    } else {
                        maxOf(member.maxFractionMilli, fractionMilli.coerceIn(0, 1000))
                    },
                    nowMs = nowMs
                )
                dao.findBatchById(member.batchId)?.let { batch ->
                    dao.markCompletedIfAllMembersTerminal(
                        batchId = batch.batchId,
                        generation = batch.generation,
                        nowMs = nowMs
                    )
                }
            }
        changed
    }
}

internal suspend fun DownloadExecutionRoomStore.beginBatchClearImpl(
    context: Context,
    clearEpoch: Long,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): DownloadBatchClearCapture {
    if (clearEpoch <= 0L) {
        return DownloadBatchClearCapture(
            identities = emptyList(),
            operationIdentities = emptyList(),
            stableKeys = emptySet()
        )
    }
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        val identities = linkedSetOf<DownloadBatchIdentity>()
        val operationIdentities = linkedMapOf<String, OperationIdentity>()
        val stableKeys = linkedSetOf<String>()
        dao.findBatchesForClear(clearEpoch).forEach { batch ->
            val identity = DownloadBatchIdentity(
                batchId = batch.batchId,
                generation = batch.generation
            )
            if (
                dao.markBatchClearingCAS(
                    batchId = batch.batchId,
                    generation = batch.generation,
                    clearEpoch = clearEpoch,
                    nowMs = nowMs
                ) <= 0
            ) {
                return@forEach
            }
            val members = dao.listMembers(batch.batchId)
            members.filter { member ->
                member.terminalBits == DownloadBatchMemberTerminal.NONE
            }.forEach { member ->
                stableKeys += member.stableKey
                member.operationId
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { operationId ->
                        operationIdentities.putIfAbsent(
                            operationId,
                            OperationIdentity(
                                operationId = operationId,
                                stableKey = member.stableKey,
                                createdAtMs = batch.createdAtMs
                            )
                        )
                    }
            }
            dao.markMembersCancelled(batch.batchId, nowMs)
            identities += identity
        }
        DownloadBatchClearCapture(
            identities = identities.toList(),
            operationIdentities = operationIdentities.values.toList(),
            stableKeys = stableKeys
        )
    }
}

internal suspend fun DownloadExecutionRoomStore.finalizeBatchClearImpl(
    context: Context,
    identities: Collection<DownloadBatchIdentity>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    val distinctIdentities = identities.distinct()
    if (distinctIdentities.isEmpty()) return true
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        distinctIdentities.all { identity ->
            dao.markMembersCancelled(identity.batchId, nowMs)
            dao.finalizeBatchClearingCAS(
                batchId = identity.batchId,
                generation = identity.generation,
                nowMs = nowMs
            )
            val batch = dao.findBatch(identity.batchId, identity.generation)
            batch == null || batch.stateBits and DownloadBatchState.CLEARING == 0
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.markBatchMembersCancelledImpl(
    context: Context,
    identity: DownloadBatchIdentity,
    stableKeys: Collection<String>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    if (keys.isEmpty()) return 0
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        if (dao.findBatch(identity.batchId, identity.generation) == null) {
            return@withTransaction 0
        }
        keys.sumOf { stableKey ->
            dao.markMemberCancelled(
                batchId = identity.batchId,
                stableKey = stableKey,
                nowMs = nowMs
            )
        }.also {
            dao.markCompletedIfAllMembersTerminal(
                batchId = identity.batchId,
                generation = identity.generation,
                nowMs = nowMs
            )
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.markAllOpenBatchesCancelledImpl(
    context: Context,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int = database.withTransaction {
    val dao = database.downloadBatchDao()
    dao.markMembersCancelledForAllOpenBatches(nowMs)
    dao.markAllOpenBatchesCancelled(nowMs)
}

internal suspend fun DownloadExecutionRoomStore.markBatchesNetworkWaitingImpl(
    context: Context,
    stableKeys: Collection<String>,
    networkGeneration: Long,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) return 0
    require(networkGeneration >= 0L) { "networkGeneration must not be negative" }
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        var changed = 0
        dao.findOpenBatches().forEach { batch ->
            if (dao.listMembers(batch.batchId).none { member -> member.stableKey in keys }) {
                return@forEach
            }
            changed += dao.markNetworkWaitingCAS(
                batchId = batch.batchId,
                generation = batch.generation,
                networkGeneration = networkGeneration,
                expectedNetworkGeneration = batch.networkGeneration,
                nowMs = nowMs
            )
        }
        changed
    }
}

internal suspend fun DownloadExecutionRoomStore.markBatchesNetworkWaitingImpl(
    context: Context,
    identities: Collection<DownloadBatchIdentity>,
    networkGeneration: Long,
    expectedNetworkGeneration: Long?,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val distinctIdentities = identities.distinct()
    if (distinctIdentities.isEmpty()) return 0
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        distinctIdentities.sumOf { identity ->
            val batch = dao.findBatch(identity.batchId, identity.generation)
                ?: return@sumOf 0
            if (expectedNetworkGeneration != null &&
                batch.networkGeneration != expectedNetworkGeneration
            ) {
                return@sumOf 0
            }
            dao.markNetworkWaitingCAS(
                batchId = identity.batchId,
                generation = identity.generation,
                networkGeneration = networkGeneration,
                expectedNetworkGeneration = batch.networkGeneration,
                nowMs = nowMs
            )
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.clearAllOpenBatchNetworkPolicyFencesImpl(
    context: Context,
    networkGeneration: Long,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    require(networkGeneration >= 0L) { "networkGeneration must not be negative" }
    return database.downloadBatchDao()
        .clearAllOpenNetworkPolicyFencesAtOrBeforeGeneration(
            networkGeneration = networkGeneration,
            nowMs = nowMs
        )
}

internal suspend fun DownloadExecutionRoomStore.allowBatchesMobileDataImpl(
    context: Context,
    identities: Collection<DownloadBatchIdentity>,
    expectedNetworkGeneration: Long,
    networkGeneration: Long,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val distinctIdentities = identities.distinct()
    if (distinctIdentities.isEmpty()) return 0
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        distinctIdentities.sumOf { identity ->
            dao.allowMobileDataCAS(
                batchId = identity.batchId,
                generation = identity.generation,
                expectedNetworkGeneration = expectedNetworkGeneration,
                networkGeneration = networkGeneration,
                nowMs = nowMs
            )
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.markInitialBatchMembersCompletedImpl(
    context: Context,
    identity: DownloadBatchIdentity,
    stableKeys: Collection<String>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    if (keys.isEmpty()) return 0
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        var updated = 0
        keys.forEach { stableKey ->
            updated += dao.markInitialMemberTerminalCAS(
                batchId = identity.batchId,
                stableKey = stableKey,
                terminalBits = DownloadBatchMemberTerminal.COMPLETED,
                fraction = 1000,
                nowMs = nowMs
            )
        }
        dao.markCompletedIfAllMembersTerminal(
            batchId = identity.batchId,
            generation = identity.generation,
            nowMs = nowMs
        )
        updated
    }
}

internal suspend fun DownloadExecutionRoomStore.reconcileCoreCommitJournalImpl(
    context: Context,
    operationId: String,
    stableKey: String? = null,
    expectedAttemptId: Long? = null,
    coreMetadataDurable: Boolean = false,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): CoreCommitJournalRecovery {
    val normalizedOperationId = operationId.trim().takeIf(String::isNotBlank)
        ?: return CoreCommitJournalRecovery(
            outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
            state = null,
            stopRequestedByUser = false
        )
    val normalizedStableKey = stableKey?.trim()?.takeIf(String::isNotBlank)
    val normalizedAttemptId = expectedAttemptId?.takeIf { it > 0L }
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        var current = dao.findHeader(normalizedOperationId)
            ?: return@withTransaction CoreCommitJournalRecovery(
                outcome = CoreCommitJournalRecovery.Outcome.MISSING,
                state = null,
                stopRequestedByUser = false
            )
        repeat(2) {
            if (
                normalizedStableKey != null &&
                    current.stableKey != normalizedStableKey
            ) {
                return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                    state = current.state,
                    stopRequestedByUser = current.stopRequestedByUser
                )
            }
            val decoded = readRequestFromHeader(dao, current)
            val request = decoded.request ?: run {
                if (decoded.payloadWasRead) {
                    invalidateMalformedPayloadInTransaction(database, current)
                }
                return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                    state = current.state,
                    stopRequestedByUser = current.stopRequestedByUser
                )
            }
            val requestStableKey = request.song.stableKey()
            if (
                requestStableKey != current.stableKey ||
                    normalizedStableKey != null && requestStableKey != normalizedStableKey ||
                    normalizedAttemptId != null &&
                        request.attemptId != null &&
                        request.attemptId != normalizedAttemptId
            ) {
                return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                    state = current.state,
                    stopRequestedByUser = current.stopRequestedByUser
                )
            }
            if (current.state in CORE_COMMITTED_STATES) {
                return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.COMMITTED,
                    state = current.state,
                    stopRequestedByUser = current.stopRequestedByUser
                )
            }
            if (current.state in CORE_COMMIT_BLOCKED_STATES) {
                return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                    state = current.state,
                    stopRequestedByUser = current.stopRequestedByUser
                )
            }
            if (
                current.stopRequestedByUser &&
                    current.state != "COMMITTING"
            ) {
                return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                    state = current.state,
                    stopRequestedByUser = true
                )
            }
            if (current.state == "COMMITTING") {
                if (!coreMetadataDurable) {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.PREPARED,
                        state = current.state,
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
                if (
                    dao.markCoreCommitted(
                        operationId = normalizedOperationId,
                        expectedStates = CORE_COMMIT_SOURCE_STATES,
                        updatedAtMs = System.currentTimeMillis()
                    ) > 0
                ) {
                    return@withTransaction CoreCommitJournalRecovery(
                        outcome = CoreCommitJournalRecovery.Outcome.COMMITTED,
                        state = "CORE_COMMITTED",
                        stopRequestedByUser = current.stopRequestedByUser
                    )
                }
            } else if (current.state in CORE_COMMIT_RECOVERY_SOURCE_STATES) {
                if (
                    dao.transitionState(
                        operationId = normalizedOperationId,
                        expectedStates = listOf(current.state),
                        state = "COMMITTING",
                        updatedAtMs = System.currentTimeMillis(),
                        errorCode = "CORE_COMMIT_RECOVERY"
                    ) > 0
                ) {
                    if (!coreMetadataDurable) {
                        return@withTransaction CoreCommitJournalRecovery(
                            outcome = CoreCommitJournalRecovery.Outcome.PREPARED,
                            state = "COMMITTING",
                            stopRequestedByUser = current.stopRequestedByUser
                        )
                    }
                    if (
                        dao.markCoreCommitted(
                            operationId = normalizedOperationId,
                            expectedStates = CORE_COMMIT_SOURCE_STATES,
                            updatedAtMs = System.currentTimeMillis()
                        ) > 0
                    ) {
                        return@withTransaction CoreCommitJournalRecovery(
                            outcome = CoreCommitJournalRecovery.Outcome.COMMITTED,
                            state = "CORE_COMMITTED",
                            stopRequestedByUser = current.stopRequestedByUser
                        )
                    }
                }
            } else {
                return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
                    state = current.state,
                    stopRequestedByUser = current.stopRequestedByUser
                )
            }
            current = dao.findHeader(normalizedOperationId)
                ?: return@withTransaction CoreCommitJournalRecovery(
                    outcome = CoreCommitJournalRecovery.Outcome.MISSING,
                    state = null,
                    stopRequestedByUser = false
                )
        }
        CoreCommitJournalRecovery(
            outcome = CoreCommitJournalRecovery.Outcome.BLOCKED,
            state = current.state,
            stopRequestedByUser = current.stopRequestedByUser
        )
    }
}

internal suspend fun DownloadExecutionRoomStore.markCommittingImpl(context: Context, operationId: String): Boolean {
    return transitionStateAtomically(
        context = context,
        operationId = operationId,
        expectedStates = COMMIT_SOURCE_STATES,
        requestedState = "COMMITTING",
        errorCode = null
    )
}

internal suspend fun DownloadExecutionRoomStore.markUserRequestedProcessExitOperationsImpl(
    context: Context,
    entries: Collection<StateEntry>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Set<String> {
    val requests = entries
        .filter { entry -> entry.request.userInitiated }
        .distinctBy { entry -> entry.request.operationId }
    if (requests.isEmpty()) return emptySet()
    val nowMs = System.currentTimeMillis()
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        requests.mapNotNullTo(linkedSetOf()) { entry ->
            val operationId = entry.request.operationId
            val stableKey = entry.request.song.stableKey()
            val header = dao.findHeader(operationId)
                ?.takeIf { it.stableKey == stableKey }
                ?.takeUnless { it.stopRequestedByUser }
                ?: return@mapNotNullTo null
            val requeueState = resolveProcessExitRecoveryState(header.state)
            val requeued = requeueState != null && dao.requeueAfterProcessExit(
                operationId = operationId,
                stableKey = stableKey,
                updatedAtMs = nowMs
            ) > 0
            val released = if (requeued) {
                true
            } else if (header.state in IN_FLIGHT_OPERATION_STATES) {
                dao.releaseAfterProcessExit(
                    operationId = operationId,
                    stableKey = stableKey,
                    updatedAtMs = nowMs
                ) > 0
            } else {
                false
            }
            stableKey.takeIf { released }
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.requeueOrphanedRunningOperationsImpl(
    context: Context,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Set<String> {
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val orphaned = dao.findOrphanedRunningOperationIdentities(
            processToken = HOST_ADMISSION_PROCESS_TOKEN
        )
        if (orphaned.isEmpty()) {
            return@withTransaction emptySet()
        }
        val requeued = dao.requeueOrphanedRunningOperations(
            processToken = HOST_ADMISSION_PROCESS_TOKEN,
            updatedAtMs = System.currentTimeMillis()
        )
        if (requeued <= 0) {
            emptySet()
        } else {
            orphaned.mapTo(linkedSetOf()) { identity -> identity.stableKey }
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.clearUserStopForStableKeysImpl(
    context: Context,
    stableKeys: Collection<String>
): Boolean {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    if (keys.isEmpty()) return false
    val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
    val updatedAtMs = System.currentTimeMillis()
    return keys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
        dao.clearUserStopForStableKeysAnyLibrary(
            stableKeys = chunk,
            updatedAtMs = updatedAtMs
        )
    } > 0
}

internal suspend fun DownloadExecutionRoomStore.clearUserStopForFreshStartImpl(
    context: Context,
    stableKeys: Collection<String>
): Boolean {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    if (keys.isEmpty()) return false
    val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
    val updatedAtMs = System.currentTimeMillis()
    return keys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
        dao.clearUserStopForFreshStartAnyLibrary(
            stableKeys = chunk,
            updatedAtMs = updatedAtMs
        )
    } > 0
}

internal suspend fun DownloadExecutionRoomStore.prepareExplicitResumeImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    return database.downloadOperationDao().prepareExplicitResume(
        operationId = operationId,
        stableKey = normalizedKey,
        expectedStates = EXPLICIT_RESUME_SOURCE_STATES,
        updatedAtMs = System.currentTimeMillis()
    ) > 0
}

internal suspend fun DownloadExecutionRoomStore.prepareExplicitResumesForStableKeysImpl(
    context: Context,
    stableKeys: Collection<String>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Int {
    val normalizedKeys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
    if (normalizedKeys.isEmpty()) return 0
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        dao.findUserStoppedHeaders()
            .filter { header -> header.stableKey in normalizedKeys }
            .sumOf { header ->
                dao.prepareExplicitResume(
                    operationId = header.operationId,
                    stableKey = header.stableKey,
                    expectedStates = EXPLICIT_RESUME_SOURCE_STATES,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
    }
}

internal suspend fun DownloadExecutionRoomStore.restoreExplicitStopImpl(
    context: Context,
    operationId: String,
    stableKey: String,
    errorCode: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
    return database.downloadOperationDao().restoreExplicitStop(
        operationId = operationId,
        stableKey = normalizedKey,
        expectedStates = EXPLICIT_STOP_RESTORE_SOURCE_STATES,
        updatedAtMs = System.currentTimeMillis(),
        errorCode = errorCode
    ) > 0
}
