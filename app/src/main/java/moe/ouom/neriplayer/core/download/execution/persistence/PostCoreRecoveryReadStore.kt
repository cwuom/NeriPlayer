package moe.ouom.neriplayer.core.download.execution.persistence

import android.content.Context
import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow

/** 每个状态只保留一页表头，完整歌词载荷仅在选中后读取 */
internal object PostCoreRecoveryReadStore {
    private const val HEADER_PAGE_SIZE = 64
    val states = listOf("DEGRADED_COMPLETE", "CORE_COMMITTED", "ASSETS_ENRICHING")
    private val order = compareBy<DownloadOperationHeaderRow> { it.queueOrder }
        .thenBy { it.createdAtMs }.thenBy { it.operationId }

    suspend fun select(
        database: NeriUserDataDatabase,
        capacity: Int,
        excluded: Set<String>,
        allowWifi: Boolean,
        isExecuting: (String) -> Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): List<DownloadOperationHeaderRow> {
        val limit = capacity.coerceIn(0, 8)
        if (limit == 0) return emptyList()
        class Stream(val state: String) {
            var cursor: DownloadOperationHeaderRow? = null
            var exhausted = false
            val pending = ArrayDeque<Pair<DownloadOperationHeaderRow, Boolean>>()
            suspend fun peek(): Pair<DownloadOperationHeaderRow, Boolean>? {
                if (pending.isEmpty() && !exhausted) {
                    val page = database.withTransaction {
                        val dao = database.downloadOperationDao()
                        val after = cursor
                        val headers = if (after == null) dao.postCoreHeadersFirst(state, HEADER_PAGE_SIZE)
                        else dao.postCoreHeadersAfter(state, after.queueOrder, after.createdAtMs, after.operationId, HEADER_PAGE_SIZE)
                        val policies = if (allowWifi || headers.isEmpty()) emptyMap() else
                            dao.findNetworkPoliciesByOperationIds(headers.map { it.operationId })
                                .associate { it.operationId to it.requiresWifiNetwork }
                        headers.map { it to (allowWifi || policies[it.operationId] == false) }
                    }
                    cursor = page.lastOrNull()?.first ?: cursor
                    exhausted = page.size < HEADER_PAGE_SIZE
                    pending.addAll(page)
                }
                return pending.firstOrNull()
            }
        }
        val degraded = Stream(states[0])
        val fresh = listOf(Stream(states[1]), Stream(states[2]))
        val selected = mutableListOf<DownloadOperationHeaderRow>()
        while (selected.size < limit) {
            val stream = if (degraded.peek() != null) degraded else {
                val candidates = fresh.mapNotNull { stream -> stream.peek()?.let { stream to it.first } }
                candidates.minWithOrNull { a, b -> order.compare(a.second, b.second) }?.first ?: break
            }
            val (header, eligible) = stream.pending.removeFirst()
            if (header.operationId in excluded || selected.any { it.operationId == header.operationId } ||
                !eligible || isExecuting(header.operationId)) continue
            if (header.nextRetryAtMs?.let { it > nowMs } == true) break
            selected += header
        }
        return selected
    }

    suspend fun entries(
        context: Context,
        ids: Collection<String>,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context)
    ): List<DownloadExecutionRoomStore.StateEntry> {
        if (ids.isEmpty()) return emptyList()
        return database.withTransaction {
            val dao = database.downloadOperationDao()
            dao.findAllHeadersByOperationIds(ids.distinct()).mapNotNull { header ->
                if (header.state !in states || header.stopRequestedByUser) return@mapNotNull null
                val decoded = DownloadExecutionRoomStore.Access.readRequestFromHeader(dao, header)
                val request = decoded.request
                if (request == null) {
                    if (decoded.payloadWasRead) {
                        DownloadExecutionRoomStore.Access.invalidateMalformedPayloadInTransaction(database, header)
                    }
                    return@mapNotNull null
                }
                DownloadExecutionRoomStore.StateEntry(
                    request, header.queueOrder, header.createdAtMs, header.state, header.updatedAtMs,
                    header.retryCount, header.nextRetryAtMs, header.lastErrorCode
                )
            }
        }
    }
}
