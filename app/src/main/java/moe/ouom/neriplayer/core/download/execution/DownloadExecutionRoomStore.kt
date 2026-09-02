package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.DownloadOperationDao
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import org.json.JSONObject

internal const val WAITING_STORAGE_MUTATION_OPERATION_STATE = "WAITING_STORAGE_MUTATION"

internal object DownloadExecutionRoomStore {
    private const val OPERATION_QUERY_PAGE_SIZE = 64
    private const val CANCELLATION_QUERY_PAGE_SIZE = 256
    private const val PUMP_QUERY_MAX_ITEMS = 64

    internal data class StateEntry(
        val request: DownloadExecutionRequest,
        val queueOrder: Int,
        val createdAtMs: Long,
        val state: String = "",
        val updatedAtMs: Long = createdAtMs
    )

    internal data class OperationSnapshot(
        val request: DownloadExecutionRequest,
        val state: String
    )

    internal data class OperationRequestMetadata(
        val operationId: String,
        val stableKey: String,
        val state: String,
        val preserveStaging: Boolean,
        val requiresWifiNetwork: Boolean,
        val attemptId: Long?,
        val artifactLeaseId: String,
        val userInitiated: Boolean,
        val downloadAudioQuality: DownloadAudioQualitySelection?
    )

    internal data class CoreCommitJournalRecovery(
        val outcome: Outcome,
        val state: String?,
        val stopRequestedByUser: Boolean
    ) {
        internal enum class Outcome {
            COMMITTED,
            PREPARED,
            MISSING,
            BLOCKED
        }
    }

    internal data class CancellationSnapshot(
        val entries: List<StateEntry>,
        val operationIds: List<String>,
        val stableKeys: Set<String>,
        val requestedAtMs: Long
    )

    internal data class OperationIdentity(
        val operationId: String,
        val stableKey: String,
        val createdAtMs: Long = 0L
    )

    internal data class ProgressCheckpoint(
        val bytesWritten: Long,
        val totalBytes: Long?
    )

    internal data class ProgressEntry(
        val request: DownloadExecutionRequest,
        val state: String,
        val bytesWritten: Long,
        val totalBytes: Long?,
        val stopRequestedByUser: Boolean,
        val updatedAtMs: Long,
        val queueOrder: Int = 0
    )

