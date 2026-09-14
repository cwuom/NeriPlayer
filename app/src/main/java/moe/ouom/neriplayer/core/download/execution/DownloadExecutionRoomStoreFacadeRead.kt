package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.CancellationBoundary
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.DownloadBatchIdentity
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.DownloadBatchRecoverySnapshot
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.DownloadBatchNetworkPolicy
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.BatchMemberMutation
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.BatchMemberBinding
import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey

internal suspend fun DownloadExecutionRoomStore.requestCancelImpl(context: Context, operationId: String): Boolean {
    val database = NeriUserDataDatabase.getInstance(context)
    val dao = database.downloadOperationDao()
    if (
        dao.transitionState(
            operationId = operationId,
            expectedStates = CANCELABLE_OPERATION_STATES,
            state = "CANCEL_REQUESTED",
            updatedAtMs = System.currentTimeMillis(),
            errorCode = "USER_CANCELLED"
        ) > 0
    ) {
        return true
    }
    if (
        dao.requestStoppedCancellation(
            operationId = operationId,
            updatedAtMs = System.currentTimeMillis()
        ) > 0
    ) {
        return true
    }
    return dao.requestCommitBoundaryStop(
        operationId = operationId,
        expectedStates = COMMIT_BOUNDARY_CANCEL_STATES,
        updatedAtMs = System.currentTimeMillis()
    ) > 0
}

internal suspend fun DownloadExecutionRoomStore.requestCancelImpl(
    context: Context,
    operationId: String,
    database: NeriUserDataDatabase,
    updatedAtMs: Long = System.currentTimeMillis()
): Boolean {
    val dao = database.downloadOperationDao()
    if (
        dao.transitionState(
            operationId = operationId,
            expectedStates = CANCELABLE_OPERATION_STATES,
            state = "CANCEL_REQUESTED",
            updatedAtMs = updatedAtMs,
            errorCode = "USER_CANCELLED"
        ) > 0
    ) {
        return true
    }
    if (
        dao.requestStoppedCancellation(
            operationId = operationId,
            updatedAtMs = updatedAtMs
        ) > 0
    ) {
        return true
    }
    return dao.requestCommitBoundaryStop(
        operationId = operationId,
        expectedStates = COMMIT_BOUNDARY_CANCEL_STATES,
        updatedAtMs = updatedAtMs
    ) > 0
}

internal suspend fun DownloadExecutionRoomStore.requestCancelForStableKeysBeforeImpl(
    context: Context,
    boundaries: Collection<CancellationBoundary>,
    excludedOperationIds: Collection<String> = emptySet(),
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Set<String> {
    val boundaryByKey = boundaries.asSequence()
        .map { boundary ->
            boundary.stableKey.trim() to boundary.createdAtMsAtMost.coerceAtLeast(0L)
        }
        .filter { (stableKey, _) -> stableKey.isNotBlank() }
        .groupBy({ (stableKey, _) -> stableKey }, { (_, cutoff) -> cutoff })
        .mapValues { (_, cutoffs) -> cutoffs.maxOrNull() ?: 0L }
    if (boundaryByKey.isEmpty()) return emptySet()
    val excludedIds = excludedOperationIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val headers = boundaryByKey.keys
            .toList()
            .chunked(SQLITE_IN_QUERY_CHUNK_SIZE)
            .flatMap { stableKeyChunk ->
                dao.findAllHeadersByStableKeysAnyLibrary(
                    stableKeys = stableKeyChunk,
                    states = CANCELLATION_CANDIDATE_OPERATION_STATES
                )
            }
        val eligibleHeaders = headers.filter { header ->
            header.operationId !in excludedIds &&
                header.createdAtMs <= (boundaryByKey[header.stableKey] ?: -1L)
        }
        val cancelIds = eligibleHeaders
            .filter { header ->
                header.state in setOf(
                    "PENDING_QUEUE",
                    "QUEUED",
                    WAITING_STORAGE_MUTATION_OPERATION_STATE,
                    "RUNNING",
                    "STOPPED",
                    "RETRYABLE"
                ) && !header.stopRequestedByUser
            }
            .map(DownloadOperationHeaderRow::operationId)
            .distinct()
        val commitBoundaryIds = eligibleHeaders
            .filter { header ->
                header.state in setOf(
                    "COMMITTING",
                    "CORE_COMMITTED",
                    "ASSETS_ENRICHING",
                    "DEGRADED_COMPLETE"
                ) && !header.stopRequestedByUser
            }
            .map(DownloadOperationHeaderRow::operationId)
            .distinct()
        val updatedAtMs = System.currentTimeMillis()
        cancelIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
            dao.requestCancellations(chunk, updatedAtMs)
        }
        commitBoundaryIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
            dao.requestCommitBoundaryCancellations(chunk, updatedAtMs)
        }
        (cancelIds + commitBoundaryIds).toSet()
    }
}

