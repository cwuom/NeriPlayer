package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal interface DownloadExecutionOperationJournal {
    fun save(context: Context, request: DownloadExecutionRequest)

    fun read(context: Context, operationId: String): DownloadExecutionRequest?

    fun remove(context: Context, operationId: String)

    fun markStopped(context: Context, operationId: String)

    fun isStopped(context: Context, operationId: String): Boolean

    fun stoppedSongKeys(context: Context): Set<String>

    fun findOperationIdForSong(context: Context, songKey: String): String?

    fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null
    )

    fun currentState(context: Context, operationId: String): String?

    /** claims an operation before entering the transfer path */
    fun tryStart(
        context: Context,
        operationId: String,
        allowExistingRunning: Boolean = false
    ): Boolean {
        val state = currentState(context, operationId) ?: return false
        val claimableStates = if (allowExistingRunning) {
            setOf("PENDING_QUEUE", "QUEUED", "RUNNING", "RETRYABLE")
        } else {
            setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE")
        }
        if (state !in claimableStates) {
            return false
        }
        updateState(context, operationId, "RUNNING")
        return currentState(context, operationId) == "RUNNING"
    }

    fun requestCancel(context: Context, operationId: String): Boolean

    fun markCoreCommitted(context: Context, operationId: String): Boolean

    fun markCommitting(context: Context, operationId: String): Boolean

    fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int
    ): Int
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

    override fun stoppedSongKeys(context: Context): Set<String> {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.stoppedSongKeys(context)
        }
    }

    override fun findOperationIdForSong(context: Context, songKey: String): String? {
        return runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.findOperationIdForSong(context, songKey)
        }
    }

    override fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String?
    ) {
        runBlocking(Dispatchers.IO) {
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

    fun stoppedSongKeys(context: Context): Set<String> {
        val appContext = context.applicationContext
        return journalProvider(appContext).stoppedSongKeys(appContext)
    }

    fun findOperationIdForSong(context: Context, songKey: String): String? {
        val normalizedSongKey = songKey.trim().takeIf(String::isNotEmpty) ?: return null
        val appContext = context.applicationContext
        return journalProvider(appContext).findOperationIdForSong(
            appContext,
            normalizedSongKey
        )
    }

    fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String? = null
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        journalProvider(appContext).updateState(
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
    if (current == "CANCEL_REQUESTED") {
        return requestedState.takeIf { it in CORE_COMMITTED_STATES }
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
    val currentCoreIndex = CORE_COMMITTED_STATES.indexOf(current)
    if (currentCoreIndex >= 0) {
        val requestedCoreIndex = CORE_COMMITTED_STATES.indexOf(requestedState)
        return requestedState.takeIf { requestedCoreIndex >= currentCoreIndex }
    }
    return requestedState.takeIf {
        current == "PENDING_QUEUE" || current == "QUEUED" || current == "RUNNING"
    }
}

private val CORE_COMMITTED_STATES = listOf(
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "FINALIZED",
    "DEGRADED_COMPLETE"
)
