package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal const val METADATA_ACTION_REQUIRED_OPERATION_STATE = "METADATA_ACTION_REQUIRED"
internal const val METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR =
    "METADATA_EMBEDDING_UNSUPPORTED_CONTAINER"

internal data class DownloadExecutionPumpCursor(
    val queueOrder: Int,
    val updatedAtMs: Long,
    val operationId: String
)

internal data class DownloadExecutionPumpPage(
    val requests: List<DownloadExecutionRequest> = emptyList(),
    val nextCursor: DownloadExecutionPumpCursor? = null
)

internal val INTERRUPTED_DOWNLOAD_OPERATION_STATES = setOf(
    "RUNNING",
    "COMMITTING",
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "DEGRADED_COMPLETE"
)

internal fun resolveProcessExitRecoveryState(state: String?): String? {
    return when (state) {
        "PENDING_QUEUE",
        "QUEUED",
        "RUNNING",
        "RETRYABLE" -> "RETRYABLE"
        else -> null
    }
}

internal interface DownloadExecutionOperationJournal {
    fun save(context: Context, request: DownloadExecutionRequest)

    fun read(context: Context, operationId: String): DownloadExecutionRequest?

    fun remove(context: Context, operationId: String)

    fun markStopped(context: Context, operationId: String)

    fun isStopped(context: Context, operationId: String): Boolean

    fun isUserCancellationRequested(context: Context, operationId: String): Boolean = false

    fun isExplicitResumePending(context: Context, operationId: String): Boolean = false

    fun stoppedSongKeys(context: Context): Set<String>

    /** 读取全局下载泵的一页可调度 operation，默认 journal 不暴露记录 */
    fun listSchedulableForPumpPage(
        context: Context,
        afterCursor: DownloadExecutionPumpCursor?,
        limit: Int
    ): DownloadExecutionPumpPage = DownloadExecutionPumpPage()

    fun findOperationIdForSong(context: Context, songKey: String): String?

    fun findOperationIdsForSong(context: Context, songKey: String): List<String> {
        return listOfNotNull(findOperationIdForSong(context, songKey))
    }

    fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null
    ): Boolean

    fun currentState(context: Context, operationId: String): String?

    /** claims an operation before entering the transfer path */
    fun tryStart(
        context: Context,
        operationId: String,
        allowExistingRunning: Boolean = false
    ): Boolean {
        if (isStopped(context, operationId)) return false
        val state = currentState(context, operationId) ?: return false
        val claimableStates = if (allowExistingRunning) {
            setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE") +
                INTERRUPTED_DOWNLOAD_OPERATION_STATES
        } else {
            setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE")
        }
        if (state !in claimableStates) {
            return false
        }
        if (state in RESUMABLE_CORE_EXECUTION_STATES) {
            return true
        }
        return updateState(context, operationId, "RUNNING")
    }

    fun requestCancel(context: Context, operationId: String): Boolean

    fun markCoreCommitted(context: Context, operationId: String): Boolean

    fun markCommitting(context: Context, operationId: String): Boolean

    fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int
    ): Int

    /** reserves an execution slot; in-memory journals keep unit tests isolated from Room */
    fun tryAcquireHostAdmission(
        context: Context,
        operationId: String,
        capacity: Int
    ): Boolean = capacity > 0

    fun releaseHostAdmission(context: Context, operationId: String) = Unit

    fun releaseHostAdmissions(context: Context, operationIds: Collection<String>) {
        operationIds.forEach { operationId ->
            releaseHostAdmission(context, operationId)
        }
    }
}