internal suspend fun DownloadExecutionRoomStore.purgeCancelledImpl(
    context: Context,
    stableKeys: Collection<String>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
) {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
    if (keys.isEmpty()) return
    listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
        deleteByStateAndStableKeys(
            context = context,
            state = state,
            stableKeys = keys,
            database = database
        )
    }
}

internal suspend fun DownloadExecutionRoomStore.purgeCancelledOperationIdsImpl(
    context: Context,
    operationIds: Collection<String>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Int {
    val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
    if (ids.isEmpty()) return 0
    val eligibleIds = ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
        database.downloadOperationDao()
            .findAllHeadersByOperationIds(chunk)
            .filter { header -> header.state in setOf("CANCEL_REQUESTED", "CANCELLED") }
            .map(DownloadOperationHeaderRow::operationId)
    }
    return deleteOperationsWithAdmissions(database, eligibleIds)
}

internal suspend fun DownloadExecutionRoomStore.tryAcquireHostAdmissionImpl(
    context: Context,
    operationId: String,
    capacity: Int,
    nowMs: Long = System.currentTimeMillis(),
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    if (capacity <= 0 || operationId.isBlank()) return false
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        // Room 只在主进程使用，其他令牌留下的记录已经无法继续拥有系统宿主
        dao.deleteHostAdmissionsFromOtherProcesses(HOST_ADMISSION_PROCESS_TOKEN)
        dao.deleteExpiredHostAdmissions(
            processToken = HOST_ADMISSION_PROCESS_TOKEN,
            cutoffMs = (nowMs - HOST_ADMISSION_HANDOFF_LEASE_MS).coerceAtLeast(0L),
            states = HOST_ADMISSION_EXPIRABLE_STATES
        )
        val operation = dao.findHeader(operationId) ?: return@withTransaction false
        if (operation.hostProcessToken == HOST_ADMISSION_PROCESS_TOKEN) {
            return@withTransaction true
        }
        if (
            operation.stopRequestedByUser ||
                operation.state !in HOST_ADMISSION_HANDOFF_STATES
        ) {
            return@withTransaction false
        }
        if (hasOtherValidWaitingStorageMutation(database, operation)) {
            return@withTransaction false
        }
        if (dao.countHostAdmissions(HOST_ADMISSION_PROCESS_TOKEN) >= capacity) {
            return@withTransaction false
        }
        dao.setHostAdmission(
            operationId = operationId,
            processToken = HOST_ADMISSION_PROCESS_TOKEN,
            admittedAtMs = nowMs
        ) > 0
    }
}

internal suspend fun DownloadExecutionRoomStore.releaseHostAdmissionsImpl(
    context: Context,
    operationIds: Collection<String>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
) {
    val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
    if (ids.isEmpty()) return
    ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
        database.downloadOperationDao().deleteHostAdmissions(chunk)
    }
}

internal suspend fun DownloadExecutionRoomStore.currentHostAdmissionCountImpl(
    context: Context,
    nowMs: Long = System.currentTimeMillis(),
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Int {
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        dao.deleteHostAdmissionsFromOtherProcesses(HOST_ADMISSION_PROCESS_TOKEN)
        dao.deleteExpiredHostAdmissions(
            processToken = HOST_ADMISSION_PROCESS_TOKEN,
            cutoffMs = (nowMs - HOST_ADMISSION_HANDOFF_LEASE_MS).coerceAtLeast(0L),
            states = HOST_ADMISSION_EXPIRABLE_STATES
        )
        dao.countHostAdmissions(HOST_ADMISSION_PROCESS_TOKEN)
    }
}

