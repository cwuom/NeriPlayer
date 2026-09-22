package moe.ouom.neriplayer.core.download.execution.persistence

import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.recovery.CLEARED_ARTIFACT_RECOVERY_STOP_STATES
import android.content.Context
import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import java.util.UUID
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.CancellationSnapshot
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.OperationIdentity
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.StateEntry

/**
 * 固化下载取消快照和终态清理的 Room 边界
 *
 * 所有批量取消继续只作用于调用方固定的 operation 集合，不能扫描并删除新代次任务
 */
internal object DownloadExecutionRoomCancellationStore {
    suspend fun listCancellationCandidates(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        return DownloadExecutionRoomReadStore.listByStates(
            context = context,
            states = DownloadExecutionRoomStore.Access.CANCELLATION_CANDIDATE_OPERATION_STATES,
            database = database
        )
    }

    suspend fun listAllOperationIds(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<String> {
        return listAllOperationIdentities(context, database)
            .map(OperationIdentity::operationId)
            .distinct()
    }

    suspend fun listAllOperationIdentities(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<OperationIdentity> {
        val dao = database.downloadOperationDao()
        val identities = mutableListOf<OperationIdentity>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findAllOperationIdentitiesAfterOperationId(
                afterOperationId = afterOperationId,
                limit = DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) {
                break
            }
            identities += page.map { row ->
                OperationIdentity(
                    operationId = row.operationId,
                    stableKey = row.stableKey
                )
            }
            val nextOperationId = page.last().operationId
            if (nextOperationId <= afterOperationId) {
                break
            }
            afterOperationId = nextOperationId
            if (page.size < DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE) {
                break
            }
        }
        return identities
    }

    /** 清空恢复需要跨 library 捕获所有仍可能持有 staging lease 的 operation */
    suspend fun listCancellationCandidatesAnyLibrary(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        return DownloadExecutionRoomReadStore.listByStatesAnyLibrary(
            context = context,
            states = DownloadExecutionRoomStore.Access.CANCELLATION_CANDIDATE_OPERATION_STATES,
            database = database
        )
    }

    /** 只返回带用户取消凭据的 operation，避免新请求被旧 stableKey 误取消 */
    suspend fun findUserCancellationOperationIdsForSong(
        context: Context,
        songKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        createdAtMsAtMost: Long? = null
    ): List<String> {
        val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return emptyList()
        return database.downloadOperationDao()
            .findAllHeadersByStableKeyAnyLibrary(
                stableKey = normalizedKey,
                states = DownloadExecutionRoomStore.Access.CANCELLATION_CANDIDATE_OPERATION_STATES
            )
            .asSequence()
            .filter { header ->
                createdAtMsAtMost == null || header.createdAtMs <= createdAtMsAtMost
            }
            .filter { header ->
                header.state in setOf("CANCEL_REQUESTED", "CANCELLED", "STOPPED") ||
                    header.stopRequestedByUser ||
                    header.lastErrorCode == "USER_CANCELLED"
            }
            .map(DownloadOperationHeaderRow::operationId)
            .distinct()
            .toList()
    }

    /** 清空也捕获失败历史，让重启恢复能读取到保留音频的用户停止凭据 */
    suspend fun listCancellationIdentitiesAnyLibrary(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<OperationIdentity> {
        val dao = database.downloadOperationDao()
        val identities = mutableListOf<OperationIdentity>()
        val clearOwnershipStates = (
            DownloadExecutionRoomStore.Access.CANCELLATION_CANDIDATE_OPERATION_STATES +
                CLEARED_ARTIFACT_RECOVERY_STOP_STATES
            ).distinct()
        var afterOperationId = ""
        while (true) {
            val page = dao.findCancellationIdentitiesAfterOperationId(
                states = clearOwnershipStates,
                afterOperationId = afterOperationId,
                limit = DownloadExecutionRoomStore.Access.CANCELLATION_QUERY_PAGE_SIZE
            )
            if (page.isEmpty()) break
            identities += page.map { row ->
                OperationIdentity(
                    operationId = row.operationId,
                    stableKey = row.stableKey,
                    createdAtMs = row.createdAtMs
                )
            }
            val nextOperationId = page.last().operationId
            if (nextOperationId <= afterOperationId ||
                page.size < DownloadExecutionRoomStore.Access.CANCELLATION_QUERY_PAGE_SIZE
            ) {
                break
            }
            afterOperationId = nextOperationId
        }
        return identities
    }

    /** 只读取清空开始时已拥有 stableKey 的 operation，避免纳入新 generation */
    suspend fun listOperationIdentitiesForStableKeys(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<OperationIdentity> {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return emptyList()
        return keys.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
            database.downloadOperationDao()
                .findAllHeadersByStableKeysAnyLibrary(
                    stableKeys = chunk,
                    states = DownloadExecutionRoomStore.Access.CANCELLATION_CANDIDATE_OPERATION_STATES
                )
                .map { header ->
                    OperationIdentity(
                        operationId = header.operationId,
                        stableKey = header.stableKey,
                        createdAtMs = header.createdAtMs
                    )
                }
        }.distinctBy(OperationIdentity::operationId)
    }

    suspend fun requestCancelAll(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): CancellationSnapshot {
        val requestedAtMs = System.currentTimeMillis()
        val headers = mutableListOf<DownloadOperationHeaderRow>()
        while (true) {
            val page = database.withTransaction {
                val dao = database.downloadOperationDao()
                val candidates = dao.findCancellationCandidatesPageHeaders(
                    states = DownloadExecutionRoomStore.Access.CANCELLATION_CANDIDATE_OPERATION_STATES,
                    limit = DownloadExecutionRoomStore.Access.CANCELLATION_QUERY_PAGE_SIZE
                )
                val directCancellationIds = candidates.asSequence()
                    .filter { header -> DownloadExecutionRoomStore.Access.requiresDirectCancellation(header) }
                    .map(DownloadOperationHeaderRow::operationId)
                    .toList()
                val commitBoundaryCancellationIds = candidates.asSequence()
                    .filter { header -> DownloadExecutionRoomStore.Access.requiresCommitBoundaryCancellation(header) }
                    .map(DownloadOperationHeaderRow::operationId)
                    .toList()
                directCancellationIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { ids ->
                    dao.requestCancellations(ids, requestedAtMs)
                }
                commitBoundaryCancellationIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { ids ->
                    dao.requestCommitBoundaryCancellations(ids, requestedAtMs)
                }
                candidates
            }
            if (page.isEmpty()) {
                break
            }
            headers += page
        }
        val dao = database.downloadOperationDao()
        val refreshedHeadersByOperationId = linkedMapOf<String, DownloadOperationHeaderRow>()
        headers.map(DownloadOperationHeaderRow::operationId)
            .chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE)
            .forEach { operationIdChunk ->
                dao.findAllHeadersByOperationIds(operationIdChunk).forEach { header ->
                    refreshedHeadersByOperationId[header.operationId] = header
                }
            }
        val entries = mutableListOf<StateEntry>()
        headers.forEach { header ->
            if (
                !DownloadExecutionRoomStore.Access.requiresDirectCancellation(header) &&
                    !DownloadExecutionRoomStore.Access.requiresCommitBoundaryCancellation(header)
            ) {
                return@forEach
            }
            val refreshedHeader = refreshedHeadersByOperationId[header.operationId]
                ?: return@forEach
            val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, refreshedHeader)
            val request = decoded.request
            if (request == null) {
                if (decoded.payloadWasRead) {
                    DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, refreshedHeader)
                }
                return@forEach
            }
            entries += StateEntry(
                request = request,
                queueOrder = header.queueOrder,
                createdAtMs = header.createdAtMs,
                state = header.state,
                updatedAtMs = header.updatedAtMs
            )
        }
        return CancellationSnapshot(
            entries = entries,
            operationIds = headers.map(DownloadOperationHeaderRow::operationId).distinct(),
            stableKeys = headers.mapTo(linkedSetOf(), DownloadOperationHeaderRow::stableKey),
            requestedAtMs = requestedAtMs
        )
    }

    /** 用户清空的快速阶段，用集合更新写入取消栅栏，详细凭据由恢复流程收集 */
    suspend fun requestCancelAllFast(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val requestedAtMs = System.currentTimeMillis()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            dao.requestAllCancellationsFast(requestedAtMs) +
                dao.requestAllCommitBoundaryCancellationsFast(requestedAtMs)
        }
    }

    /** 快速阶段只标记清空开始时拥有 stableKey 的 operation */
    suspend fun requestCancelForStableKeysFast(
        context: Context,
        stableKeys: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val identities = listOperationIdentitiesForStableKeys(
            context = context,
            stableKeys = stableKeys,
            database = database
        )
        return requestCancelOperationsFast(
            context = context,
            operationIds = identities.map(OperationIdentity::operationId),
            database = database
        )
    }

    /** 持久收敛只取消固定快照中的 operation，不扫描清空后的新任务 */
    suspend fun requestCancelOperations(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): CancellationSnapshot {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) {
            return CancellationSnapshot(
                entries = emptyList(),
                operationIds = emptyList(),
                stableKeys = emptySet(),
                requestedAtMs = System.currentTimeMillis()
            )
        }
        val requestedAtMs = System.currentTimeMillis()
        val headers = database.withTransaction {
            val dao = database.downloadOperationDao()
            val candidates = ids.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
                dao.findAllHeadersByOperationIds(chunk)
            }
            val directIds = candidates.asSequence()
                .filter { header -> DownloadExecutionRoomStore.Access.requiresDirectCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            val commitBoundaryIds = candidates.asSequence()
                .filter { header -> DownloadExecutionRoomStore.Access.requiresCommitBoundaryCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            directIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
                dao.requestCancellations(chunk, requestedAtMs)
            }
            commitBoundaryIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
                dao.requestCommitBoundaryCancellations(chunk, requestedAtMs)
            }
            candidates
        }
        val dao = database.downloadOperationDao()
        val refreshedHeadersByOperationId = linkedMapOf<String, DownloadOperationHeaderRow>()
        headers.map(DownloadOperationHeaderRow::operationId)
            .chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE)
            .forEach { operationIdChunk ->
                dao.findAllHeadersByOperationIds(operationIdChunk).forEach { header ->
                    refreshedHeadersByOperationId[header.operationId] = header
                }
            }
        val entries = mutableListOf<StateEntry>()
        headers.forEach { header ->
            if (
                !DownloadExecutionRoomStore.Access.requiresDirectCancellation(header) &&
                    !DownloadExecutionRoomStore.Access.requiresCommitBoundaryCancellation(header)
            ) {
                return@forEach
            }
            val refreshedHeader = refreshedHeadersByOperationId[header.operationId]
                ?: return@forEach
            val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, refreshedHeader)
            val request = decoded.request
            if (request == null) {
                if (decoded.payloadWasRead) {
                    DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, refreshedHeader)
                }
                return@forEach
            }
            entries += StateEntry(
                request = request,
                queueOrder = header.queueOrder,
                createdAtMs = header.createdAtMs,
                state = header.state,
                updatedAtMs = header.updatedAtMs
            )
        }
        return CancellationSnapshot(
            entries = entries,
            operationIds = headers.map(DownloadOperationHeaderRow::operationId).distinct(),
            stableKeys = headers.map(DownloadOperationHeaderRow::stableKey).toSet(),
            requestedAtMs = requestedAtMs
        )
    }

    /** 快速标记固定 operation，避免全局 UPDATE 触碰新 generation */
    suspend fun requestCancelOperationsFast(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return 0
        val requestedAtMs = System.currentTimeMillis()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val headers = ids.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
                dao.findAllHeadersByOperationIds(chunk)
            }
            val directIds = headers.asSequence()
                .filter { header -> DownloadExecutionRoomStore.Access.requiresDirectCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            val commitBoundaryIds = headers.asSequence()
                .filter { header -> DownloadExecutionRoomStore.Access.requiresCommitBoundaryCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            directIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
                dao.requestCancellations(chunk, requestedAtMs)
            } + commitBoundaryIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
                dao.requestCommitBoundaryCancellations(chunk, requestedAtMs)
            }
        }
    }

    suspend fun finalizeRequestedCancellations(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return 0
        val updatedAtMs = System.currentTimeMillis()
        return ids.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            database.downloadOperationDao().finalizeRequestedCancellations(
                operationIds = chunk,
                updatedAtMs = updatedAtMs
            )
        }
    }

    suspend fun deleteByStateAndStableKeys(
        context: Context,
        state: String,
        stableKeys: List<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return
        keys.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
            database.withTransaction {
                val dao = database.downloadOperationDao()
                val operationIds = dao.findOperationIdsByStateAndStableKeys(state, chunk)
                if (operationIds.isNotEmpty()) {
                    dao.deleteHostAdmissions(operationIds)
                    dao.deleteOperations(operationIds)
                }
            }
        }
    }

    suspend fun deleteByState(
        context: Context,
        state: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        val ids = database.downloadOperationDao().findOperationIdsByState(state)
        DownloadExecutionRoomStore.Access.deleteOperationsWithAdmissions(database, ids)
    }

    suspend fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        if (limit <= 0) return 0
        val ids = database.downloadOperationDao().findTerminalOperationIdsBefore(
            states = DownloadExecutionRoomStore.Access.TERMINAL_STATES,
            cutoffMs = cutoffMs,
            limit = limit
        )
        return DownloadExecutionRoomStore.Access.deleteOperationsWithAdmissions(database, ids)
    }

    suspend fun findOperationIdForSong(
        context: Context,
        songKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        states: List<String> = DownloadExecutionRoomStore.Access.ACTIVE_OPERATION_STATES
    ): String? {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return null
        val libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context)
        val dao = database.downloadOperationDao()
        return dao.findLatestOperationIdByStableKey(
                libraryId = libraryId,
                stableKey = normalizedSongKey,
                states = states
            ) ?: dao.findAllHeadersByStableKeyAnyLibrary(
                stableKey = normalizedSongKey,
                states = states
            ).firstOrNull { header -> !header.stopRequestedByUser }?.also { header ->
                DownloadExecutionRoomStore.rehomeOperationToCurrentLibrary(
                    context = context,
                    operationId = header.operationId,
                    stableKey = normalizedSongKey,
                    states = states,
                    database = database
                )
            }?.operationId
    }

    suspend fun findOperationIdsForSong(
        context: Context,
        songKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        states: List<String> = DownloadExecutionRoomStore.Access.CANCELLATION_CANDIDATE_OPERATION_STATES,
        createdAtMsAtMost: Long? = null
    ): List<String> {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return emptyList()
        val libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context)
        val dao = database.downloadOperationDao()
        // 目录迁移可能在新根目录先写入一行，而旧根目录仍保留活动行。
        // 取消必须覆盖两边，否则旧宿主和旧 lease 会继续挡住下一次下载
        val rows = (
            dao.findAllHeadersByStableKey(libraryId, normalizedSongKey, states) +
                dao.findAllHeadersByStableKeyAnyLibrary(normalizedSongKey, states)
            ).distinctBy(DownloadOperationHeaderRow::operationId)
        val eligibleRows = rows.filter { header ->
            createdAtMsAtMost == null || header.createdAtMs <= createdAtMsAtMost
        }
        eligibleRows.filter { header -> header.libraryId != libraryId }.forEach { header ->
            DownloadExecutionRoomStore.rehomeOperationToCurrentLibrary(
                context = context,
                operationId = header.operationId,
                stableKey = normalizedSongKey,
                states = states,
                database = database
            )
        }
        return eligibleRows.map(DownloadOperationHeaderRow::operationId).distinct()
    }

    suspend fun findReadableOperationIdForSong(
        context: Context,
        songKey: String,
        states: List<String>,
        excludeUserCancelledStops: Boolean = false,
        excludeUserStoppedOperations: Boolean = false,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): String? {
        val normalizedKey = songKey.trim().takeIf(String::isNotBlank) ?: return null
        return findReadableOperationsBySongKeys(
            context = context,
            songKeys = listOf(normalizedKey),
            states = states,
            excludeUserCancelledStops = excludeUserCancelledStops,
            excludeUserStoppedOperations = excludeUserStoppedOperations,
            database = database
        )[normalizedKey]?.operationId
    }

    suspend fun findReadableOperationsBySongKeys(
        context: Context,
        songKeys: Collection<String>,
        states: List<String>,
        excludeUserCancelledStops: Boolean = false,
        excludeUserStoppedOperations: Boolean = false,
        excludedOperationIds: Collection<String> = emptySet(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Map<String, DownloadExecutionRequest> {
        val normalizedKeys = songKeys
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedKeys.isEmpty() || states.isEmpty()) {
            return emptyMap()
        }
        val excludedIds = excludedOperationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context)
        val dao = database.downloadOperationDao()
        val readableOperations = linkedMapOf<String, DownloadExecutionRequest>()
        normalizedKeys.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { keyChunk ->
            dao.findAllHeadersByStableKeys(
                libraryId = libraryId,
                stableKeys = keyChunk,
                states = states
            ).forEach headerLoop@{ header ->
                val entitySongKey = header.stableKey
                if (header.operationId in excludedIds) {
                    return@headerLoop
                }
                if (entitySongKey in readableOperations) {
                    return@headerLoop
                }
                if (
                    header.stopRequestedByUser && (
                        excludeUserStoppedOperations ||
                            (excludeUserCancelledStops &&
                                header.lastErrorCode == "USER_CANCELLED")
                    )
                ) {
                    return@headerLoop
                }
                val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request != null) {
                    readableOperations[entitySongKey] = request
                } else if (decoded.payloadWasRead) {
                    DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header)
                }
            }
        }
        val unresolvedKeys = normalizedKeys.filterNot { key -> key in readableOperations }
        unresolvedKeys.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { keyChunk ->
            dao.findAllHeadersByStableKeysAnyLibrary(
                stableKeys = keyChunk,
                states = states
            ).forEach headerLoop@{ header ->
                val entitySongKey = header.stableKey
                if (header.operationId in excludedIds) {
                    return@headerLoop
                }
                if (entitySongKey in readableOperations) {
                    return@headerLoop
                }
                if (
                    header.stopRequestedByUser && (
                        excludeUserStoppedOperations ||
                            (excludeUserCancelledStops &&
                                header.lastErrorCode == "USER_CANCELLED")
                    )
                ) {
                    return@headerLoop
                }
                val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) {
                        DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header)
                    }
                    return@headerLoop
                }
                if (header.libraryId != libraryId) {
                    DownloadExecutionRoomStore.rehomeOperationToCurrentLibrary(
                        context = context,
                        operationId = header.operationId,
                        stableKey = header.stableKey,
                        states = states,
                        database = database
                    )
                }
                readableOperations[entitySongKey] = request
            }
        }
        return readableOperations
    }

    /**
     * 只有调用方提供同一稳定键的新歌曲载荷时，才恢复可复用的日志记录
     */
    suspend fun rehydrateMalformedReusableOperation(
        context: Context,
        song: SongItem,
        userInitiated: Boolean,
        requiresWifiNetwork: Boolean,
        updatedAtMs: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return false
        return stableKey in rehydrateMalformedReusableOperations(
            context = context,
            songs = listOf(song),
            userInitiated = userInitiated,
            requiresWifiNetwork = requiresWifiNetwork,
            updatedAtMs = updatedAtMs,
            database = database
        )
    }

    suspend fun rehydrateMalformedReusableOperations(
        context: Context,
        songs: Collection<SongItem>,
        userInitiated: Boolean,
        requiresWifiNetwork: Boolean,
        updatedAtMs: Long,
        downloadAudioQuality: DownloadAudioQualitySelection? = null,
        excludedOperationIds: Collection<String> = emptySet(),
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Set<String> {
        val songsByStableKey = linkedMapOf<String, SongItem>()
        songs.forEach { song ->
            val stableKey = song.stableKey().trim().takeIf(String::isNotBlank) ?: return@forEach
            songsByStableKey.putIfAbsent(stableKey, song)
        }
        if (songsByStableKey.isEmpty()) {
            return emptySet()
        }
        val excludedIds = excludedOperationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context)
        val persistedNetworkPolicies = mutableListOf<Triple<String, Boolean, Long>>()
        val rehydratedStableKeys = database.withTransaction {
            val dao = database.downloadOperationDao()
            val candidatesByStableKey = linkedMapOf<String, MutableList<DownloadOperationHeaderRow>>()
            songsByStableKey.keys.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { stableKeyChunk ->
                dao.findAllHeadersByStableKeys(
                    libraryId = libraryId,
                    stableKeys = stableKeyChunk,
                    states = DownloadExecutionRoomStore.Access.REUSABLE_OPERATION_STATES
                ).forEach { header ->
                    if (header.operationId in excludedIds) return@forEach
                    candidatesByStableKey.getOrPut(header.stableKey) { mutableListOf() } += header
                }
            }
            val recoveredKeys = linkedSetOf<String>()
            songsByStableKey.forEach { (stableKey, song) ->
                val candidates = candidatesByStableKey[stableKey]
                    .orEmpty()
                    .filterNot(DownloadOperationHeaderRow::stopRequestedByUser)
                if (candidates.isEmpty()) {
                    return@forEach
                }
                val decodedCandidates = candidates.map { header ->
                    header to DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                }
                if (decodedCandidates.any { (_, decoded) -> decoded.request != null }) {
                    return@forEach
                }
                val existing = decodedCandidates.firstOrNull { (_, decoded) ->
                    decoded.payloadWasRead
                }?.first ?: return@forEach
                val request = DownloadExecutionRequest(
                    operationId = existing.operationId,
                    song = song,
                    artifactLeaseId = UUID.randomUUID().toString(),
                    requiresWifiNetwork = requiresWifiNetwork,
                    userInitiated = userInitiated,
                    downloadAudioQuality = downloadAudioQuality
                )
                val payloadUpdatedAtMs = DownloadExecutionRoomStore.Access.nextPayloadUpdatedAt(
                    previousUpdatedAtMs = existing.updatedAtMs,
                    requestedAtMs = updatedAtMs
                )
                val replaced = dao.replaceMalformedReusablePayload(
                    operationId = existing.operationId,
                    libraryId = libraryId,
                    stableKey = stableKey,
                    expectedStates = DownloadExecutionRoomStore.Access.REUSABLE_OPERATION_STATES,
                    sourceHintJson = DownloadExecutionRoomStore.Access.requestToJson(request).toString(),
                    updatedAtMs = payloadUpdatedAtMs
                ) > 0
                if (replaced) {
                    dao.deleteHostAdmission(existing.operationId)
                    recoveredKeys += stableKey
                    persistedNetworkPolicies += Triple(
                        existing.operationId,
                        requiresWifiNetwork,
                        payloadUpdatedAtMs
                    )
                }
            }
            recoveredKeys
        }
        persistedNetworkPolicies.forEach { (operationId, requiresWifi, payloadUpdatedAtMs) ->
            DownloadExecutionRoomStore.cacheNetworkPolicy(
                operationId = operationId,
                requiresWifiNetwork = requiresWifi,
                updatedAtMs = payloadUpdatedAtMs
            )
        }
        return rehydratedStableKeys
    }

}