private object RoomDownloadExecutionOperationJournal : DownloadExecutionOperationJournal {
    override fun save(context: Context, request: DownloadExecutionRequest) {
        runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.upsert(
                context = context,
                request = request,
                state = ACTIVE_STATE
            )
        }
    }

    override fun read(context: Context, operationId: String): DownloadExecutionRequest? {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.read(context, operationId)
        }
    }

    override fun remove(context: Context, operationId: String) {
        runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.delete(context, operationId)
        }
    }

    override fun markStopped(context: Context, operationId: String) {
        runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.markStopped(context, operationId)
        }
    }

    override fun isStopped(context: Context, operationId: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.isStopped(context, operationId)
        }
    }

    override fun isUserCancellationRequested(context: Context, operationId: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.isUserCancellationRequested(context, operationId)
        }
    }

    override fun isExplicitResumePending(context: Context, operationId: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.isExplicitResumePending(context, operationId)
        }
    }

    override fun stoppedSongKeys(context: Context): Set<String> {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.stoppedSongKeys(context)
        }
    }

    override fun listSchedulableForPumpPage(
        context: Context,
        afterCursor: DownloadExecutionPumpCursor?,
        limit: Int
    ): DownloadExecutionPumpPage {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.listSchedulableForPumpPage(
                context = context,
                afterCursor = afterCursor,
                limit = limit
            )
        }
    }

    override fun findOperationIdForSong(context: Context, songKey: String): String? {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.findOperationIdForSong(context, songKey)
        }
    }

    override fun findOperationIdsForSong(context: Context, songKey: String): List<String> {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.findOperationIdsForSong(context, songKey)
        }
    }

    override fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String?
    ): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.updateState(
                context = context,
                operationId = operationId,
                state = state,
                errorCode = errorCode
            )
        }
    }

    override fun currentState(context: Context, operationId: String): String? {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.state(context, operationId)
        }
    }

    override fun tryStart(
        context: Context,
        operationId: String,
        allowExistingRunning: Boolean
    ): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.tryStart(
                context = context,
                operationId = operationId,
                allowExistingRunning = allowExistingRunning
            )
        }
    }

    override fun requestCancel(context: Context, operationId: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.requestCancel(context, operationId)
        }
    }

    override fun markCoreCommitted(context: Context, operationId: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.markCoreCommitted(context, operationId)
        }
    }

    override fun markCommitting(context: Context, operationId: String): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.markCommitting(context, operationId)
        }
    }

    override fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int
    ): Int {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.pruneTerminalOperations(
                context = context,
                cutoffMs = cutoffMs,
                limit = limit
            )
        }
    }

    override fun tryAcquireHostAdmission(
        context: Context,
        operationId: String,
        capacity: Int
    ): Boolean {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.tryAcquireHostAdmission(
                context = context,
                operationId = operationId,
                capacity = capacity
            )
        }
    }

    override fun releaseHostAdmission(context: Context, operationId: String) {
        runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.releaseHostAdmission(context, operationId)
        }
    }

    override fun releaseHostAdmissions(context: Context, operationIds: Collection<String>) {
        runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.releaseHostAdmissions(context, operationIds)
        }
    }

    private const val ACTIVE_STATE = "QUEUED"
}

class DownloadExecutionOperationStore internal constructor(
    private val journalProvider: (Context) -> DownloadExecutionOperationJournal = {
        RoomDownloadExecutionOperationJournal
    }
) {
    fun save(context: Context, request: DownloadExecutionRequest) {
        val appContext = context.applicationContext
        journalProvider(appContext).save(appContext, request)
    }

    fun read(context: Context, operationId: String): DownloadExecutionRequest? {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return null
        val appContext = context.applicationContext
        return journalProvider(appContext).read(appContext, normalizedId)
    }

    fun remove(context: Context, operationId: String) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        journalProvider(appContext).remove(appContext, normalizedId)
    }

    fun markStopped(context: Context, operationId: String) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        journalProvider(appContext).markStopped(appContext, normalizedId)
    }

    fun isStopped(context: Context, operationId: String): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).isStopped(appContext, normalizedId)
    }

    fun isUserCancellationRequested(context: Context, operationId: String): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).isUserCancellationRequested(appContext, normalizedId)
    }

    fun isExplicitResumePending(context: Context, operationId: String): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).isExplicitResumePending(appContext, normalizedId)
    }

    fun stoppedSongKeys(context: Context): Set<String> {
        val appContext = context.applicationContext
        return journalProvider(appContext).stoppedSongKeys(appContext)
    }

    internal fun listSchedulableForPumpPage(
        context: Context,
        afterCursor: DownloadExecutionPumpCursor?,
        limit: Int
    ): DownloadExecutionPumpPage {
        if (limit <= 0) return DownloadExecutionPumpPage()
        val appContext = context.applicationContext
        return journalProvider(appContext).listSchedulableForPumpPage(
            appContext,
            afterCursor,
            limit
        )
    }

    fun findOperationIdForSong(context: Context, songKey: String): String? {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return null
        val appContext = context.applicationContext
        return journalProvider(appContext).findOperationIdForSong(
            appContext,
            normalizedSongKey
        )
    }

    fun findOperationIdsForSong(context: Context, songKey: String): List<String> {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return emptyList()
        val appContext = context.applicationContext
        return journalProvider(appContext).findOperationIdsForSong(
            appContext,
            normalizedSongKey
        ).distinct()
    }

    fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null
    ): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).updateState(
            appContext,
            normalizedId,
            state,
            errorCode
        )
    }

    fun currentState(context: Context, operationId: String): String? {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return null
        val appContext = context.applicationContext
        return journalProvider(appContext).currentState(appContext, normalizedId)
    }

    fun tryStart(
        context: Context,
        operationId: String,
        allowExistingRunning: Boolean = false
    ): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).tryStart(
            context = appContext,
            operationId = normalizedId,
            allowExistingRunning = allowExistingRunning
        )
    }

    fun requestCancel(context: Context, operationId: String): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).requestCancel(appContext, normalizedId)
    }

    fun markCoreCommitted(context: Context, operationId: String): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).markCoreCommitted(appContext, normalizedId)
    }

    fun markCommitting(context: Context, operationId: String): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        val appContext = context.applicationContext
        return journalProvider(appContext).markCommitting(appContext, normalizedId)
    }

    fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int
    ): Int {
        if (limit <= 0) return 0
        val appContext = context.applicationContext
        return journalProvider(appContext).pruneTerminalOperations(
            appContext,
            cutoffMs,
            limit
        )
    }

    fun tryAcquireHostAdmission(
        context: Context,
        operationId: String,
        capacity: Int
    ): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        if (capacity <= 0) return false
        val appContext = context.applicationContext
        return journalProvider(appContext).tryAcquireHostAdmission(
            context = appContext,
            operationId = normalizedId,
            capacity = capacity
        )
    }

    fun releaseHostAdmission(context: Context, operationId: String) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        journalProvider(appContext).releaseHostAdmission(appContext, normalizedId)
    }

    fun releaseHostAdmissions(context: Context, operationIds: Collection<String>) {
        val ids = operationIds.mapNotNull(::normalizeDownloadOperationId).distinct()
        if (ids.isEmpty()) return
        val appContext = context.applicationContext
        journalProvider(appContext).releaseHostAdmissions(appContext, ids)
    }

    fun clearUserStopForStableKeys(
        context: Context,
        stableKeys: Collection<String>
    ): Boolean {
        val keys = stableKeys.map(String::trim).filter(String::isNotBlank).distinct()
        if (keys.isEmpty()) return false
        val appContext = context.applicationContext
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.clearUserStopForStableKeys(appContext, keys)
        }
    }

}