internal suspend fun DownloadExecutionRoomStore.markCoreCommittedImpl(
    context: Context,
    operationId: String,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Boolean {
    val normalizedOperationId = normalizeDownloadOperationId(operationId) ?: return false
    return database.withTransaction {
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(normalizedOperationId)
            ?: return@withTransaction false
        val request = readRequestFromHeader(dao, header).request
        val attemptId = request?.attemptId
        val changed = dao.markCoreCommitted(
            operationId = normalizedOperationId,
            expectedStates = CORE_COMMIT_SOURCE_STATES,
            updatedAtMs = System.currentTimeMillis()
        ) > 0
        val currentState = dao.findState(normalizedOperationId)
        val committed = changed || currentState in CORE_COMMITTED_STATES
        if (committed) {
            markMembersCompletedForOperationInTransaction(
                database = database,
                operationId = normalizedOperationId,
                stableKey = header.stableKey,
                attemptId = attemptId
            )
        }
        committed
    }
}

internal suspend fun DownloadExecutionRoomStore.createBatchSnapshotImpl(
    context: Context,
    songs: Collection<SongItem>,
    initiallyCompletedSongKeys: Set<String> = emptySet(),
    clearEpoch: Long = 0L,
    networkGeneration: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): DownloadBatchIdentity {
    val normalizedSongs = songs
        .map { song -> song to song.stableKey().trim() }
        .filter { (_, key) -> key.isNotBlank() }
        .distinctBy { (_, key) -> key }
        .map { (song, _) -> song }
    require(normalizedSongs.isNotEmpty()) { "songs must not be empty" }
    return database.withTransaction {
        val batchDao = database.downloadBatchDao()
        val previousGeneration = batchDao.findMaxGeneration() ?: 0L
        require(previousGeneration < Long.MAX_VALUE) {
            "download batch generation exhausted"
        }
        val identity = DownloadBatchIdentity(
            batchId = UUID.randomUUID().toString(),
            generation = (previousGeneration + 1L).coerceAtLeast(1L)
        )
        val completedKeys = initiallyCompletedSongKeys
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val members = normalizedSongs.mapIndexed { ordinal, song ->
            val stableKey = song.stableKey()
            val initiallyCompleted = stableKey in completedKeys
            DownloadBatchMemberEntity(
                batchId = identity.batchId,
                ordinal = ordinal,
                stableKey = stableKey,
                terminalBits = if (initiallyCompleted) {
                    DownloadBatchMemberTerminal.COMPLETED
                } else {
                    DownloadBatchMemberTerminal.NONE
                },
                maxFractionMilli = if (initiallyCompleted) 1000 else 0,
                initiallyCompleted = initiallyCompleted,
                updatedAtMs = nowMs
            )
        }
        val batch = DownloadBatchEntity(
            batchId = identity.batchId,
            generation = identity.generation,
            totalCount = members.size,
            stateBits = DownloadBatchState.OPEN,
            clearEpoch = clearEpoch.coerceAtLeast(0L),
            networkGeneration = networkGeneration,
            updatedAtMs = nowMs,
            createdAtMs = nowMs
        )
        insertBatchSnapshotInTransaction(database, batch, members)
        if (members.all { member -> member.terminalBits != DownloadBatchMemberTerminal.NONE }) {
            batchDao.markCompletedIfAllMembersTerminal(
                batchId = identity.batchId,
                generation = identity.generation,
                nowMs = nowMs
            )
        }
        identity
    }
}

internal suspend fun DownloadExecutionRoomStore.readOpenBatchSnapshotsImpl(
    context: Context,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): List<DownloadBatchRecoverySnapshot> {
    val batchDao = database.downloadBatchDao()
    return database.withTransaction {
        batchDao.findOpenBatches().map { batch ->
            DownloadBatchRecoverySnapshot(
                batch = batch,
                members = batchDao.listMembers(batch.batchId)
            )
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.findOpenBatchIdentitiesForStableKeysImpl(
    context: Context,
    stableKeys: Collection<String>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): List<DownloadBatchIdentity> {
    val keys = stableKeys.map(String::trim).filter(String::isNotBlank).toSet()
    if (keys.isEmpty()) return emptyList()
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        val batches = mutableListOf<DownloadBatchEntity>()
        for (keyChunk in keys.toList().chunked(SQLITE_IN_QUERY_CHUNK_SIZE)) {
            batches += dao.findOpenBatchesForStableKeys(keyChunk)
        }
        batches
            .distinctBy { batch -> batch.batchId to batch.generation }
            .map { batch ->
                DownloadBatchIdentity(
                    batchId = batch.batchId,
                    generation = batch.generation
                )
            }
    }
}

internal suspend fun DownloadExecutionRoomStore.findPendingStableKeysForOpenBatchesImpl(
    context: Context,
    identities: Collection<DownloadBatchIdentity>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
): Set<String> {
    val distinctIdentities = identities.distinct()
    if (distinctIdentities.isEmpty()) return emptySet()
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        buildSet {
            distinctIdentities.forEach { identity ->
                addAll(
                    dao.listPendingStableKeysForOpenBatch(
                        batchId = identity.batchId,
                        generation = identity.generation
                    )
                )
            }
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.bindBatchMemberOperationsImpl(
    context: Context,
    identity: DownloadBatchIdentity,
    bindings: Collection<BatchMemberBinding>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val normalizedBindings = bindings.mapNotNull { binding ->
        val key = binding.stableKey.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        val operationId = normalizeDownloadOperationId(binding.operationId)
            ?: return@mapNotNull null
        BatchMemberBinding(key, operationId, binding.attemptId?.takeIf { it > 0L })
    }.distinctBy { binding -> binding.stableKey }
    if (normalizedBindings.isEmpty()) return 0
    return database.withTransaction {
        val operationDao = database.downloadOperationDao()
        val batchDao = database.downloadBatchDao()
        if (batchDao.findBatch(identity.batchId, identity.generation) == null) {
            return@withTransaction 0
        }
        normalizedBindings.count { binding ->
            val header = operationDao.findHeader(binding.operationId)
            if (
                header == null ||
                    header.stableKey != binding.stableKey ||
                    header.batchId != identity.batchId ||
                    header.batchGeneration != identity.generation
            ) {
                false
            } else {
                batchDao.bindMemberOperationCAS(
                    batchId = identity.batchId,
                    batchGeneration = identity.generation,
                    stableKey = binding.stableKey,
                    operationId = binding.operationId,
                    attemptId = binding.attemptId,
                    nowMs = nowMs
                ) > 0
            }
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.attachBatchIdentityImpl(
    context: Context,
    identity: DownloadBatchIdentity,
    requests: Collection<DownloadExecutionRequest>,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): Int {
    val distinctRequests = requests
        .asSequence()
        .filter { request -> request.song.stableKey().isNotBlank() }
        .distinctBy(DownloadExecutionRequest::operationId)
        .toList()
    if (distinctRequests.isEmpty()) return 0
    val persistedNetworkPolicies = mutableListOf<Triple<String, Boolean, Long>>()
    val boundMemberCount = database.withTransaction {
        val operationDao = database.downloadOperationDao()
        val batchDao = database.downloadBatchDao()
        if (batchDao.findBatch(identity.batchId, identity.generation) == null) {
            return@withTransaction 0
        }
        var boundMembers = 0
        distinctRequests.forEach { request ->
            val stableKey = request.song.stableKey()
            val header = operationDao.findHeader(request.operationId) ?: return@forEach
            if (header.stableKey != stableKey) return@forEach
            val existingRequest = readSourceHintJson(operationDao, header)
                ?.let { sourceHintJson ->
                    requestFromEntity(header.toEntity(sourceHintJson))
                }
            val existingBatchId = existingRequest?.batchId ?: header.batchId
            val existingBatchGeneration =
                existingRequest?.batchGeneration ?: header.batchGeneration
            val operationBoundToTarget = when {
                existingBatchId == null && existingBatchGeneration == null -> {
                    val payloadUpdatedAtMs = nextPayloadUpdatedAt(
                        previousUpdatedAtMs = header.updatedAtMs,
                        requestedAtMs = nowMs
                    )
                    val bound = operationDao.bindBatchIdentityIfUnbound(
                        operationId = request.operationId,
                        stableKey = stableKey,
                        batchId = identity.batchId,
                        batchGeneration = identity.generation,
                        sourceHintJson = requestToJson(
                            request.copy(
                                batchId = identity.batchId,
                                batchGeneration = identity.generation
                            )
                        ).toString(),
                        updatedAtMs = payloadUpdatedAtMs
                    ) > 0
                    if (bound) {
                        persistedNetworkPolicies += Triple(
                            request.operationId,
                            request.requiresWifiNetwork,
                            payloadUpdatedAtMs
                        )
                    }
                    bound
                }

                existingBatchId == identity.batchId &&
                    existingBatchGeneration == identity.generation -> true

                else -> false
            }
            if (!operationBoundToTarget) {
                // 一个 operation 只能归属一个批次。不能把旧批次的 operation
                // 再挂到新批次，否则同一回调会同时推进两个批次
                return@forEach
            }
            val effectiveAttemptId = request.attemptId
                ?: existingRequest?.attemptId
            val changed = batchDao.bindMemberOperationCAS(
                batchId = identity.batchId,
                batchGeneration = identity.generation,
                stableKey = stableKey,
                operationId = request.operationId,
                attemptId = effectiveAttemptId?.takeIf { it > 0L },
                nowMs = nowMs
            )
            if (changed > 0) boundMembers++
        }
        boundMembers
    }
    persistedNetworkPolicies.forEach { (operationId, requiresWifiNetwork, updatedAtMs) ->
        cacheNetworkPolicy(operationId, requiresWifiNetwork, updatedAtMs)
    }
    return boundMemberCount
}

internal suspend fun DownloadExecutionRoomStore.prepareBatchMembersForTransferImpl(
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
        val batch = dao.findBatch(identity.batchId, identity.generation)
        if (
            batch == null ||
                batch.stateBits and DownloadBatchState.OPEN == 0 ||
                batch.stateBits and DownloadBatchState.CLEARING != 0 ||
                batch.stateBits and DownloadBatchState.TERMINAL_MASK != 0
        ) {
            return@withTransaction 0
        }
        keys.sumOf { stableKey ->
            dao.clearInitialMemberCompletionCAS(
                batchId = identity.batchId,
                stableKey = stableKey,
                nowMs = nowMs
            )
        }
    }
}

internal suspend fun DownloadExecutionRoomStore.markBatchMemberTerminalImpl(
    context: Context,
    identity: DownloadBatchIdentity,
    stableKey: String,
    operationId: String,
    attemptId: Long?,
    terminalBits: Int,
    fractionMilli: Int = 1000,
    database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
    nowMs: Long = System.currentTimeMillis()
): BatchMemberMutation {
    require(terminalBits in DownloadBatchMemberTerminal.VALID_BITS) {
        "invalid batch member terminal bits"
    }
    require(terminalBits != DownloadBatchMemberTerminal.NONE) {
        "terminal bits must not be NONE"
    }
    val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return BatchMemberMutation.STALE
    val normalizedOperationId = normalizeDownloadOperationId(operationId)
        ?: return BatchMemberMutation.STALE
    return database.withTransaction {
        val dao = database.downloadBatchDao()
        if (dao.findBatch(identity.batchId, identity.generation) == null) {
            return@withTransaction BatchMemberMutation.MISSING
        }
        val normalizedAttemptId = attemptId?.takeIf { it > 0L }
        val updated = dao.markMemberTerminalCAS(
            batchId = identity.batchId,
            stableKey = normalizedKey,
            operationId = normalizedOperationId,
            attemptId = normalizedAttemptId,
            terminalBits = terminalBits,
            fraction = fractionMilli.coerceIn(0, 1000),
            nowMs = nowMs
        )
        val result = if (updated > 0) {
            BatchMemberMutation.APPLIED
        } else {
            val current = dao.findMember(identity.batchId, normalizedKey)
            if (
                current?.operationId == normalizedOperationId &&
                    current.attemptId == normalizedAttemptId &&
                    current.terminalBits == terminalBits
            ) {
                BatchMemberMutation.IDEMPOTENT
            } else {
                BatchMemberMutation.STALE
            }
        }
        if (result == BatchMemberMutation.APPLIED || result == BatchMemberMutation.IDEMPOTENT) {
            dao.markCompletedIfAllMembersTerminal(
                batchId = identity.batchId,
                generation = identity.generation,
                nowMs = nowMs
            )
        }
        result
    }
}
