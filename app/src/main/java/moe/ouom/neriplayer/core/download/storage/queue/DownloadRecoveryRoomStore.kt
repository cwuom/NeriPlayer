package moe.ouom.neriplayer.core.download.storage.queue

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberTerminal
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection

internal fun isLegacyQueueImportSuppressed(cutoverState: String?): Boolean {
    return cutoverState == DownloadRecoveryRoomStore.USER_CLEARED_STATE
}

private fun Collection<String>.normalizedOperationIds(): Set<String> {
    return asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
}

private fun Collection<String>.normalizedStableKeys(): Set<String> {
    return asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()
}

/** compatibility facade for v15 queue files; operation journal owns new work */
internal class DownloadRecoveryRoomStore(
    private val context: Context,
    private val database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
) {
    internal data class WaitingStorageMutationBatchResult(
        val operationIds: List<String>,
        val requestsByOperationId: Map<String, DownloadExecutionRequest>
    )

    private val appContext = context.applicationContext

    suspend fun upsertPendingDownloadQueue(
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis(),
        userInitiated: Boolean = false,
        requiresWifiNetwork: Boolean = true,
        downloadAudioQuality: DownloadAudioQualitySelection? = null,
        excludedOperationIds: Collection<String> = emptySet(),
        forceNewOperationForStableKeys: Collection<String> = emptySet()
    ): List<String> {
        val requestedExcludedIds = excludedOperationIds.normalizedOperationIds()
        val forceNewKeys = forceNewOperationForStableKeys.normalizedStableKeys()
        return database.withTransaction {
            val distinctSongs = songs.distinctBy(SongItem::stableKey)
            if (distinctSongs.isEmpty()) return@withTransaction emptyList()
            val excludedIds = requestedExcludedIds + operationIdsForStableKeysInTransaction(
                forceNewKeys
            )
            val songKeys = distinctSongs.map(SongItem::stableKey)
            val failedKeys = if (userInitiated) emptySet() else {
                songKeys.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).flatMap { keys ->
                    database.downloadOperationDao().findAllHeadersByStableKeysAnyLibrary(
                        stableKeys = keys, states = listOf("INVALID")
                    ).map { it.stableKey }
                }.toSet()
            }
            val inFlightOperationIds = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = songKeys,
                    database = database,
                    states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                    excludeUserCancelledStops = true,
                    excludedOperationIds = excludedIds
                )
                .mapValues { (_, request) -> request.operationId }
            DownloadExecutionRoomStore.rehydrateMalformedReusableOperations(
                context = appContext,
                songs = distinctSongs.filter { song ->
                    inFlightOperationIds[song.stableKey()] == null
                },
                userInitiated = userInitiated,
                requiresWifiNetwork = requiresWifiNetwork,
                downloadAudioQuality = downloadAudioQuality,
                excludedOperationIds = excludedIds,
                updatedAtMs = nowMs,
                database = database
            )
            val existing = readLatestOperationCandidates(
                songKeys = songKeys,
                states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                excludeUserStoppedOperations = true,
                invalidateDuplicates = true,
                nowMs = nowMs,
                excludedOperationIds = excludedIds
            )
            val existingOperationIds = existing.mapValues { (_, candidate) ->
                candidate.metadata.operationId
            }
            var nextOrder = database.downloadOperationDao().findMaxActiveQueueOrder(
                states = DownloadExecutionRoomStore.ACTIVE_OPERATION_STATES
            )?.let { maxOrder -> maxOrder + 1 } ?: 0
            distinctSongs.mapNotNull { song ->
                val key = song.stableKey()
                inFlightOperationIds[key]?.let { operationId ->
                    return@mapNotNull operationId
                }
                val old = existing[key]
                // 迟到的宿主停止回调不能为已耗尽预算的歌曲自动创建一代新任务
                if (old == null && key in failedKeys && key !in forceNewKeys) return@mapNotNull null
                val operationId = existingOperationIds[key]
                    ?: UUID.randomUUID().toString()
                val effectiveRequiresWifiNetwork = if (userInitiated) {
                    requiresWifiNetwork
                } else {
                    old?.metadata?.requiresWifiNetwork ?: requiresWifiNetwork
                }
                DownloadExecutionRoomStore.upsert(
                    context = appContext,
                    request = DownloadExecutionRequest(
                        operationId = operationId,
                        song = song,
                        preserveStaging = old?.metadata?.preserveStaging ?: false,
                        requiresWifiNetwork = effectiveRequiresWifiNetwork,
                        attemptId = old?.metadata?.attemptId,
                        artifactLeaseId = old?.metadata?.artifactLeaseId
                            ?: UUID.randomUUID().toString(),
                        userInitiated = old?.metadata?.userInitiated == true || userInitiated,
                        downloadAudioQuality = old?.metadata?.downloadAudioQuality
                            ?: downloadAudioQuality
                    ),
                    state = PENDING_QUEUE_STATE,
                    queueOrder = old?.header?.queueOrder ?: nextOrder++,
                    createdAtMs = old?.header?.createdAtMs ?: nowMs,
                    database = database
                )
                operationId
            }
        }
    }

    suspend fun findQueuedOperationIdForSong(songKey: String): String? {
        val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return null
        return DownloadExecutionRoomStore.findReadableOperationIdForSong(
            context = appContext,
            songKey = normalizedKey,
            database = database,
            states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
        )
    }

    /**
     * records a user request that must wait for a SAF mutation without letting an
     * execution host observe it as runnable work
     * returns only operation ids that remain in the waiting state
     */
    suspend fun upsertWaitingStorageMutation(
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis(),
        userInitiated: Boolean = false,
        requiresWifiNetwork: Boolean = true,
        downloadAudioQuality: DownloadAudioQualitySelection? = null,
        excludedOperationIds: Collection<String> = emptySet(),
        forceNewOperationForStableKeys: Collection<String> = emptySet()
    ): List<String> {
        // the shared transaction batches findAllHeadersByStableKeys(...) and
        // findAllHeadersByOperationIds(...) so this compatibility facade adds no
        // per-song database round trips
        return upsertWaitingStorageMutationWithRequests(
            songs = songs,
            nowMs = nowMs,
            userInitiated = userInitiated,
            requiresWifiNetwork = requiresWifiNetwork,
            downloadAudioQuality = downloadAudioQuality,
            excludedOperationIds = excludedOperationIds,
            forceNewOperationForStableKeys = forceNewOperationForStableKeys
        ).operationIds
    }

    suspend fun upsertWaitingStorageMutationWithRequests(
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis(),
        userInitiated: Boolean = false,
        requiresWifiNetwork: Boolean = true,
        downloadAudioQuality: DownloadAudioQualitySelection? = null,
        excludedOperationIds: Collection<String> = emptySet(),
        forceNewOperationForStableKeys: Collection<String> = emptySet(),
        batchIdentity: DownloadExecutionRoomStore.DownloadBatchIdentity? = null
    ): WaitingStorageMutationBatchResult {
        if (songs.isEmpty()) {
            return WaitingStorageMutationBatchResult(
                operationIds = emptyList(),
                requestsByOperationId = emptyMap()
            )
        }
        val requestedExcludedIds = excludedOperationIds.normalizedOperationIds()
        val forceNewKeys = forceNewOperationForStableKeys.normalizedStableKeys()
        // 清空只删除 durable 行，旧的 用户发起的数据传输任务/Worker 协程仍可能在返回路径中访问
        // operationId。把清空代次纳入新等待 operation 的身份，避免旧回调误写新任务
        val clearEpoch = PersistentDownloadClearFenceStore.currentEpoch(appContext)
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val batchMembers = batchIdentity?.let { identity ->
                val batchDao = database.downloadBatchDao()
                val batch = batchDao.findBatch(identity.batchId, identity.generation)
                if (batch == null || batch.stateBits and DownloadBatchState.OPEN == 0 ||
                    batch.stateBits and (DownloadBatchState.CLEARING or DownloadBatchState.TERMINAL_MASK) != 0
                ) {
                    return@withTransaction WaitingStorageMutationBatchResult(emptyList(), emptyMap())
                }
                songs.map(SongItem::stableKey).distinct()
                    .chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE)
                    .flatMap { keys -> batchDao.findMembersByStableKeys(identity.batchId, keys) }
                    .associateBy { it.stableKey }
            }
            val distinctSongs = songs.distinctBy(SongItem::stableKey).filter { song ->
                batchMembers == null ||
                    batchMembers[song.stableKey()]?.terminalBits == DownloadBatchMemberTerminal.NONE
            }
            if (distinctSongs.isEmpty()) {
                return@withTransaction WaitingStorageMutationBatchResult(
                    operationIds = emptyList(),
                    requestsByOperationId = emptyMap()
                )
            }
            val excludedIds = requestedExcludedIds + operationIdsForStableKeysInTransaction(
                forceNewKeys
            )
            val songKeys = distinctSongs.map(SongItem::stableKey)
            val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(appContext)
            val waitingWinners = readLatestOperationCandidates(
                songKeys = songKeys,
                states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
                excludeUserStoppedOperations = true,
                invalidateDuplicates = true,
                nowMs = nowMs,
                excludedOperationIds = excludedIds
            )
            val inFlightOperationIds = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = songKeys,
                    database = database,
                    states = DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES,
                    excludeUserCancelledStops = true,
                    excludedOperationIds = excludedIds
                )
                .mapValues { (_, request) -> request.operationId }
            val reusableOperationIds = DownloadExecutionRoomStore
                .findReadableOperationsBySongKeys(
                    context = appContext,
                    songKeys = songKeys,
                    database = database,
                    states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES,
                    excludeUserStoppedOperations = true,
                    excludedOperationIds = excludedIds
                )
                .mapValues { (_, request) -> request.operationId }
            val blockedOperationIds = linkedMapOf<String, String>()
            songKeys.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).forEach { stableKeyChunk ->
                dao.findAllHeadersByStableKeysAnyLibrary(
                    stableKeys = stableKeyChunk,
                    states = WAITING_STORAGE_MUTATION_BLOCKING_STATES
                ).forEach { header ->
                    if (header.operationId !in excludedIds) {
                        blockedOperationIds.putIfAbsent(header.stableKey, header.operationId)
                    }
                }
            }
            val stoppedWaitingOperationIds = linkedMapOf<String, String>()
            songKeys.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).forEach { stableKeyChunk ->
                dao.findAllHeadersByStableKeysAnyLibrary(
                    stableKeys = stableKeyChunk,
                    states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
                ).forEach { header ->
                    if (header.stopRequestedByUser && header.operationId !in excludedIds) {
                        stoppedWaitingOperationIds.putIfAbsent(
                            header.stableKey,
                            header.operationId
                        )
                    }
                }
            }
            val deterministicOperationIdsBySongKey = distinctSongs.associate { song ->
                song.stableKey() to waitingStorageMutationOperationId(
                    libraryId = libraryId,
                    stableKey = song.stableKey(),
                    clearEpoch = clearEpoch
                )
            }
            val deterministicOperationsById = linkedMapOf<String, String>()
            deterministicOperationIdsBySongKey.values
                .chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE)
                .forEach { operationIdChunk ->
                    dao.findAllHeadersByOperationIds(operationIdChunk).forEach { header ->
                        deterministicOperationsById[header.operationId] = header.state
                    }
                }
            var nextOrder = (dao.findMaxActiveQueueOrder(
                states = DownloadExecutionRoomStore.ACTIVE_OPERATION_STATES
            ) ?: -1) + 1
            val requestsByOperationId = linkedMapOf<String, DownloadExecutionRequest>()
            val operationIds = distinctSongs.mapNotNull { song ->
                val key = song.stableKey()
                if (
                    key !in forceNewKeys &&
                        (inFlightOperationIds[key] != null || reusableOperationIds[key] != null)
                ) {
                    return@mapNotNull null
                }
                // 清空和新请求可以在同一时间窗内交错到达。旧取消行不能吞掉
                // 用户的新意图：用户主动重试时创建新的等待 operation，旧行仍
                // 由取消收敛负责，避免复用旧 lease 或让两代请求互相覆盖
                val blockedByCancellation = blockedOperationIds[key] != null ||
                    stoppedWaitingOperationIds[key] != null
                if (blockedByCancellation && !userInitiated && key !in forceNewKeys) {
                    return@mapNotNull null
                }
                val existing = waitingWinners[key]
                val deterministicOperationId = checkNotNull(
                    deterministicOperationIdsBySongKey[key]
                )
                val deterministicOperationState = deterministicOperationsById[
                    deterministicOperationId
                ]
                val mustReplaceDeterministicOperation =
                    blockedByCancellation ||
                        key in forceNewKeys ||
                        deterministicOperationId in excludedIds ||
                        deterministicOperationState in
                        WAITING_STORAGE_MUTATION_REPLACED_TERMINAL_STATES
                val existingOperationId = existing?.metadata?.operationId
                // 没有可复用 winner 的用户请求必须拥有新的 operation 身份。
                // 旧版本的固定 waiting id 可能刚被清空/取消协程持有，复用它会
                // 让迟到回调把新批次重新写回旧任务。
                val mustCreateFreshUserOperation = userInitiated &&
                    existingOperationId == null
                val mustCreateReplacement = blockedByCancellation ||
                    key in forceNewKeys ||
                    existingOperationId in excludedIds ||
                    (existingOperationId == null && mustReplaceDeterministicOperation) ||
                    mustCreateFreshUserOperation
                val operationId = if (mustCreateReplacement) {
                    UUID.randomUUID().toString()
                } else {
                    existingOperationId ?: deterministicOperationId
                }
                val reusableMetadata = existing?.takeUnless { mustCreateReplacement }
                val effectiveRequiresWifiNetwork = if (userInitiated) {
                    requiresWifiNetwork
                } else {
                    reusableMetadata?.metadata?.requiresWifiNetwork ?: requiresWifiNetwork
                }
                val initialBatch = batchIdentity?.takeIf {
                    (mustCreateReplacement || existingOperationId == null && deterministicOperationState == null) &&
                        batchMembers?.get(key)?.operationId == null
                }
                val reusableRequest = reusableMetadata?.let { candidate ->
                    DownloadExecutionRoomStore.read(
                        context = appContext,
                        operationId = candidate.metadata.operationId,
                        database = database
                    )
                }
                val request = DownloadExecutionRequest(
                    operationId = operationId,
                    song = reusableRequest?.song ?: song,
                    preserveStaging = reusableMetadata?.metadata?.preserveStaging ?: false,
                    requiresWifiNetwork = effectiveRequiresWifiNetwork,
                    attemptId = reusableMetadata?.metadata?.attemptId,
                    artifactLeaseId = reusableMetadata?.metadata?.artifactLeaseId
                        ?: UUID.randomUUID().toString(),
                    userInitiated = reusableMetadata?.metadata?.userInitiated == true ||
                        userInitiated,
                    downloadAudioQuality = reusableMetadata?.metadata?.downloadAudioQuality
                        ?: downloadAudioQuality,
                    batchId = initialBatch?.batchId ?: reusableMetadata?.header?.batchId,
                    batchGeneration = initialBatch?.generation
                        ?: reusableMetadata?.header?.batchGeneration
                )
                DownloadExecutionRoomStore.upsert(
                    context = appContext,
                    request = request,
                    state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
                    queueOrder = if (mustCreateReplacement) nextOrder++ else {
                        existing?.header?.queueOrder ?: nextOrder++
                    },
                    createdAtMs = if (mustCreateReplacement) {
                        nowMs
                    } else {
                        existing?.header?.createdAtMs ?: nowMs
                    },
                    database = database
                )
                if (initialBatch != null) {
                    // operation 载荷和成员在同一页事务绑定，首次交接不再读写整份歌曲 JSON
                    check(database.downloadBatchDao().bindMemberOperationCAS(
                        batchId = initialBatch.batchId,
                        batchGeneration = initialBatch.generation,
                        stableKey = key,
                        operationId = operationId,
                        attemptId = request.attemptId,
                        nowMs = nowMs
                    ) > 0) { "new download operation lost its batch member" }
                }
                requestsByOperationId[operationId] = request
                operationId
            }
            WaitingStorageMutationBatchResult(
                operationIds = operationIds,
                requestsByOperationId = requestsByOperationId
            )
        }
    }

    suspend fun listWaitingStorageMutations(): List<DownloadExecutionRoomStore.StateEntry> {
        return DownloadExecutionRoomStore.listByStates(
            context = appContext,
            states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE),
            excludeUserStoppedOperations = true,
            database = database
        )
            .sortedWith(compareBy({ it.queueOrder }, { it.request.operationId }))
    }

    suspend fun promoteWaitingStorageMutation(
        operationId: String,
        stableKey: String
    ): Boolean {
        return DownloadExecutionRoomStore.promoteWaitingStorageMutation(
            context = appContext,
            operationId = operationId,
            stableKey = stableKey,
            database = database
        )
    }

    suspend fun promoteWaitingStorageMutations(operationIds: Collection<String>): Int {
        return DownloadExecutionRoomStore.promoteWaitingStorageMutations(
            context = appContext,
            operationIds = operationIds,
            database = database
        )
    }

    suspend fun listPendingQueuedDownloads(): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        return DownloadExecutionRoomStore.listByStates(
            context = appContext,
            states = PENDING_QUEUE_VISIBLE_STATES,
            database = database
        )
            .sortedWith(compareBy({ it.queueOrder }, { it.request.operationId }))
            .map { entry ->
                ManagedDownloadStorage.PendingDownloadQueueEntry(
                    stableKey = entry.request.song.stableKey(),
                    song = entry.request.song,
                    order = entry.queueOrder,
                    queuedAtMs = entry.createdAtMs,
                    operationId = entry.request.operationId,
                    requiresWifiNetwork = entry.request.requiresWifiNetwork
                )
            }
    }

    suspend fun countPendingQueuedDownloads(): Int {
        return DownloadExecutionRoomStore.countByStates(
            context = appContext,
            states = PENDING_QUEUE_VISIBLE_STATES,
            database = database
        )
    }

    private data class ReadableOperationCandidate(
        val header: DownloadOperationHeaderRow,
        val metadata: DownloadExecutionRoomStore.OperationRequestMetadata
    )

    private suspend fun operationIdsForStableKeysInTransaction(
        stableKeys: Set<String>
    ): Set<String> {
        if (stableKeys.isEmpty()) return emptySet()
        val operationIds = linkedSetOf<String>()
        for (keyChunk in stableKeys.toList().chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE)) {
            database.downloadOperationDao()
                .findAllHeadersByStableKeysAnyLibrary(
                    stableKeys = keyChunk,
                    states = REPLACEMENT_OPERATION_STATES
                )
                .forEach { header -> operationIds += header.operationId }
        }
        return operationIds
    }

    /** 只读取请求歌曲对应的表头和调度字段，避免扫描整张 operation 表 */
    private suspend fun readLatestOperationCandidates(
        songKeys: Collection<String>,
        states: List<String>,
        excludeUserStoppedOperations: Boolean,
        invalidateDuplicates: Boolean,
        nowMs: Long,
        excludedOperationIds: Set<String> = emptySet()
    ): Map<String, ReadableOperationCandidate> {
        val normalizedKeys = songKeys
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedKeys.isEmpty() || states.isEmpty()) return emptyMap()

        val dao = database.downloadOperationDao()
        val libraryId = ManagedDownloadStorage.currentSnapshotCacheKey(appContext)
        val headersByStableKey = linkedMapOf<String, MutableList<DownloadOperationHeaderRow>>()

        fun addHeaders(headers: Collection<DownloadOperationHeaderRow>) {
            headers.forEach { header ->
                if (header.stableKey in normalizedKeys) {
                    headersByStableKey
                        .getOrPut(header.stableKey) { mutableListOf() }
                        .apply {
                            if (none { existing -> existing.operationId == header.operationId }) {
                                add(header)
                            }
                        }
                }
            }
        }

        normalizedKeys.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).forEach { keyChunk ->
            addHeaders(
                dao.findAllHeadersByStableKeys(
                    libraryId = libraryId,
                    stableKeys = keyChunk,
                    states = states
                )
            )
        }

        fun candidateHeaders(key: String): List<DownloadOperationHeaderRow> {
            return headersByStableKey[key]
                .orEmpty()
                .asSequence()
                .filterNot { header -> header.operationId in excludedOperationIds }
                .filterNot { header ->
                    excludeUserStoppedOperations && header.stopRequestedByUser
                }
                .sortedWith(
                    compareByDescending<DownloadOperationHeaderRow> { it.createdAtMs }
                        .thenBy { it.operationId }
                )
                .toList()
        }

        var candidateHeaderList = normalizedKeys.flatMap(::candidateHeaders)
        var metadataByOperationId = DownloadExecutionRoomStore.readOperationRequestMetadata(
            context = appContext,
            operationIds = candidateHeaderList.map(DownloadOperationHeaderRow::operationId),
            database = database
        )
        val unresolvedKeys = normalizedKeys.filter { key ->
            candidateHeaders(key).none { header -> metadataByOperationId[header.operationId] != null }
        }
        if (unresolvedKeys.isNotEmpty()) {
            unresolvedKeys.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).forEach { keyChunk ->
                addHeaders(
                    dao.findAllHeadersByStableKeysAnyLibrary(
                        stableKeys = keyChunk,
                        states = states
                    )
                )
            }
            candidateHeaderList = normalizedKeys.flatMap(::candidateHeaders)
            metadataByOperationId = DownloadExecutionRoomStore.readOperationRequestMetadata(
                context = appContext,
                operationIds = candidateHeaderList.map(DownloadOperationHeaderRow::operationId),
                database = database
            )
        }

        val selected = linkedMapOf<String, ReadableOperationCandidate>()
        normalizedKeys.forEach { key ->
            val header = candidateHeaders(key).firstOrNull { candidate ->
                metadataByOperationId[candidate.operationId] != null
            } ?: return@forEach
            val metadata = metadataByOperationId[header.operationId] ?: return@forEach
            if (metadata.stableKey != header.stableKey) return@forEach
            if (header.libraryId != libraryId) {
                dao.rehomeOperationLibrary(
                    operationId = header.operationId,
                    stableKey = header.stableKey,
                    libraryId = libraryId,
                    states = states,
                    updatedAtMs = nowMs
                )
            }
            selected[header.stableKey] = ReadableOperationCandidate(
                header = header,
                metadata = metadata
            )
        }

        if (invalidateDuplicates) {
            headersByStableKey.forEach { (key, headers) ->
                val winnerId = selected[key]?.header?.operationId
                headers.filterNot { header ->
                    header.operationId == winnerId || header.operationId in excludedOperationIds
                }
                    .filter { header ->
                        !excludeUserStoppedOperations || !header.stopRequestedByUser
                    }
                    .forEach { header ->
                        dao.transitionState(
                            operationId = header.operationId,
                            expectedStates = states,
                            state = "INVALID",
                            updatedAtMs = nowMs,
                            errorCode = "DUPLICATE_STABLE_KEY_OPERATION"
                        )
                    }
            }
        }
        return selected
    }

    suspend fun removePendingDownloadQueueEntries(songKeys: Collection<String>) {
        val keys = songKeys.filter(String::isNotBlank).toSet().toList()
        CLEARABLE_PENDING_QUEUE_STATES.forEach { state ->
            DownloadExecutionRoomStore.deleteByStateAndStableKeys(
                context = appContext,
                state = state,
                stableKeys = keys,
                database = database
            )
        }
    }

    /** 取消清理只删除快照中的 operation，不能按 stable key 抹掉替代请求 */
    suspend fun removePendingDownloadQueueOperationIds(operationIds: Collection<String>) {
        val ids = operationIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (ids.isEmpty()) return
        ids.chunked(DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE).forEach { chunk ->
            database.withTransaction {
                val dao = database.downloadOperationDao()
                val removableIds = dao.findAllHeadersByOperationIds(chunk)
                    .filter { header -> header.state in CLEARABLE_PENDING_QUEUE_STATES }
                    .map(DownloadOperationHeaderRow::operationId)
                if (removableIds.isNotEmpty()) {
                    dao.deleteHostAdmissions(removableIds)
                    dao.deleteOperations(removableIds)
                }
            }
        }
    }

    suspend fun clearPendingDownloadQueue() {
        database.withTransaction {
            markLegacyQueueImportSuppressed()
            CLEARABLE_PENDING_QUEUE_STATES.forEach { state ->
                DownloadExecutionRoomStore.deleteByState(
                    context = appContext,
                    state = state,
                    database = database
                )
            }
        }
    }

    /**
     * imports the v15 files once after the Room database is available
     * runtime queue operations must never call this method implicitly
     */
    suspend fun bootstrapLegacyFilesOnce() {
        bootstrapLegacyQueueFile()
        bootstrapLegacyCancelledFile()
    }

    private suspend fun bootstrapLegacyQueueFile() {
        val queueFile = File(appContext.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
        if (!queueFile.isFile) {
            database.withTransaction {
                if (!isLegacyQueueFileImportBlocked() && !queueFile.isFile) {
                    markPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)
                }
            }
            return
        }
        val parsed = ManagedDownloadQueueStore.readPendingDownloadQueueFile(queueFile) ?: return
        database.withTransaction {
            if (isLegacyQueueFileImportBlocked()) return@withTransaction
            parsed.sortedBy { it.order }.forEach { entry ->
                DownloadExecutionRoomStore.upsert(
                    context = appContext,
                    request = DownloadExecutionRequest(
                        operationId = entry.operationId ?: pendingOperationId(entry.stableKey),
                        song = entry.song,
                        userInitiated = false
                    ),
                    state = PENDING_QUEUE_STATE,
                    queueOrder = entry.order,
                    createdAtMs = entry.queuedAtMs,
                    database = database
                )
            }
            markPrimary(PENDING_QUEUE_CUTOVER_STATE_KEY)
        }
    }

    private suspend fun bootstrapLegacyCancelledFile() {
        val cancelledFile = File(appContext.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
        if (isLegacyQueueImportSuppressed()) return
        if (isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)) return
        if (!cancelledFile.isFile) {
            database.withTransaction {
                if (
                    !isLegacyQueueImportSuppressed() &&
                        !isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY) &&
                        !cancelledFile.isFile
                ) {
                    markPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
                }
            }
            return
        }
        val parsed = ManagedDownloadQueueStore.readCancelledDownloadKeysFile(cancelledFile) ?: return
        database.withTransaction {
            if (
                isLegacyQueueImportSuppressed() ||
                    isRoomPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
            ) {
                return@withTransaction
            }
            bootstrapLegacyQueueFile()
            parsed.filter(String::isNotBlank).distinct().forEach { stableKey ->
                val existing = DownloadExecutionRoomStore.listByStates(
                    context = appContext,
                    states = PENDING_QUEUE_VISIBLE_STATES + listOf(
                        "QUEUED", WAITING_STORAGE_MUTATION_OPERATION_STATE, "RUNNING", "STOPPED",
                        "RETRYABLE", "CANCEL_REQUESTED", "CANCELLED"
                    ),
                    database = database
                ).filter { it.request.song.stableKey() == stableKey }
                if (existing.isEmpty()) {
                    // legacy cancellation markers without a durable operation have no owner
                    // and must not become synthetic runtime operations
                } else {
                    existing.forEach { entry ->
                        if (entry.request.operationId.isNotBlank()) {
                            DownloadExecutionRoomStore.requestCancel(
                                context = appContext,
                                operationId = entry.request.operationId,
                                database = database
                            )
                        }
                    }
                }
            }
            markPrimary(CANCELLED_KEYS_CUTOVER_STATE_KEY)
        }
    }

    private suspend fun isRoomPrimary(key: String): Boolean {
        return database.syncMetadataDao().getMigrationMetadata(key)?.value == ROOM_PRIMARY_STATE
    }

    private suspend fun isLegacyQueueFileImportBlocked(): Boolean {
        val state = database.syncMetadataDao()
            .getMigrationMetadata(PENDING_QUEUE_CUTOVER_STATE_KEY)
            ?.value
        return state == ROOM_PRIMARY_STATE || isLegacyQueueImportSuppressed(state)
    }

    private suspend fun isLegacyQueueImportSuppressed(): Boolean {
        val state = database.syncMetadataDao()
            .getMigrationMetadata(PENDING_QUEUE_CUTOVER_STATE_KEY)
            ?.value
        return isLegacyQueueImportSuppressed(state)
    }

    private suspend fun markLegacyQueueImportSuppressed() {
        val nowMs = System.currentTimeMillis()
        listOf(PENDING_QUEUE_CUTOVER_STATE_KEY, CANCELLED_KEYS_CUTOVER_STATE_KEY).forEach { key ->
            database.syncMetadataDao().upsertMigrationMetadata(
                MigrationMetadataEntity(
                    key = key,
                    value = USER_CLEARED_STATE,
                    updatedAt = nowMs
                )
            )
        }
    }

    private suspend fun markPrimary(key: String) {
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = key,
                value = ROOM_PRIMARY_STATE,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val PENDING_QUEUE_CUTOVER_STATE_KEY = "download_pending_queue_cutover_state"
        const val CANCELLED_KEYS_CUTOVER_STATE_KEY = "download_cancelled_keys_cutover_state"
        const val ROOM_PRIMARY_STATE = "room_primary"
        const val USER_CLEARED_STATE = "user_cleared"
        private const val PENDING_QUEUE_STATE = "QUEUED"
        private const val DOWNLOAD_OPERATION_QUERY_CHUNK_SIZE = 900
        private val PENDING_QUEUE_STATES = listOf(
            PENDING_QUEUE_STATE,
            "PENDING_QUEUE"
        )
        private val CLEARABLE_PENDING_QUEUE_STATES = PENDING_QUEUE_STATES +
            "RETRYABLE" +
            WAITING_STORAGE_MUTATION_OPERATION_STATE
        private val WAITING_STORAGE_MUTATION_BLOCKING_STATES = listOf(
            "CANCEL_REQUESTED",
            "CANCELLED",
            "STOPPED"
        )
        private val WAITING_STORAGE_MUTATION_REPLACED_TERMINAL_STATES = setOf(
            "COMPLETED",
            "FINALIZED",
            "INVALID"
        )
        private val REPLACEMENT_OPERATION_STATES = listOf(
            "PENDING_QUEUE",
            "QUEUED",
            WAITING_STORAGE_MUTATION_OPERATION_STATE,
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE",
            "CANCEL_REQUESTED",
            "STOPPED",
            "RETRYABLE"
        )
        private val PENDING_QUEUE_VISIBLE_STATES = listOf(
            PENDING_QUEUE_STATE,
            "CANCEL_REQUESTED",
            "PENDING_QUEUE",
            "RETRYABLE"
        )
        private fun pendingOperationId(stableKey: String): String {
            return UUID.nameUUIDFromBytes(
                "pending-download:$stableKey".toByteArray(Charsets.UTF_8)
            ).toString()
        }

        internal fun waitingStorageMutationOperationId(
            libraryId: String,
            stableKey: String,
            clearEpoch: Long = 0L
        ): String {
            val identity = if (clearEpoch > 0L) {
                "waiting-storage-mutation:$libraryId:$clearEpoch:$stableKey"
            } else {
                // 保留首代 operation 身份，兼容已经落盘的旧数据库
                "waiting-storage-mutation:$libraryId:$stableKey"
            }
            return UUID.nameUUIDFromBytes(
                identity.toByteArray(Charsets.UTF_8)
            ).toString()
        }

    }
}