    suspend fun upsert(
        context: Context,
        request: DownloadExecutionRequest,
        state: String,
        queueOrder: Int = 0,
        createdAtMs: Long? = null,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        database.withTransaction {
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
            val persistedRequest = when {
                restartForNewAttempt -> request.copy(
                    artifactLeaseId = UUID.randomUUID().toString()
                )
                existingRequest != null -> request.copy(
                    artifactLeaseId = existingRequest.artifactLeaseId
                )
                else -> request
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
                    nextRetryAtMs = existingHeader?.nextRetryAtMs,
                    lastErrorCode = existingHeader?.lastErrorCode,
                    stopRequestedByUser = if (restartForNewAttempt) {
                        false
                    } else {
                        existingHeader?.stopRequestedByUser ?: false
                    },
                    createdAtMs = existingHeader?.createdAtMs ?: requestedCreatedAtMs,
                    updatedAtMs = payloadUpdatedAtMs,
                    hostProcessToken = existingHeader?.hostProcessToken,
                    hostAdmittedAtMs = existingHeader?.hostAdmittedAtMs
                )
            )
        }
    }

    suspend fun read(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): DownloadExecutionRequest? {
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val header = dao.findHeader(operationId) ?: return@withTransaction null
            readRequestFromHeader(dao, header).request
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
        normalizedOperationIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { operationIdChunk ->
            val chunkSnapshots = database.withTransaction {
                val dao = database.downloadOperationDao()
                dao.findAllHeadersByOperationIds(operationIdChunk).map { header ->
                    val decoded = readRequestFromHeader(dao, header)
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
                        invalidateMalformedPayload(database, header)
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
            .chunked(SQLITE_IN_QUERY_CHUNK_SIZE)
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
        normalizedOperationIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { operationIdChunk ->
            val chunkMetadata = database.withTransaction {
                val dao = database.downloadOperationDao()
                dao.findAllHeadersByOperationIds(operationIdChunk).mapNotNull { header ->
                    val sourceHintJson = readSourceHintJson(dao, header)
                    val root = sourceHintJson?.let { json ->
                        runCatching { JSONObject(json) }.getOrNull()
                    }
                    val sourceStableKey = root?.optString("sourceStableKey")
                        ?.takeIf(String::isNotBlank)
                    val artifactLeaseId = root?.optString("artifactLeaseId")
                        ?.takeIf(String::isNotBlank)
                    if (
                        root == null ||
                            root.optInt("schemaVersion") != JOURNAL_PAYLOAD_VERSION ||
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
        malformedHeaders.forEach { header -> invalidateMalformedPayload(database, header) }
        return metadata
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
                libraryId = currentLibraryId(context),
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
        normalizedOperationIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { operationIdChunk ->
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
                    header.state !in PROGRESS_CHECKPOINT_OPERATION_STATES ||
                    header.stopRequestedByUser
            ) {
                return@withTransaction false
            }
            val request = readRequestFromHeader(dao, header).request
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
                expectedStates = PROGRESS_CHECKPOINT_OPERATION_STATES
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
                header.state !in PROGRESS_CHECKPOINT_OPERATION_STATES
        ) {
            return null
        }
        val request = readRequestFromHeader(dao, header).request ?: return null
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
            libraryId = currentLibraryId(context),
            states = states
        )
    }

    /** 给全局下载泵提供有界 keyset 页面，避免 grace 过滤把后续可运行任务饿死 */
    suspend fun listSchedulableForPumpPage(
        context: Context,
        afterCursor: DownloadExecutionPumpCursor?,
        limit: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): DownloadExecutionPumpPage {
        val boundedLimit = limit.coerceIn(1, PUMP_QUERY_MAX_ITEMS)
        val (headers, decodedRequests) = database.withTransaction {
            val dao = database.downloadOperationDao()
            val headers = dao.findSchedulableForPumpAfterCursorHeaders(
                states = REUSABLE_OPERATION_STATES,
                afterQueueOrder = afterCursor?.queueOrder,
                afterUpdatedAtMs = afterCursor?.updatedAtMs,
                afterOperationId = afterCursor?.operationId,
                limit = boundedLimit
            )
            headers to headers.map { header ->
                header to readRequestFromHeader(dao, header)
            }
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
        malformedHeaders.forEach { header -> invalidateMalformedPayload(database, header) }
        return DownloadExecutionPumpPage(
            requests = requests,
            nextCursor = nextCursor
        )
    }

    suspend fun listByStates(
        context: Context,
        states: List<String>,
        excludeUserStoppedOperations: Boolean = false,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        if (states.isEmpty()) return emptyList()
        val libraryId = currentLibraryId(context)
        val dao = database.downloadOperationDao()
        val entries = mutableListOf<StateEntry>()
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findByStatesInLibraryAfterOperationIdHeaders(
                libraryId = libraryId,
                states = states,
                afterOperationId = afterOperationId,
                limit = OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) {
                break
            }
            page.forEach { header ->
                val decoded = readRequestFromHeader(dao, header)
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
            if (page.size < OPERATION_QUERY_PAGE_SIZE) {
                break
            }
        }
        malformedHeaders.forEach { header -> invalidateMalformedPayload(database, header) }
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
                limit = OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { header ->
                val decoded = readRequestFromHeader(dao, header)
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
            if (page.size < OPERATION_QUERY_PAGE_SIZE) break
        }
        malformedHeaders.forEach { header -> invalidateMalformedPayload(database, header) }
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
                libraryId = currentLibraryId(context),
                states = ROOT_REHOME_OPERATION_STATES,
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
        val libraryId = currentLibraryId(context)
        val dao = database.downloadOperationDao()
        val entries = mutableListOf<ProgressEntry>()
        val malformedHeaders = mutableListOf<DownloadOperationHeaderRow>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findByStatesInLibraryAfterOperationIdHeaders(
                libraryId = libraryId,
                states = PROGRESS_CHECKPOINT_OPERATION_STATES,
                afterOperationId = afterOperationId,
                limit = OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { header ->
                val decoded = readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) malformedHeaders += header
                } else {
                    entries += ProgressEntry(
                        request = request,
                        state = header.state,
                        bytesWritten = header.bytesWritten.coerceAtLeast(0L),
                        totalBytes = header.totalBytes?.takeIf { it > 0L },
                        stopRequestedByUser = header.stopRequestedByUser,
                        updatedAtMs = header.updatedAtMs,
                        queueOrder = header.queueOrder
                    )
                }
            }
            val nextOperationId = page.last().operationId
            if (nextOperationId <= afterOperationId) {
                break
            }
            afterOperationId = nextOperationId
            if (page.size < OPERATION_QUERY_PAGE_SIZE) break
        }
        malformedHeaders.forEach { header -> invalidateMalformedPayload(database, header) }
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
        var afterOperationId = ""
        while (true) {
            val page = dao.findByStatesAfterOperationIdHeaders(
                states = PROGRESS_CHECKPOINT_OPERATION_STATES,
                afterOperationId = afterOperationId,
                limit = OPERATION_QUERY_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            page.forEach { header ->
                val decoded = readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) malformedHeaders += header
                } else {
                    entries += ProgressEntry(
                        request = request,
                        state = header.state,
                        bytesWritten = header.bytesWritten.coerceAtLeast(0L),
                        totalBytes = header.totalBytes?.takeIf { it > 0L },
                        stopRequestedByUser = header.stopRequestedByUser,
                        updatedAtMs = header.updatedAtMs,
                        queueOrder = header.queueOrder
                    )
                }
            }
            val nextOperationId = page.last().operationId
            if (nextOperationId <= afterOperationId) {
                break
            }
            afterOperationId = nextOperationId
            if (page.size < OPERATION_QUERY_PAGE_SIZE) break
        }
        malformedHeaders.forEach { header -> invalidateMalformedPayload(database, header) }
        entries.sortWith(
            compareBy<ProgressEntry> { it.queueOrder }
                .thenBy { it.updatedAtMs }
                .thenBy { it.request.operationId }
        )
        return entries
    }

    /** 迁移栅栏打开后重新绑定活动 operation，不改动其载荷内容 */
    suspend fun rehomeOperationToCurrentLibrary(
        context: Context,
        operationId: String,
        stableKey: String,
        states: List<String> = ACTIVE_OPERATION_STATES,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        if (operationId.isBlank() || states.isEmpty()) return false
        val currentLibraryId = currentLibraryId(context)
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

    suspend fun listCancellationCandidates(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<StateEntry> {
        return listByStates(
            context = context,
            states = CANCELLATION_CANDIDATE_OPERATION_STATES,
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
                limit = OPERATION_QUERY_PAGE_SIZE,
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
            if (page.size < OPERATION_QUERY_PAGE_SIZE) {
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
        return listByStatesAnyLibrary(
            context = context,
            states = CANCELLATION_CANDIDATE_OPERATION_STATES,
            database = database
        )
    }

    /** 清空 owner 捕获只读取身份列，避免把大段 sourceHintJson 装入 CursorWindow */
    suspend fun listCancellationIdentitiesAnyLibrary(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<OperationIdentity> {
        val dao = database.downloadOperationDao()
        val identities = mutableListOf<OperationIdentity>()
        var afterOperationId = ""
        while (true) {
            val page = dao.findCancellationIdentitiesAfterOperationId(
                states = CANCELLATION_CANDIDATE_OPERATION_STATES,
                afterOperationId = afterOperationId,
                limit = CANCELLATION_QUERY_PAGE_SIZE
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
                page.size < CANCELLATION_QUERY_PAGE_SIZE
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
        return keys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
            database.downloadOperationDao()
                .findAllHeadersByStableKeysAnyLibrary(
                    stableKeys = chunk,
                    states = CANCELLATION_CANDIDATE_OPERATION_STATES
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
                    states = CANCELLATION_CANDIDATE_OPERATION_STATES,
                    limit = CANCELLATION_QUERY_PAGE_SIZE
                )
                val directCancellationIds = candidates.asSequence()
                    .filter { header -> requiresDirectCancellation(header) }
                    .map(DownloadOperationHeaderRow::operationId)
                    .toList()
                val commitBoundaryCancellationIds = candidates.asSequence()
                    .filter { header -> requiresCommitBoundaryCancellation(header) }
                    .map(DownloadOperationHeaderRow::operationId)
                    .toList()
                directCancellationIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { ids ->
                    dao.requestCancellations(ids, requestedAtMs)
                }
                commitBoundaryCancellationIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { ids ->
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
            .chunked(SQLITE_IN_QUERY_CHUNK_SIZE)
            .forEach { operationIdChunk ->
                dao.findAllHeadersByOperationIds(operationIdChunk).forEach { header ->
                    refreshedHeadersByOperationId[header.operationId] = header
                }
            }
        val entries = mutableListOf<StateEntry>()
        headers.forEach { header ->
            if (
                !requiresDirectCancellation(header) &&
                    !requiresCommitBoundaryCancellation(header)
            ) {
                return@forEach
            }
            val refreshedHeader = refreshedHeadersByOperationId[header.operationId]
                ?: return@forEach
            val decoded = readRequestFromHeader(dao, refreshedHeader)
            val request = decoded.request
            if (request == null) {
                if (decoded.payloadWasRead) {
                    invalidateMalformedPayload(database, refreshedHeader)
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
            val candidates = ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
                dao.findAllHeadersByOperationIds(chunk)
            }
            val directIds = candidates.asSequence()
                .filter { header -> requiresDirectCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            val commitBoundaryIds = candidates.asSequence()
                .filter { header -> requiresCommitBoundaryCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            directIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
                dao.requestCancellations(chunk, requestedAtMs)
            }
            commitBoundaryIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
                dao.requestCommitBoundaryCancellations(chunk, requestedAtMs)
            }
            candidates
        }
        val dao = database.downloadOperationDao()
        val refreshedHeadersByOperationId = linkedMapOf<String, DownloadOperationHeaderRow>()
        headers.map(DownloadOperationHeaderRow::operationId)
            .chunked(SQLITE_IN_QUERY_CHUNK_SIZE)
            .forEach { operationIdChunk ->
                dao.findAllHeadersByOperationIds(operationIdChunk).forEach { header ->
                    refreshedHeadersByOperationId[header.operationId] = header
                }
            }
        val entries = mutableListOf<StateEntry>()
        headers.forEach { header ->
            if (
                !requiresDirectCancellation(header) &&
                    !requiresCommitBoundaryCancellation(header)
            ) {
                return@forEach
            }
            val refreshedHeader = refreshedHeadersByOperationId[header.operationId]
                ?: return@forEach
            val decoded = readRequestFromHeader(dao, refreshedHeader)
            val request = decoded.request
            if (request == null) {
                if (decoded.payloadWasRead) {
                    invalidateMalformedPayload(database, refreshedHeader)
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
            val headers = ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).flatMap { chunk ->
                dao.findAllHeadersByOperationIds(chunk)
            }
            val directIds = headers.asSequence()
                .filter { header -> requiresDirectCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            val commitBoundaryIds = headers.asSequence()
                .filter { header -> requiresCommitBoundaryCancellation(header) }
                .map(DownloadOperationHeaderRow::operationId)
                .toList()
            directIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
                dao.requestCancellations(chunk, requestedAtMs)
            } + commitBoundaryIds.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
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
        return ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
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
        keys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { chunk ->
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
        deleteOperationsWithAdmissions(database, ids)
    }

    suspend fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        if (limit <= 0) return 0
        val ids = database.downloadOperationDao().findTerminalOperationIdsBefore(
            states = TERMINAL_STATES,
            cutoffMs = cutoffMs,
            limit = limit
        )
        return deleteOperationsWithAdmissions(database, ids)
    }

    suspend fun findOperationIdForSong(
        context: Context,
        songKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context),
        states: List<String> = ACTIVE_OPERATION_STATES
    ): String? {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return null
        val libraryId = currentLibraryId(context)
        val dao = database.downloadOperationDao()
        return dao.findLatestOperationIdByStableKey(
                libraryId = libraryId,
                stableKey = normalizedSongKey,
                states = states
            ) ?: dao.findAllHeadersByStableKeyAnyLibrary(
                stableKey = normalizedSongKey,
                states = states
            ).firstOrNull()?.also { header ->
                rehomeOperationToCurrentLibrary(
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
        states: List<String> = CANCELLATION_CANDIDATE_OPERATION_STATES
    ): List<String> {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return emptyList()
        val libraryId = currentLibraryId(context)
        val dao = database.downloadOperationDao()
        val current = dao.findAllHeadersByStableKey(libraryId, normalizedSongKey, states)
        val rows = if (current.isNotEmpty()) {
            current
        } else {
            dao.findAllHeadersByStableKeyAnyLibrary(normalizedSongKey, states).also { headers ->
                headers.forEach { header ->
                    rehomeOperationToCurrentLibrary(
                        context = context,
                        operationId = header.operationId,
                        stableKey = normalizedSongKey,
                        states = states,
                        database = database
                    )
                }
            }
        }
        return rows.map(DownloadOperationHeaderRow::operationId).distinct()
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
        val libraryId = currentLibraryId(context)
        val dao = database.downloadOperationDao()
        val readableOperations = linkedMapOf<String, DownloadExecutionRequest>()
        normalizedKeys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { keyChunk ->
            dao.findAllHeadersByStableKeys(
                libraryId = libraryId,
                stableKeys = keyChunk,
                states = states
            ).forEach headerLoop@{ header ->
                val entitySongKey = header.stableKey
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
                val decoded = readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request != null) {
                    readableOperations[entitySongKey] = request
                } else if (decoded.payloadWasRead) {
                    invalidateMalformedPayload(database, header)
                }
            }
        }
        val unresolvedKeys = normalizedKeys.filterNot { key -> key in readableOperations }
        unresolvedKeys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { keyChunk ->
            dao.findAllHeadersByStableKeysAnyLibrary(
                stableKeys = keyChunk,
                states = states
            ).forEach headerLoop@{ header ->
                val entitySongKey = header.stableKey
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
                val decoded = readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) {
                        invalidateMalformedPayload(database, header)
                    }
                    return@headerLoop
                }
                if (header.libraryId != libraryId) {
                    rehomeOperationToCurrentLibrary(
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
        val libraryId = currentLibraryId(context)
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            val candidatesByStableKey = linkedMapOf<String, MutableList<DownloadOperationHeaderRow>>()
            songsByStableKey.keys.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).forEach { stableKeyChunk ->
                dao.findAllHeadersByStableKeys(
                    libraryId = libraryId,
                    stableKeys = stableKeyChunk,
                    states = REUSABLE_OPERATION_STATES
                ).forEach { header ->
                    candidatesByStableKey.getOrPut(header.stableKey) { mutableListOf() } += header
                }
            }
            val rehydratedStableKeys = linkedSetOf<String>()
            songsByStableKey.forEach { (stableKey, song) ->
                val candidates = candidatesByStableKey[stableKey]
                    .orEmpty()
                    .filterNot(DownloadOperationHeaderRow::stopRequestedByUser)
                if (candidates.isEmpty()) {
                    return@forEach
                }
                val decodedCandidates = candidates.map { header ->
                    header to readRequestFromHeader(dao, header)
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
                val replaced = dao.replaceMalformedReusablePayload(
                    operationId = existing.operationId,
                    libraryId = libraryId,
                    stableKey = stableKey,
                    expectedStates = REUSABLE_OPERATION_STATES,
                    sourceHintJson = requestToJson(request).toString(),
                    updatedAtMs = nextPayloadUpdatedAt(
                        previousUpdatedAtMs = existing.updatedAtMs,
                        requestedAtMs = updatedAtMs
                    )
                ) > 0
                if (replaced) {
                    dao.deleteHostAdmission(existing.operationId)
                    rehydratedStableKeys += stableKey
                }
            }
            rehydratedStableKeys
        }
    }

    suspend fun isStopped(
        context: Context,
        operationId: String
    ): Boolean {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .isUserStopped(operationId) == true
    }

    suspend fun isUserCancellationRequested(
        context: Context,
        operationId: String
    ): Boolean {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .isUserCancellationRequested(operationId)
    }

    suspend fun isExplicitResumePending(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return database.downloadOperationDao().isExplicitResumePending(operationId)
    }

    suspend fun isExecutionOwned(
        context: Context,
        operationId: String,
        stableKey: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.downloadOperationDao().isExecutionOwnedAnyLibrary(
            operationId = operationId,
            stableKey = normalizedKey
        )
    }

    suspend fun stoppedSongKeys(context: Context): Set<String> {
        val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
        return dao.findUserStoppedHeaders()
            .mapNotNull { header ->
                readRequestFromHeader(dao, header).request?.song?.stableKey()
            }
            .toSet()
    }

    suspend fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val dao = database.downloadOperationDao()
        val current = dao.findHeader(operationId) ?: return false
        val nextState = resolveDownloadOperationState(current.state, state) ?: return false
        if (nextState == current.state) return !current.stopRequestedByUser
        return dao.transitionState(
            operationId = operationId,
            expectedStates = listOf(current.state),
            state = nextState,
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
        ) > 0
    }

    suspend fun markScheduleRejectedRetryable(
        context: Context,
        operationId: String,
        stableKey: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        val normalizedKey = stableKey.trim().takeIf(String::isNotBlank) ?: return false
        return database.downloadOperationDao().transitionStateForStableKey(
            operationId = operationId,
            stableKey = normalizedKey,
            expectedStates = REUSABLE_OPERATION_STATES,
            state = "RETRYABLE",
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
        ) > 0
    }

    suspend fun markWaitingForStorageMutation(
        context: Context,
        operationId: String,
        errorCode: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Boolean {
        return database.downloadOperationDao().transitionState(
            operationId = operationId,
            expectedStates = listOf(
                "PENDING_QUEUE",
                "QUEUED",
                "RUNNING",
                "RETRYABLE",
                "COMMITTING",
                "CORE_COMMITTED",
                "ASSETS_ENRICHING"
            ),
            state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
            updatedAtMs = System.currentTimeMillis(),
            errorCode = errorCode
        ) > 0
    }

    /**
     * 调用方确认存储变更和清空栅栏都已收敛后，才提升用户意图
     */
    suspend fun promoteWaitingStorageMutation(
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

    suspend fun markStagingPrepared(
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

    /** 为没有进度身份的旧记录持久化新生成的尝试编号 */
    suspend fun ensureAttemptId(
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

    suspend fun state(context: Context, operationId: String): String? {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .findState(operationId)
    }

    suspend fun tryStart(
        context: Context,
        operationId: String,
        allowExistingRunning: Boolean = false,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
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

            validContenders.forEach { (header, _) ->
                if (header.operationId == winner.first.operationId) return@forEach
                dao.transitionState(
                    operationId = header.operationId,
                    expectedStates = listOf(header.state),
                    state = "INVALID",
                    updatedAtMs = System.currentTimeMillis(),
                    errorCode = "DUPLICATE_STABLE_KEY_OPERATION"
                )
            }
            if (winner.first.operationId != operationId) {
                return@withTransaction false
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

    suspend fun requestCancel(context: Context, operationId: String): Boolean {
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

    suspend fun requestCancel(
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

    suspend fun purgeCancelled(
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

    suspend fun purgeAllCancelled(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        listOf("CANCEL_REQUESTED", "CANCELLED").forEach { state ->
            deleteByState(context, state, database)
        }
    }

    suspend fun purgeClearedOperations(
        context: Context,
        operationIds: Collection<String>,
        cancelledAtMs: Long,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return 0
        return ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            database.withTransaction {
                val dao = database.downloadOperationDao()
                val eligibleIds = dao.findClearedOperationIds(
                    operationIds = chunk,
                    cancelledAtMs = cancelledAtMs
                )
                if (eligibleIds.isEmpty()) {
                    0
                } else {
                    dao.deleteHostAdmissions(eligibleIds)
                    dao.deleteOperations(eligibleIds)
                }
            }
        }
    }

    /**
     * 只有宿主和提交工作都完全停止后，才删除清空快照
     */
    suspend fun purgeFullyClearedOperations(
        context: Context,
        operationIds: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        return deleteOperationsWithAdmissions(database, ids)
    }

    /**
     * 为当前进程预留一个系统宿主交接槽位，不把所有持久排队记录都当成活动任务
     */
    suspend fun tryAcquireHostAdmission(
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

    suspend fun releaseHostAdmission(
        context: Context,
        operationId: String,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ) {
        if (operationId.isBlank()) return
        database.downloadOperationDao().deleteHostAdmission(operationId)
    }

    suspend fun releaseHostAdmissions(
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

    suspend fun currentHostAdmissionCount(
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

    suspend fun markCoreCommitted(context: Context, operationId: String): Boolean {
        val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
        if (dao.markCoreCommitted(
                operationId = operationId,
                expectedStates = CORE_COMMIT_SOURCE_STATES,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
        ) {
            return true
        }
        return dao.findState(operationId) in CORE_COMMITTED_STATES
    }

    /** 失败重试时重新确认提交边界，不把取消或停止的 operation 重新变成可执行任务 */
    suspend fun reconcileCoreCommitJournal(
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

    suspend fun markCommitting(context: Context, operationId: String): Boolean {
        return transitionStateAtomically(
            context = context,
            operationId = operationId,
            expectedStates = COMMIT_SOURCE_STATES,
            requestedState = "COMMITTING",
            errorCode = null
        )
    }

    suspend fun markStopped(context: Context, operationId: String): Boolean {
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .requestUserStop(
                operationId = operationId,
                updatedAtMs = System.currentTimeMillis()
            ) > 0
    }

    /** 在退出标记推进前，用一笔事务标记所有用户主动停止的记录 */
    suspend fun markUserRequestedProcessExitOperations(
        context: Context,
        entries: Collection<StateEntry>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): Set<String> {
        val requests = entries
            .filter { entry -> entry.request.userInitiated }
            .distinctBy { entry -> entry.request.operationId }
        if (requests.isEmpty()) return emptySet()
        val nowMs = System.currentTimeMillis()
        val markedCount = database.withTransaction {
            val dao = database.downloadOperationDao()
            requests.sumOf { entry ->
                dao.requestUserStop(
                    operationId = entry.request.operationId,
                    updatedAtMs = nowMs
                )
            }
        }
        return if (markedCount == requests.size) {
            requests.mapTo(linkedSetOf()) { entry -> entry.request.song.stableKey() }
        } else {
            emptySet()
        }
    }

    suspend fun clearUserStopForStableKeys(
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

    suspend fun prepareExplicitResume(
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

    suspend fun prepareExplicitResumesForStableKeys(
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

    suspend fun restoreExplicitStop(
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

    private suspend fun transitionStateAtomically(
        context: Context,
        operationId: String,
        expectedStates: List<String>,
        requestedState: String,
        errorCode: String?
    ): Boolean {
        if (expectedStates.isEmpty()) return false
        return NeriUserDataDatabase.getInstance(context).downloadOperationDao()
            .transitionState(
                operationId = operationId,
                expectedStates = expectedStates,
                state = requestedState,
                updatedAtMs = System.currentTimeMillis(),
                errorCode = errorCode
            ) > 0
    }

    suspend fun delete(context: Context, operationId: String) {
        val database = NeriUserDataDatabase.getInstance(context)
        database.withTransaction {
            database.downloadOperationDao().deleteHostAdmission(operationId)
            database.downloadOperationDao().delete(operationId)
        }
    }

    private fun requestToJson(request: DownloadExecutionRequest): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", JOURNAL_PAYLOAD_VERSION)
            put(
                "song",
                ManagedDownloadStorageJsonCodec.workingResumeMetadataToJson(
                    song = request.song,
                    operationId = request.operationId
                )
            )
            // 持久化实体真正使用的身份，不能只保存可选的来源元数据
            put("sourceStableKey", request.song.stableKey())
            put("preserveStaging", request.preserveStaging)
            put("requiresWifiNetwork", request.requiresWifiNetwork)
            put("userInitiated", request.userInitiated)
            request.attemptId?.let { attemptId -> put("attemptId", attemptId) }
            put("artifactLeaseId", request.artifactLeaseId)
            request.downloadAudioQuality?.let { quality ->
                put(
                    "downloadAudioQuality",
                    JSONObject().apply {
                        put("neteaseQuality", quality.neteaseQuality)
                        put("youtubeQuality", quality.youtubeQuality)
                        put("biliQuality", quality.biliQuality)
                    }
                )
            }
        }
    }

    private fun requestFromEntity(
        entity: DownloadOperationEntity
    ): DownloadExecutionRequest? {
        val root = runCatching { JSONObject(entity.sourceHintJson) }
            .onFailure { error ->
                logDecodeFailure(entity, "invalid_json", error)
            }
            .getOrNull() ?: return null
        if (root.optInt("schemaVersion") != JOURNAL_PAYLOAD_VERSION) {
            logDecodeFailure(entity, "schema_version=${root.optInt("schemaVersion")}")
            return null
        }
        val songJson = root.optJSONObject("song") ?: run {
            logDecodeFailure(entity, "missing_song")
            return null
        }
        val parsedSong = runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(
                songJson.toString()
            )
        }.onFailure { error ->
            logDecodeFailure(entity, "song_decode", error)
        }.getOrNull() ?: run {
            logDecodeFailure(entity, "song_decode_null")
            return null
        }
        val song = parsedSong.copy(
            sourceStableKey = root.optString("sourceStableKey")
                .takeIf { root.has("sourceStableKey") && !root.isNull("sourceStableKey") }
                ?.takeIf(String::isNotBlank)
        )
        if (song.stableKey() != entity.stableKey) {
            logDecodeFailure(
                entity,
                "stable_key_mismatch parsed=${song.stableKey()} entity=${entity.stableKey}"
            )
            return null
        }
        return runCatching {
            DownloadExecutionRequest(
                operationId = entity.operationId,
                song = song,
                preserveStaging = root.optBoolean("preserveStaging", false),
                requiresWifiNetwork = if (root.has("requiresWifiNetwork")) {
                    root.optBoolean("requiresWifiNetwork", true)
                } else {
                    true
                },
                attemptId = root.optLong("attemptId", 0L).takeIf { it > 0L },
                artifactLeaseId = root.optString("artifactLeaseId")
                    .takeIf(String::isNotBlank)
                    ?: entity.operationId,
                userInitiated = if (root.has("userInitiated")) {
                    root.optBoolean("userInitiated", false)
                } else {
                    false
                },
                downloadAudioQuality = root.optJSONObject("downloadAudioQuality")?.let { quality ->
                    DownloadAudioQualitySelection.normalized(
                        neteaseQuality = quality.optString("neteaseQuality"),
                        youtubeQuality = quality.optString("youtubeQuality"),
                        biliQuality = quality.optString("biliQuality")
                    )
                }
            )
        }.onFailure { error ->
            logDecodeFailure(entity, "request_decode", error)
        }.getOrNull()
    }

    private data class HeaderRequestRead(
        val request: DownloadExecutionRequest?,
        val payloadWasRead: Boolean
    )

    /** source_hint_json 可能包含完整歌词，必须分段读取以避开 CursorWindow 上限 */
    private suspend fun readRequestFromHeader(
        dao: DownloadOperationDao,
        header: DownloadOperationHeaderRow
    ): HeaderRequestRead {
        val sourceHintJson = readSourceHintJson(dao, header)
            ?: return HeaderRequestRead(request = null, payloadWasRead = false)
        return HeaderRequestRead(
            request = requestFromEntity(header.toEntity(sourceHintJson)),
            payloadWasRead = true
        )
    }

    private suspend fun readSourceHintJson(
        dao: DownloadOperationDao,
        header: DownloadOperationHeaderRow
    ): String? {
        val payloadLength = dao.findSourceHintJsonLength(
            operationId = header.operationId,
            updatedAtMs = header.updatedAtMs
        ) ?: return null
        if (payloadLength < 0) return null
        if (payloadLength == 0) return ""
        val payload = StringBuilder(payloadLength)
        var startOffset = 1
        var readCharacterCount = 0
        while (readCharacterCount < payloadLength) {
            val chunk = dao.findSourceHintJsonChunk(
                operationId = header.operationId,
                startOffset = startOffset,
                chunkLength = SOURCE_HINT_JSON_CHUNK_LENGTH,
                updatedAtMs = header.updatedAtMs
            ) ?: return null
            if (chunk.isEmpty()) return null
            payload.append(chunk)
            readCharacterCount += chunk.codePointCount(0, chunk.length)
            if (readCharacterCount > payloadLength) return null
            startOffset = readCharacterCount + 1
        }
        return payload.toString()
    }

    private suspend fun readResumeJson(
        dao: DownloadOperationDao,
        header: DownloadOperationHeaderRow?
    ): String? {
        if (header == null) return null
        val payloadLength = dao.findResumeJsonLength(
            operationId = header.operationId,
            updatedAtMs = header.updatedAtMs
        ) ?: return null
        if (payloadLength < 0) return null
        if (payloadLength == 0) return ""
        val payload = StringBuilder(payloadLength)
        var startOffset = 1
        var readCharacterCount = 0
        while (readCharacterCount < payloadLength) {
            val chunk = dao.findResumeJsonChunk(
                operationId = header.operationId,
                startOffset = startOffset,
                chunkLength = SOURCE_HINT_JSON_CHUNK_LENGTH,
                updatedAtMs = header.updatedAtMs
            ) ?: return null
            if (chunk.isEmpty()) return null
            payload.append(chunk)
            readCharacterCount += chunk.codePointCount(0, chunk.length)
            if (readCharacterCount > payloadLength) return null
            startOffset = readCharacterCount + 1
        }
        return payload.toString()
    }

    private fun DownloadOperationHeaderRow.toEntity(
        sourceHintJson: String
    ): DownloadOperationEntity {
        return DownloadOperationEntity(
            operationId = operationId,
            stableKey = stableKey,
            libraryId = libraryId,
            state = state,
            queueOrder = queueOrder,
            sourceHintJson = sourceHintJson,
            stagingDirName = stagingDirName,
            bytesWritten = bytesWritten,
            totalBytes = totalBytes,
            resumeJson = null,
            retryCount = retryCount,
            nextRetryAtMs = nextRetryAtMs,
            lastErrorCode = lastErrorCode,
            stopRequestedByUser = stopRequestedByUser,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            hostProcessToken = hostProcessToken,
            hostAdmittedAtMs = hostAdmittedAtMs
        )
    }

    private fun logDecodeFailure(
        entity: DownloadOperationEntity,
        reason: String,
        error: Throwable? = null
    ) {
        moe.ouom.neriplayer.core.logging.NPLogger.w(
            "DownloadExecutionRoomStore",
            "operation payload decode failed: " +
                "operationId=${entity.operationId}, state=${entity.state}, " +
                "entityStableKey=${entity.stableKey}, reason=$reason",
            error
        )
    }

    private suspend fun invalidateMalformedPayload(
        database: NeriUserDataDatabase,
        header: DownloadOperationHeaderRow
    ) {
        database.withTransaction {
            invalidateMalformedPayloadInTransaction(database, header)
        }
    }

    private suspend fun invalidateMalformedPayloadInTransaction(
        database: NeriUserDataDatabase,
        header: DownloadOperationHeaderRow
    ) {
        val dao = database.downloadOperationDao()
        val updated = dao.invalidateMalformedPayloadAtVersion(
            operationId = header.operationId,
            expectedState = header.state,
            expectedUpdatedAtMs = header.updatedAtMs,
            invalidatedAtMs = System.currentTimeMillis()
        )
        if (updated > 0) {
            dao.deleteHostAdmission(header.operationId)
        }
    }

    private suspend fun hasOtherValidWaitingStorageMutation(
        database: NeriUserDataDatabase,
        target: DownloadOperationHeaderRow
    ): Boolean {
        return hasOtherValidWaitingStorageMutation(
            database = database,
            targetOperationId = target.operationId,
            targetLibraryId = target.libraryId,
            targetStableKey = target.stableKey
        )
    }

    private suspend fun hasOtherValidWaitingStorageMutation(
        database: NeriUserDataDatabase,
        targetOperationId: String,
        targetLibraryId: String,
        targetStableKey: String
    ): Boolean {
        val dao = database.downloadOperationDao()
        val candidates = dao.findAllHeadersByStableKey(
            libraryId = targetLibraryId,
            stableKey = targetStableKey,
            states = listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
        )
        for (header in candidates) {
            if (
                header.operationId == targetOperationId ||
                    header.stopRequestedByUser
            ) {
                continue
            }
            val decoded = readRequestFromHeader(dao, header)
            val request = decoded.request
            if (request == null || request.song.stableKey() != header.stableKey) {
                if (decoded.payloadWasRead) {
                    invalidateMalformedPayloadInTransaction(database, header)
                }
                continue
            }
            return true
        }
        return false
    }

    private fun requiresDirectCancellation(header: DownloadOperationHeaderRow): Boolean {
        return requiresDirectCancellation(
            state = header.state,
            stopRequestedByUser = header.stopRequestedByUser
        )
    }

    private fun requiresDirectCancellation(
        state: String,
        stopRequestedByUser: Boolean
    ): Boolean {
        return when (state) {
            "PENDING_QUEUE",
            "QUEUED",
            WAITING_STORAGE_MUTATION_OPERATION_STATE,
            "RUNNING",
            "RETRYABLE" -> !stopRequestedByUser

            "STOPPED" -> true
            else -> false
        }
    }

    private fun requiresCommitBoundaryCancellation(header: DownloadOperationHeaderRow): Boolean {
        return requiresCommitBoundaryCancellation(
            state = header.state,
            stopRequestedByUser = header.stopRequestedByUser
        )
    }

    private fun requiresCommitBoundaryCancellation(
        state: String,
        stopRequestedByUser: Boolean
    ): Boolean {
        return state in COMMIT_BOUNDARY_CANCEL_STATES && !stopRequestedByUser
    }

    private suspend fun deleteOperationsWithAdmissions(
        database: NeriUserDataDatabase,
        operationIds: Collection<String>
    ): Int {
        val ids = operationIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return 0
        return ids.chunked(SQLITE_IN_QUERY_CHUNK_SIZE).sumOf { chunk ->
            database.withTransaction {
                val dao = database.downloadOperationDao()
                dao.deleteHostAdmissions(chunk)
                dao.deleteOperations(chunk)
            }
        }
    }

    private fun currentLibraryId(context: Context): String {
        return ManagedDownloadStorage.currentSnapshotCacheKey(context.applicationContext)
    }

    private fun nextPayloadUpdatedAt(
        previousUpdatedAtMs: Long?,
        requestedAtMs: Long = System.currentTimeMillis()
    ): Long {
        val previous = previousUpdatedAtMs ?: return requestedAtMs
        if (previous == Long.MAX_VALUE) return previous
        return maxOf(requestedAtMs, previous + 1L)
    }

    private const val JOURNAL_PAYLOAD_VERSION = 1
    private const val SOURCE_HINT_JSON_CHUNK_LENGTH = 64 * 1024
    private const val SQLITE_IN_QUERY_CHUNK_SIZE = 900
    internal const val HOST_ADMISSION_HANDOFF_LEASE_MS = 30_000L
    private val HOST_ADMISSION_PROCESS_TOKEN = UUID.randomUUID().toString()
    private val TERMINAL_STATES = listOf("COMPLETED", "CANCELLED", "INVALID")
    private val ACTIVE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "CANCEL_REQUESTED",
        "STOPPED",
        "RETRYABLE",
        "DEGRADED_COMPLETE"
    )
    internal val REUSABLE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE"
    )
    internal val IN_FLIGHT_OPERATION_STATES = listOf(
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    /** 旧宿主消失后可交给新进程接管的状态，进程死亡可能让持久日志领先于内存调度器 */
    internal val HOST_ADMISSION_HANDOFF_STATES = REUSABLE_OPERATION_STATES +
        IN_FLIGHT_OPERATION_STATES
    /** 只有尚未进入执行的任务允许依靠时间租约回收，避免长下载被误释放 */
    internal val HOST_ADMISSION_EXPIRABLE_STATES = REUSABLE_OPERATION_STATES
    internal val PROGRESS_CHECKPOINT_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
        "RETRYABLE",
        "STOPPED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE
    )
    private val CANCELABLE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "STOPPED",
        "RETRYABLE"
    )
    private val CANCELLATION_CANDIDATE_OPERATION_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "CANCEL_REQUESTED",
        "STOPPED",
        "RETRYABLE",
        "DEGRADED_COMPLETE"
    )
    private val COMMIT_BOUNDARY_CANCEL_STATES = listOf(
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    private val EXPLICIT_RESUME_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
        "COMMITTING",
        "RETRYABLE",
        "STOPPED",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
    private val EXPLICIT_STOP_RESTORE_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE"
    )
    private val CORE_COMMIT_SOURCE_STATES = listOf(
        "COMMITTING"
    )
    private val CORE_COMMITTED_STATES = setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "FINALIZED",
        "DEGRADED_COMPLETE",
        "COMPLETED"
    )
    private val CORE_COMMIT_BLOCKED_STATES = setOf(
        "CANCEL_REQUESTED",
        "CANCELLED",
        "STOPPED"
    )
    private val CORE_COMMIT_RECOVERY_SOURCE_STATES = setOf(
        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "RETRYABLE"
    )
    private val COMMIT_SOURCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING"
    )

    private val ROOT_REHOME_OPERATION_STATES = REUSABLE_OPERATION_STATES +
        IN_FLIGHT_OPERATION_STATES +
        listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)

    private val EXECUTION_CONVERGENCE_STATES = listOf(
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE",
        "RUNNING",
        "COMMITTING",
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )

    private val DURABLE_CORE_EXECUTION_STATES = setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}

private fun executionConvergencePriority(state: String): Int {
    return when (state) {
        "DEGRADED_COMPLETE" -> 8
        "ASSETS_ENRICHING" -> 7
        "CORE_COMMITTED" -> 6
        "COMMITTING" -> 5
        "RUNNING" -> 4
        "RETRYABLE" -> 3
        "QUEUED" -> 2
        "PENDING_QUEUE" -> 1
        else -> 0
    }
}

internal fun shouldRestartOperation(
    existingState: String?,
    requestedState: String,
    userInitiated: Boolean
): Boolean {
    return false
}
