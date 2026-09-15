package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.withTransaction
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.HeaderRequestRead
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.OperationIdentity
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.OperationRequestMetadata
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.OperationSnapshot
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.ProgressCheckpoint
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.ProgressEntry
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore.StateEntry
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.DownloadOperationDao
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import org.json.JSONObject

/**
 * Room operation 的只读、分页和进度查询边界
 *
 * 读取大载荷时仍由 facade 的分段解码器处理，避免查询层重新引入 CursorWindow 风险
 */
internal object DownloadExecutionRoomReadStore {
    suspend fun read(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): DownloadExecutionRequest? {
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction null
            DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header).request
        }
    }

    suspend fun readOperationSnapshots(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Map<String, OperationSnapshot> {
        val normalizedOperationIds = operationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedOperationIds.isEmpty()) {
            return emptyMap()
        }
        val snapshots = linkedMapOf<String, OperationSnapshot>()
        normalizedOperationIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { operationIdChunk ->
            val chunkSnapshots = database.withTransaction {
                val dao = database.downloadOperationDao()
                dao.findAllHeadersByOperationIds(operationIdChunk).map { header ->
                    val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                    val request = decoded.request
                    header to HeaderRequestRead(
                        request = request,
                        payloadWasRead = decoded.payloadWasRead
                    )
                }
            }
            chunkSnapshots.forEach { (header, decoded) ->
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) {
                        DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header)
                    }
                    return@forEach
                }
                snapshots[header.operationId] = OperationSnapshot(
                    request = request,
                    state = header.state
                )
            }
        }
        return snapshots
    }

    /** 批量读取 operation 表头，不把歌词等大载荷装入内存 */
    suspend fun readOperationHeaders(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Map<String, DownloadOperationHeaderRow> {
        val normalizedOperationIds = operationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedOperationIds.isEmpty()) return emptyMap()
        return normalizedOperationIds
            .chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE)
            .flatMap { operationIdChunk ->
                database.downloadOperationDao().findAllHeadersByOperationIds(operationIdChunk)
            }
            .associateBy(DownloadOperationHeaderRow::operationId)
    }

    /** 读取调度所需的小字段，避免为批量任务解码完整歌曲和歌词 */
    suspend fun readOperationRequestMetadata(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Map<String, OperationRequestMetadata> {
        val normalizedOperationIds = operationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedOperationIds.isEmpty()) return emptyMap()
        val metadata = linkedMapOf<String, OperationRequestMetadata>()
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        normalizedOperationIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { operationIdChunk ->
            val chunkMetadata = database.withTransaction {
                val dao = database.downloadOperationDao()
                dao.findAllHeadersByOperationIds(operationIdChunk).mapNotNull { header ->
                    val sourceHintJson = DownloadExecutionRoomStore.Access.readSourceHintJson(dao, header)
                    val root = sourceHintJson?.let { json ->
                        runCatching { JSONObject(json) }.getOrNull()
                    }
                    val sourceStableKey = root?.optString("sourceStableKey")
                        ?.takeIf(String::isNotBlank)
                    val artifactLeaseId = root?.optString("artifactLeaseId")
                        ?.takeIf(String::isNotBlank)
                    if (
                        root == null ||
                            root.optInt("schemaVersion") != DownloadExecutionRoomStore.Access.JOURNAL_PAYLOAD_VERSION ||
                            root.optJSONObject("song") == null ||
                            sourceStableKey != null && sourceStableKey != header.stableKey
                    ) {
                        if (sourceHintJson != null) malformedHeaders += header
                        return@mapNotNull null
                    }
                    OperationRequestMetadata(
                        operationId = header.operationId,
                        stableKey = header.stableKey,
                        state = header.state,
                        preserveStaging = root.optBoolean("preserveStaging", false),
                        requiresWifiNetwork = if (root.has("requiresWifiNetwork")) {
                            root.optBoolean("requiresWifiNetwork", true)
                        } else {
                            true
                        },
                        attemptId = root.optLong("attemptId", 0L)
                            .takeIf { attemptId -> attemptId > 0L },
                        artifactLeaseId = artifactLeaseId ?: header.operationId,
                        userInitiated = if (root.has("userInitiated")) {
                            root.optBoolean("userInitiated", false)
                        } else {
                            false
                        },
                        downloadAudioQuality = root.optJSONObject("downloadAudioQuality")
                            ?.let { quality ->
                                DownloadAudioQualitySelection.normalized(
                                    neteaseQuality = quality.optString("neteaseQuality"),
                                    youtubeQuality = quality.optString("youtubeQuality"),
                                    biliQuality = quality.optString("biliQuality")
                                )
                            }
                    )
                }
            }
            chunkMetadata.forEach { item -> metadata[item.operationId] = item }
        }
        malformedHeaders.forEach { header -> DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header) }
        return metadata
    }

    /**
     * 按 stable key 读取最新网络策略。这里只返回 SQLite 投影出的布尔值，
     * 不解析请求中的歌曲、歌词和封面载荷
     */
    suspend fun readLatestOperationNetworkPoliciesForStableKeys(
        context: Context,
        stableKeys: Collection<String>,
        states: List<String>,
        excludeUserStoppedOperations: Boolean = true,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Map<String, Boolean> {
        val normalizedKeys = stableKeys
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedKeys.isEmpty() || states.isEmpty()) return emptyMap()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val headers = normalizedKeys
                .chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE)
                .flatMap { keyChunk ->
                    dao.findAllHeadersByStableKeysAnyLibrary(
                        stableKeys = keyChunk,
                        states = states
                    )
                }
            readLatestNetworkPolicies(
                dao = dao,
                headers = headers,
                excludeUserStoppedOperations = excludeUserStoppedOperations
            )
        }
    }

    /** 扫描轻量表头后只投影每首歌最新 operation 的网络策略 */
    suspend fun readLatestOperationNetworkPoliciesByStatesAnyLibrary(
        context: Context,
        states: List<String>,
        excludeUserStoppedOperations: Boolean = true,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Map<String, Boolean> {
        if (states.isEmpty()) return emptyMap()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val headers = mutableListOf<DownloadOperationHeaderRow>()
            var afterOperationId = ""
            while (true) {
                val page = dao.findByStatesAfterOperationIdHeaders(
                    states = states,
                    afterOperationId = afterOperationId,
                    limit = DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE
                )
                if (page.isEmpty()) break
                headers += page
                val nextOperationId = page.last().operationId
                if (nextOperationId <= afterOperationId) break
                afterOperationId = nextOperationId
                if (page.size < DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE) break
            }
            readLatestNetworkPolicies(
                dao = dao,
                headers = headers,
                excludeUserStoppedOperations = excludeUserStoppedOperations
            )
        }
    }

    private suspend fun readLatestNetworkPolicies(
        dao: DownloadOperationDao,
        headers: Collection<DownloadOperationHeaderRow>,
        excludeUserStoppedOperations: Boolean
    ): Map<String, Boolean> {
        val latestHeadersByStableKey = linkedMapOf<String, DownloadOperationHeaderRow>()
        headers.forEach { header ->
            if (excludeUserStoppedOperations && header.stopRequestedByUser) {
                return@forEach
            }
            val current = latestHeadersByStableKey[header.stableKey]
            if (current == null || isNewerNetworkPolicyHeader(header, current)) {
                latestHeadersByStableKey[header.stableKey] = header
            }
        }
        if (latestHeadersByStableKey.isEmpty()) return emptyMap()
        val latestHeadersByOperationId = latestHeadersByStableKey.values
            .associateBy(DownloadOperationHeaderRow::operationId)
        val policiesByOperationId = linkedMapOf<String, Boolean>()
        val uncachedOperationIds = latestHeadersByStableKey.values.mapNotNull { header ->
            val cached = DownloadExecutionRoomStore.cachedNetworkPolicy(header.operationId)
                ?: return@mapNotNull header.operationId
            policiesByOperationId[header.operationId] = cached
            null
        }
        val operationIdChunks = uncachedOperationIds
            .chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE)
        for (operationIds in operationIdChunks) {
            for (policy in dao.findNetworkPoliciesByOperationIds(operationIds)) {
                policiesByOperationId[policy.operationId] = policy.requiresWifiNetwork
                DownloadExecutionRoomStore.cacheNetworkPolicy(
                    operationId = policy.operationId,
                    requiresWifiNetwork = policy.requiresWifiNetwork,
                    updatedAtMs = latestHeadersByOperationId[policy.operationId]
                        ?.updatedAtMs ?: continue
                )
            }
        }
        return latestHeadersByStableKey.mapValues { (_, header) ->
            policiesByOperationId[header.operationId] ?: true
        }
    }

    private fun isNewerNetworkPolicyHeader(
        candidate: DownloadOperationHeaderRow,
        current: DownloadOperationHeaderRow
    ): Boolean {
        return when {
            candidate.createdAtMs != current.createdAtMs ->
                candidate.createdAtMs > current.createdAtMs
            candidate.updatedAtMs != current.updatedAtMs ->
                candidate.updatedAtMs > current.updatedAtMs
            else -> candidate.operationId > current.operationId
        }
    }

    suspend fun promoteWaitingStorageMutations(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val normalizedOperationIds = operationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedOperationIds.isEmpty()) return 0
        return database.withTransaction {
            database.downloadOperationDao().promoteWaitingStorageMutations(
                operationIds = normalizedOperationIds,
                libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context),
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    /** 批量提升失败时仍需知道该 operation 属于哪首歌，不能让一条坏记录中止整批 */
    suspend fun readOperationIdentities(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Map<String, OperationIdentity> {
        val normalizedOperationIds = operationIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedOperationIds.isEmpty()) return emptyMap()
        val identities = linkedMapOf<String, OperationIdentity>()
        normalizedOperationIds.chunked(DownloadExecutionRoomStore.Access.SQLITE_IN_QUERY_CHUNK_SIZE).forEach { operationIdChunk ->
            database.downloadOperationDao().findAllHeadersByOperationIds(operationIdChunk)
                .forEach { header ->
                    identities[header.operationId] = OperationIdentity(
                        operationId = header.operationId,
                        stableKey = header.stableKey,
                        createdAtMs = header.createdAtMs
                    )
                }
        }
        return identities
    }

    suspend fun checkpointProgress(
        context: Context,
        operationId: String,
        stableKey: String,
        attemptId: Long?,
        bytesWritten: Long,
        totalBytes: Long?,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        val normalizedAttemptId = attemptId?.takeIf { it > 0L } ?: return false
        val normalizedTotalBytes = totalBytes?.takeIf { it > 0L }
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction false
            if (
                header.stableKey != normalizedKey ||
                    header.state !in DownloadExecutionRoomStore.Access.PROGRESS_CHECKPOINT_OPERATION_STATES ||
                    header.stopRequestedByUser
            ) {
                return@withTransaction false
            }
            val request = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header).request
                ?: return@withTransaction false
            if (
                request.attemptId != normalizedAttemptId ||
                    request.song.stableKey() != normalizedKey
            ) {
                return@withTransaction false
            }
            dao.updateProgressCheckpointAnyLibrary(
                operationId = operationId,
                stableKey = normalizedKey,
                bytesWritten = bytesWritten.coerceAtLeast(0L),
                totalBytes = normalizedTotalBytes,
                expectedStates = DownloadExecutionRoomStore.Access.PROGRESS_CHECKPOINT_OPERATION_STATES
            ) > 0
        }
    }

    suspend fun readProgressCheckpoint(
        context: Context,
        operationId: String,
        stableKey: String,
        attemptId: Long?,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): ProgressCheckpoint? {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return null
        val normalizedAttemptId = attemptId?.takeIf { it > 0L } ?: return null
        val dao = database.downloadOperationDao()
        val header = dao.findHeader(operationId) ?: return null
        if (
            header.stableKey != normalizedKey ||
                header.state !in DownloadExecutionRoomStore.Access.PROGRESS_CHECKPOINT_OPERATION_STATES
        ) {
            return null
        }
        val request = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header).request ?: return null
        if (
            request.attemptId != normalizedAttemptId ||
                request.song.stableKey() != normalizedKey
        ) {
            return null
        }
        return ProgressCheckpoint(
            bytesWritten = header.bytesWritten.coerceAtLeast(0L),
            totalBytes = header.totalBytes?.takeIf { it > 0L }
        )
    }

    suspend fun listByState(
        context: Context,
        state: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        return listByStates(
            context = context,
            states = listOf(state),
            database = database
        )
    }

    suspend fun countByStates(
        context: Context,
        states: List<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        if (states.isEmpty()) return 0
        return database.downloadOperationDao().countByStatesInLibrary(
            libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context),
            states = states
        )
    }

    /** 给全局下载泵提供有界 keyset 页面，避免 grace 过滤把后续可运行任务饿死 */
    suspend fun listSchedulableForPumpPage(
        context: Context,
        afterCursor: DownloadExecutionPumpCursor?,
        limit: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        nowMs: Long = System.currentTimeMillis()
    ): DownloadExecutionPumpPage {
        val boundedLimit = limit.coerceIn(1, DownloadExecutionRoomStore.Access.PUMP_QUERY_MAX_ITEMS)
        val (headers, decodedRequests, nextRetryAtMs) = database.withTransaction {
            val dao = database.downloadOperationDao()
            val headers = dao.findSchedulableForPumpAfterCursorHeaders(
                states = DownloadExecutionRoomStore.Access.PUMP_OPERATION_STATES,
                afterQueueOrder = afterCursor?.queueOrder,
                afterUpdatedAtMs = afterCursor?.updatedAtMs,
                afterOperationId = afterCursor?.operationId,
                limit = boundedLimit,
                nowMs = nowMs
            )
            Triple(
                headers,
                headers.map { header ->
                    header to DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                },
                // 每个 keyset 页都返回最早 retry 截止时间，避免前页没有可调度行时丢失定时器唤醒
                dao.findEarliestFutureRetryDeadlineForPump(
                    states = DownloadExecutionRoomStore.Access.PUMP_OPERATION_STATES,
                    nowMs = nowMs
                )
            )
        }
        val nextCursor = headers.lastOrNull()
            ?.takeIf { headers.size == boundedLimit }
            ?.let { header ->
                DownloadExecutionPumpCursor(
                    queueOrder = header.queueOrder,
                    updatedAtMs = header.updatedAtMs,
                    operationId = header.operationId
                )
            }
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        val requests = decodedRequests.mapNotNull { (header, decoded) ->
            if (decoded.request == null) {
                if (decoded.payloadWasRead) malformedHeaders += header
                null
            } else {
                decoded.request
            }
        }
        malformedHeaders.forEach { header -> DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header) }
        return DownloadExecutionPumpPage(
            requests = requests,
            nextCursor = nextCursor,
            nextRetryAtMs = nextRetryAtMs
        )
    }

    suspend fun listByStates(
        context: Context,
        states: List<String>,
        excludeUserStoppedOperations: Boolean = false,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        if (states.isEmpty()) return emptyList()
        val libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context)
        val dao = database.downloadOperationDao()
        val entries = mutableListOf<StateEntry>()
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findByStatesInLibraryAfterOperationIdHeaders(
                libraryId = libraryId,
                states = states,
                afterOperationId = afterOperationId,
                limit = DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) {
                break
            }
            page.forEach { header ->
                val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) malformedHeaders += header
                } else if (!excludeUserStoppedOperations || !header.stopRequestedByUser) {
                    entries += StateEntry(
                        request = request,
                        queueOrder = header.queueOrder,
                        createdAtMs = header.createdAtMs,
                        state = header.state,
                        updatedAtMs = header.updatedAtMs
                    )
                }
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
        malformedHeaders.forEach { header -> DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header) }
        entries.sortWith(
            compareBy<StateEntry> { it.queueOrder }
                .thenBy { it.updatedAtMs }
                .thenBy { it.request.operationId }
        )
        return entries
    }

    /**
     * 目录切换或进程重启后仍需看到旧根目录中的持久 operation
     * 分页读取避免一次性把大量历史行装入内存
     */
    suspend fun listByStatesAnyLibrary(
        context: Context,
        states: List<String>,
        excludeUserStoppedOperations: Boolean = false,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        if (states.isEmpty()) return emptyList()
        val dao = database.downloadOperationDao()
        val entries = mutableListOf<StateEntry>()
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findByStatesAfterOperationIdHeaders(
                states = states,
                afterOperationId = afterOperationId,
                limit = DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { header ->
                val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) malformedHeaders += header
                } else if (!excludeUserStoppedOperations || !header.stopRequestedByUser) {
                    entries += StateEntry(
                        request = request,
                        queueOrder = header.queueOrder,
                        createdAtMs = header.createdAtMs,
                        state = header.state,
                        updatedAtMs = header.updatedAtMs
                    )
                }
            }
            val nextOperationId = page.last().operationId
            if (nextOperationId <= afterOperationId) {
                break
            }
            afterOperationId = nextOperationId
            if (page.size < DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE) break
        }
        malformedHeaders.forEach { header -> DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header) }
        entries.sortWith(
            compareBy<StateEntry> { it.queueOrder }
                .thenBy { it.updatedAtMs }
                .thenBy { it.request.operationId }
        )
        return entries
    }

    /** 供网络回调快速判断是否有任务，不加载整批记录 */
    fun hasAnyByStatesAnyLibrary(
        context: Context,
        states: List<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return states.isNotEmpty() && database.downloadOperationDao().hasAnyByStates(states)
    }

    /** 用一条 Room 语句把可恢复和活动记录切到当前存储根目录 */
    suspend fun rehomeActiveOperationsToCurrentLibrary(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        return database.withTransaction {
            database.downloadOperationDao().rehomeOperationsLibrary(
                libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context),
                states = DownloadExecutionRoomStore.Access.ROOT_REHOME_OPERATION_STATES,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * 分页读取重启后可恢复的进度，避免为每首歌重新查询一次 Room
     */
    suspend fun listProgressEntries(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<ProgressEntry> {
        val libraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context)
        val dao = database.downloadOperationDao()
        val entries = mutableListOf<ProgressEntry>()
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        val batchCache = mutableMapOf<Pair<String, Long>, DownloadBatchEntity?>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findByStatesInLibraryAfterOperationIdHeaders(
                libraryId = libraryId,
                states = DownloadExecutionRoomStore.Access.PROGRESS_CHECKPOINT_OPERATION_STATES,
                afterOperationId = afterOperationId,
                limit = DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { header ->
                val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) malformedHeaders += header
                } else {
                    val batchStateBits = progressBatchStateBits(
                        database = database,
                        request = request,
                        cache = batchCache
                    ) ?: if (request.batchId != null) return@forEach else null
                    entries += ProgressEntry(
                        request = request,
                        state = header.state,
                        bytesWritten = header.bytesWritten.coerceAtLeast(0L),
                        totalBytes = header.totalBytes?.takeIf { it > 0L },
                        stopRequestedByUser = header.stopRequestedByUser,
                        updatedAtMs = header.updatedAtMs,
                        queueOrder = header.queueOrder,
                        nextRetryAtMs = header.nextRetryAtMs,
                        lastErrorCode = header.lastErrorCode,
                        batchStateBits = batchStateBits
                    )
                }
            }
            val nextOperationId = page.last().operationId
            if (nextOperationId <= afterOperationId) {
                break
            }
            afterOperationId = nextOperationId
            if (page.size < DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE) break
        }
        malformedHeaders.forEach { header -> DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header) }
        entries.sortWith(
            compareBy<ProgressEntry> { it.queueOrder }
                .thenBy { it.updatedAtMs }
                .thenBy { it.request.operationId }
        )
        return entries
    }

    /** 跨存储根读取进度检查点，避免刚完成迁移就把任务卡片隐藏 */
    suspend fun listProgressEntriesAnyLibrary(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<ProgressEntry> {
        val dao = database.downloadOperationDao()
        val entries = mutableListOf<ProgressEntry>()
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        val batchCache = mutableMapOf<Pair<String, Long>, DownloadBatchEntity?>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findByStatesAfterOperationIdHeaders(
                states = DownloadExecutionRoomStore.Access.PROGRESS_CHECKPOINT_OPERATION_STATES,
                afterOperationId = afterOperationId,
                limit = DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { header ->
                val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) malformedHeaders += header
                } else {
                    val batchStateBits = progressBatchStateBits(
                        database = database,
                        request = request,
                        cache = batchCache
                    ) ?: if (request.batchId != null) return@forEach else null
                    entries += ProgressEntry(
                        request = request,
                        state = header.state,
                        bytesWritten = header.bytesWritten.coerceAtLeast(0L),
                        totalBytes = header.totalBytes?.takeIf { it > 0L },
                        stopRequestedByUser = header.stopRequestedByUser,
                        updatedAtMs = header.updatedAtMs,
                        queueOrder = header.queueOrder,
                        nextRetryAtMs = header.nextRetryAtMs,
                        lastErrorCode = header.lastErrorCode,
                        batchStateBits = batchStateBits
                    )
                }
            }
            val nextOperationId = page.last().operationId
            if (nextOperationId <= afterOperationId) {
                break
            }
            afterOperationId = nextOperationId
            if (page.size < DownloadExecutionRoomStore.Access.OPERATION_QUERY_PAGE_SIZE) break
        }
        malformedHeaders.forEach { header -> DownloadExecutionRoomStore.Access.invalidateMalformedPayload(database, header) }
        entries.sortWith(
            compareBy<ProgressEntry> { it.queueOrder }
                .thenBy { it.updatedAtMs }
                .thenBy { it.request.operationId }
        )
        return entries
    }

    private suspend fun progressBatchStateBits(
        database: NeriUserDataDatabase,
        request: DownloadExecutionRequest,
        cache: MutableMap<Pair<String, Long>, DownloadBatchEntity?>
    ): Int? {
        val batchId = request.batchId ?: return null
        val generation = request.batchGeneration ?: return null
        val identity = batchId to generation
        val batch = if (identity in cache) {
            cache[identity]
        } else {
            database.downloadBatchDao()
                .findBatch(batchId, generation)
                .also { cache[identity] = it }
        } ?: return null
        val isRecoverable = batch.stateBits and DownloadBatchState.OPEN != 0 &&
            batch.stateBits and DownloadBatchState.TERMINAL_MASK == 0 &&
            batch.stateBits and DownloadBatchState.CLEARING == 0
        return batch.stateBits.takeIf { isRecoverable }
    }

    /** 迁移栅栏打开后重新绑定活动 operation，不改动其载荷内容 */
    suspend fun rehomeOperationToCurrentLibrary(
        context: Context,
        operationId: String,
        stableKey: String,
        states: List<String> = DownloadExecutionRoomStore.Access.ACTIVE_OPERATION_STATES,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        if (operationId.isBlank() || states.isEmpty()) return false
        val currentLibraryId = DownloadExecutionRoomStore.Access.currentLibraryId(context)
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction false
            if (
                header.stableKey != normalizedKey ||
                    header.state !in states ||
                    header.stopRequestedByUser
            ) {
                return@withTransaction false
            }
            val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
            val request = decoded.request ?: run {
                if (decoded.payloadWasRead) {
                    DownloadExecutionRoomStore.Access.invalidateMalformedPayloadInTransaction(database, header)
                }
                return@withTransaction false
            }
            if (request.song.stableKey() != normalizedKey) {
                DownloadExecutionRoomStore.Access.invalidateMalformedPayloadInTransaction(database, header)
                return@withTransaction false
            }
            if (header.libraryId == currentLibraryId) {
                return@withTransaction true
            }
            dao.rehomeOperationLibrary(
                operationId = operationId,
                stableKey = normalizedKey,
                libraryId = currentLibraryId,
                states = states,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
        }
    }

}