internal fun resolveDownloadOperationState(
    currentState: String?,
    requestedState: String
): String? {
    val current = currentState?.trim()?.takeIf(String::isNotEmpty) ?: return requestedState
    if (current == requestedState) return current
    if (current == "CANCELLED" || current == "COMPLETED") return null
    if (requestedState == "CANCEL_REQUESTED") {
        return requestedState.takeIf {
            it != current && current in setOf("QUEUED", "RUNNING", "STOPPED", "RETRYABLE")
        }
    }
    if (requestedState == "CANCELLED") {
        return requestedState.takeIf {
            current == "CANCEL_REQUESTED" ||
                current in setOf("QUEUED", "RUNNING", "STOPPED", "RETRYABLE")
        }
    }
    if (requestedState == "COMMITTING") {
        return requestedState.takeIf {
            current == "PENDING_QUEUE" || current == "QUEUED" || current == "RUNNING"
        }
    }
    if (requestedState == "CORE_COMMITTED") {
        return requestedState.takeIf {
            current == "COMMITTING"
        }
    }
    if (
        requestedState == "RUNNING" &&
            current in setOf("RUNNING", "COMMITTING")
    ) {
        return requestedState
    }
    if (requestedState == "RETRYABLE") {
        return requestedState.takeIf {
            current in setOf("PENDING_QUEUE", "QUEUED") ||
                current in setOf("RUNNING", "COMMITTING")
        }
    }
    if (requestedState == METADATA_ACTION_REQUIRED_OPERATION_STATE) {
        return requestedState.takeIf { current == "DEGRADED_COMPLETE" }
    }
    if (current == METADATA_ACTION_REQUIRED_OPERATION_STATE) {
        return requestedState.takeIf {
            it in setOf("ASSETS_ENRICHING", "FINALIZED")
        }
    }
    if (current == "CANCEL_REQUESTED") {
        return requestedState.takeIf { it in CORE_COMMITTED_STATES }
    }
    if (requestedState == "INVALID") {
        return requestedState.takeIf {
            current in setOf(
                "PENDING_QUEUE",
                "QUEUED",
                "RUNNING",
                "COMMITTING",
                "CANCEL_REQUESTED",
                "STOPPED",
                "RETRYABLE"
            )
        }
    }
    if (requestedState == "COMPLETED") {
        return requestedState.takeIf {
            current in setOf(
                "RUNNING",
                "COMMITTING",
                "CORE_COMMITTED",
                "ASSETS_ENRICHING",
                "FINALIZED",
                "DEGRADED_COMPLETE"
            )
        }
    }
    if (
        current == "DEGRADED_COMPLETE" &&
            requestedState in setOf("ASSETS_ENRICHING", "FINALIZED")
    ) {
        return requestedState
    }
    val currentCoreIndex = CORE_COMMITTED_STATES.indexOf(current)
    if (currentCoreIndex >= 0) {
        val requestedCoreIndex = CORE_COMMITTED_STATES.indexOf(requestedState)
        return requestedState.takeIf { requestedCoreIndex >= currentCoreIndex }
    }
    return requestedState.takeIf {
        current == "PENDING_QUEUE" ||
            current == "QUEUED" ||
            current == "RUNNING" ||
            current == "RETRYABLE"
    }
}

private val CORE_COMMITTED_STATES = listOf(
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "FINALIZED",
    "DEGRADED_COMPLETE"
)

private val RESUMABLE_CORE_EXECUTION_STATES = setOf(
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "DEGRADED_COMPLETE"
)
