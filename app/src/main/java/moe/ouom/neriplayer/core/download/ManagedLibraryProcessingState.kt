package moe.ouom.neriplayer.core.download

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MANAGED_PROCESSING_STATE_RUNNING = "running"

enum class ManagedLibraryProcessingReason {
    LEGACY_DATABASE_UPGRADE,
    DIRECTORY_CHANGE
}

enum class ManagedLibraryProcessingPhase {
    UPGRADING_DATABASE,
    REBUILDING_INDEX,
    WAITING_FOR_RETRY
}

sealed interface ManagedLibraryProcessingState {
    val operationId: String?
    val reason: ManagedLibraryProcessingReason?
    val phase: ManagedLibraryProcessingPhase?
    val processed: Int?
    val total: Int?
    val currentItem: String?

    data object Idle : ManagedLibraryProcessingState {
        override val operationId: String? = null
        override val reason: ManagedLibraryProcessingReason? = null
        override val phase: ManagedLibraryProcessingPhase? = null
        override val processed: Int? = null
        override val total: Int? = null
        override val currentItem: String? = null
    }

    data class Running(
        override val operationId: String,
        override val reason: ManagedLibraryProcessingReason,
        override val phase: ManagedLibraryProcessingPhase,
        override val processed: Int? = null,
        override val total: Int? = null,
        override val currentItem: String? = null
    ) : ManagedLibraryProcessingState

    data class WaitingForRetry(
        override val operationId: String,
        override val reason: ManagedLibraryProcessingReason,
        override val phase: ManagedLibraryProcessingPhase =
            ManagedLibraryProcessingPhase.WAITING_FOR_RETRY,
        override val processed: Int? = null,
        override val total: Int? = null,
        override val currentItem: String? = null
    ) : ManagedLibraryProcessingState
}

internal object ManagedLibraryProcessingStateMachine {
    fun begin(
        operationId: String,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase,
        processed: Int? = null,
        total: Int? = null,
        currentItem: String? = null
    ): ManagedLibraryProcessingState.Running {
        val normalized = normalizeProgress(
            processed = processed,
            total = total,
            currentItem = currentItem
        )
        return ManagedLibraryProcessingState.Running(
            operationId = operationId,
            reason = reason,
            phase = phase,
            processed = normalized.processed,
            total = normalized.total,
            currentItem = normalized.currentItem
        )
    }

    fun tryBeginExclusive(
        current: ManagedLibraryProcessingState,
        operationId: String,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase,
        resumeWaitingOperation: Boolean = false
    ): ManagedLibraryProcessingState.Running? {
        if (current is ManagedLibraryProcessingState.Running) return null
        if (current is ManagedLibraryProcessingState.WaitingForRetry) {
            if (!resumeWaitingOperation || current.reason != reason) return null
            return ManagedLibraryProcessingState.Running(
                operationId = current.operationId,
                reason = current.reason,
                phase = phase,
                processed = current.processed,
                total = current.total,
                currentItem = current.currentItem
            )
        }
        return begin(
            operationId = operationId,
            reason = reason,
            phase = phase
        )
    }

    fun updateProgress(
        current: ManagedLibraryProcessingState,
        operationId: String,
        processed: Int?,
        total: Int?,
        currentItem: String? = null
    ): ManagedLibraryProcessingState {
        if (current.operationId != operationId) return current
        val normalized = normalizeProgress(
            current = current,
            processed = processed,
            total = total,
            currentItem = currentItem
        )
        return when (current) {
            ManagedLibraryProcessingState.Idle -> current
            is ManagedLibraryProcessingState.Running -> current.copy(
                processed = normalized.processed,
                total = normalized.total,
                currentItem = normalized.currentItem
            )
            is ManagedLibraryProcessingState.WaitingForRetry -> current.copy(
                processed = normalized.processed,
                total = normalized.total,
                currentItem = normalized.currentItem
            )
        }
    }

    fun advancePhase(
        current: ManagedLibraryProcessingState,
        operationId: String,
        phase: ManagedLibraryProcessingPhase
    ): ManagedLibraryProcessingState {
        if (current.operationId != operationId || current.phase == phase) {
            return current
        }
        return when (current) {
            ManagedLibraryProcessingState.Idle -> current
            is ManagedLibraryProcessingState.Running -> current.copy(phase = phase)
            is ManagedLibraryProcessingState.WaitingForRetry -> current.copy(phase = phase)
        }
    }

    fun waitingForRetry(
        current: ManagedLibraryProcessingState,
        operationId: String
    ): ManagedLibraryProcessingState {
        if (current.operationId != operationId) return current
        val reason = current.reason ?: return current
        return ManagedLibraryProcessingState.WaitingForRetry(
            operationId = operationId,
            reason = reason,
            phase = current.phase ?: ManagedLibraryProcessingPhase.WAITING_FOR_RETRY,
            processed = current.processed,
            total = current.total,
            currentItem = current.currentItem
        )
    }

    fun complete(
        current: ManagedLibraryProcessingState,
        operationId: String
    ): ManagedLibraryProcessingState {
        return if (current.operationId == operationId) {
            ManagedLibraryProcessingState.Idle
        } else {
            current
        }
    }

    private data class NormalizedProgress(
        val processed: Int?,
        val total: Int?,
        val currentItem: String?
    )

    private fun normalizeProgress(
        processed: Int?,
        total: Int?,
        currentItem: String?
    ): NormalizedProgress {
        val normalizedTotal = total?.coerceAtLeast(0)
        val normalizedProcessed = processed?.coerceAtLeast(0)?.let { value ->
            normalizedTotal?.let(value::coerceAtMost) ?: value
        }
        return NormalizedProgress(
            processed = normalizedProcessed,
            total = normalizedTotal,
            currentItem = currentItem?.trim()?.takeIf(String::isNotBlank)
        )
    }

    private fun normalizeProgress(
        current: ManagedLibraryProcessingState,
        processed: Int?,
        total: Int?,
        currentItem: String?
    ): NormalizedProgress {
        val normalized = normalizeProgress(processed, total, currentItem)
        val previousTotal = current.total
        val previousProcessed = current.processed
        val resumedBatch = previousTotal != null &&
            normalized.total != null &&
            normalized.total < previousTotal &&
            previousProcessed != null
        val effectiveTotal = when {
            resumedBatch -> previousTotal
            normalized.total == null -> previousTotal
            else -> normalized.total
        }
        val monotonicProcessed = if (resumedBatch) {
            (
                requireNotNull(previousProcessed).toLong() +
                    (normalized.processed ?: 0).toLong()
                )
                .coerceAtMost(requireNotNull(effectiveTotal).toLong())
                .toInt()
        } else if (effectiveTotal != null && effectiveTotal == previousTotal) {
            maxOf(previousProcessed ?: 0, normalized.processed ?: previousProcessed ?: 0)
                .coerceAtMost(effectiveTotal)
        } else {
            normalized.processed ?: previousProcessed
        }
        return normalized.copy(
            processed = monotonicProcessed,
            total = effectiveTotal,
            currentItem = normalized.currentItem ?: current.currentItem
        )
    }
}

internal fun restoreManagedLibraryProcessingState(
    operationId: String?,
    reasonName: String?,
    phaseName: String?,
    processed: Int? = null,
    total: Int? = null,
    currentItem: String? = null,
    restoredRunning: Boolean = false
): ManagedLibraryProcessingState {
    val restoredOperationId = operationId?.takeIf(String::isNotBlank)
        ?: return ManagedLibraryProcessingState.Idle
    val restoredReason = reasonName?.let { value ->
        runCatching { ManagedLibraryProcessingReason.valueOf(value) }.getOrNull()
    } ?: return ManagedLibraryProcessingState.Idle
    val restoredPhase = phaseName?.let { value ->
        runCatching { ManagedLibraryProcessingPhase.valueOf(value) }.getOrNull()
    } ?: return ManagedLibraryProcessingState.Idle
    val normalizedTotal = total?.coerceAtLeast(0)
    val normalizedProcessed = processed?.coerceAtLeast(0)?.let { value ->
        normalizedTotal?.let(value::coerceAtMost) ?: value
    }

    val currentItemValue = currentItem?.trim()?.takeIf(String::isNotBlank)
    return if (restoredRunning) {
        ManagedLibraryProcessingState.Running(
            operationId = restoredOperationId,
            reason = restoredReason,
            phase = restoredPhase,
            processed = normalizedProcessed,
            total = normalizedTotal,
            currentItem = currentItemValue
        )
    } else {
        ManagedLibraryProcessingState.WaitingForRetry(
            operationId = restoredOperationId,
            reason = restoredReason,
            phase = restoredPhase,
            processed = normalizedProcessed,
            total = normalizedTotal,
            currentItem = currentItemValue
        )
    }
}

/**
 * 终态迁移的请求和界面状态分开提交时，进程终止可能只留下旧的等待横幅
 */
internal fun shouldCompleteOrphanedTerminalDirectoryChange(
    current: ManagedLibraryProcessingState,
    expectedOperationId: String?,
    requestAutoResume: Boolean,
    activeMigrationWorkPresent: Boolean?
): Boolean {
    val expectedId = expectedOperationId?.trim()?.takeIf(String::isNotBlank) ?: return false
    return !requestAutoResume &&
        activeMigrationWorkPresent == false &&
        current is ManagedLibraryProcessingState.WaitingForRetry &&
        current.reason == ManagedLibraryProcessingReason.DIRECTORY_CHANGE &&
        current.operationId == expectedId
}

internal fun describeManagedLibraryProcessingBusy(
    state: ManagedLibraryProcessingState,
    requestedReason: ManagedLibraryProcessingReason? = null
): String? {
    if (state is ManagedLibraryProcessingState.Idle) return null
    val operationId = state.operationId?.takeIf(String::isNotBlank) ?: "unknown"
    val reason = state.reason?.name ?: "unknown"
    val phase = state.phase?.name ?: "unknown"
    val relation = when {
        requestedReason == null -> "active"
        requestedReason == state.reason -> "same-reason"
        else -> "different-reason"
    }
    return "operationId=$operationId, reason=$reason, phase=$phase, relation=$relation"
}

internal fun isManagedLibraryProcessingOwnedByCurrentProcess(
    stateKind: String?,
    ownerProcessToken: String?,
    currentProcessToken: String?
): Boolean {
    return stateKind == MANAGED_PROCESSING_STATE_RUNNING &&
        ownerProcessToken?.trim()?.isNotBlank() == true &&
        ownerProcessToken == currentProcessToken
}

internal class ManagedLibraryProcessingBusyException(
    activeReason: ManagedLibraryProcessingReason?
) : IllegalStateException("managed library processing is busy: ${activeReason?.name ?: "unknown"}")

object ManagedLibraryProcessingCoordinator {
    private const val PREFERENCES_NAME = "managed_library_processing"
    private const val KEY_OPERATION_ID = "operation_id"
    private const val KEY_REASON = "reason"
    private const val KEY_PHASE = "phase"
    private const val KEY_PROCESSED = "processed"
    private const val KEY_TOTAL = "total"
    private const val KEY_CURRENT_ITEM = "current_item"
    private const val KEY_STATE_KIND = "state_kind"
    private const val KEY_OWNER_PROCESS_TOKEN = "owner_process_token"
    private const val STATE_KIND_RUNNING = MANAGED_PROCESSING_STATE_RUNNING
    private const val STATE_KIND_WAITING = "waiting"
    private const val PROGRESS_PERSIST_INTERVAL_MS = 250L
    private const val PROGRESS_PERSIST_DELTA = 16

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ManagedLibraryProcessingState>(
        ManagedLibraryProcessingState.Idle
    )

    private var persistenceContext: Context? = null
    private var lastProgressPersistedAtMs = 0L
    private var lastProgressPersisted: ManagedLibraryProcessingState? = null
    private val processToken = UUID.randomUUID().toString()

    val state: StateFlow<ManagedLibraryProcessingState> = mutableState.asStateFlow()

    /** restores the small persisted state synchronously before the first frame */
    fun restoreImmediately(context: Context): ManagedLibraryProcessingState {
        if (!mutex.tryLock()) return mutableState.value
        return try {
            persistenceContext = context.applicationContext
            if (mutableState.value != ManagedLibraryProcessingState.Idle) {
                return mutableState.value
            }
            val restored = readPersistedState(context.applicationContext)
            mutableState.value = restored
            resetProgressPersistence(restored)
            restored
        } finally {
            mutex.unlock()
        }
    }

    suspend fun restore(context: Context): ManagedLibraryProcessingState = mutex.withLock {
        persistenceContext = context.applicationContext
        if (mutableState.value != ManagedLibraryProcessingState.Idle) {
            return@withLock mutableState.value
        }
        val restored = withContext(Dispatchers.IO) {
            readPersistedState(context.applicationContext)
        }
        mutableState.value = restored
        resetProgressPersistence(restored)
        restored
    }

    suspend fun begin(
        context: Context,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase
    ): String = mutex.withLock {
        persistenceContext = context.applicationContext
        val next = ManagedLibraryProcessingStateMachine.begin(
            operationId = UUID.randomUUID().toString(),
            reason = reason,
            phase = phase
        )
        persist(context.applicationContext, next)
        mutableState.value = next
        resetProgressPersistence(next)
        next.operationId
    }

    suspend fun beginIfIdle(
        context: Context,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase
    ): String? = mutex.withLock {
        persistenceContext = context.applicationContext
        val current = mutableState.value
        if (current != ManagedLibraryProcessingState.Idle) {
            return@withLock current.operationId.takeIf { current.reason == reason }
        }
        val next = ManagedLibraryProcessingStateMachine.begin(
            operationId = UUID.randomUUID().toString(),
            reason = reason,
            phase = phase
        )
        persist(context.applicationContext, next)
        mutableState.value = next
        resetProgressPersistence(next)
        next.operationId
    }

    suspend fun tryBeginExclusive(
        context: Context,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase,
        resumeWaitingOperation: Boolean = false
    ): String? = mutex.withLock {
        persistenceContext = context.applicationContext
        val next = ManagedLibraryProcessingStateMachine.tryBeginExclusive(
            current = mutableState.value,
            operationId = UUID.randomUUID().toString(),
            reason = reason,
            phase = phase,
            resumeWaitingOperation = resumeWaitingOperation
        ) ?: return@withLock null
        persist(context.applicationContext, next)
        mutableState.value = next
        resetProgressPersistence(next)
        next.operationId
    }

    suspend fun updateProgress(
        operationId: String,
        processed: Int?,
        total: Int?,
        currentItem: String? = null,
        context: Context? = null
    ) = mutex.withLock {
        context?.applicationContext?.let { appContext ->
            persistenceContext = appContext
        }
        val current = mutableState.value
        val next = ManagedLibraryProcessingStateMachine.updateProgress(
            current = current,
            operationId = operationId,
            processed = processed,
            total = total,
            currentItem = currentItem
        )
        if (next == current) return@withLock
        if (shouldPersistProgress(next)) {
            persistenceContext?.let { appContext ->
                persist(appContext, next)
                lastProgressPersistedAtMs = System.currentTimeMillis()
                lastProgressPersisted = next
            }
        }
        mutableState.value = next
    }

    /** keeps the same operation visible while the rebuilt catalog is published */
    suspend fun advancePhase(
        context: Context,
        operationId: String,
        phase: ManagedLibraryProcessingPhase
    ) = mutex.withLock {
        val current = mutableState.value
        val next = ManagedLibraryProcessingStateMachine.advancePhase(
            current = current,
            operationId = operationId,
            phase = phase
        )
        if (next == current) return@withLock
        persist(context.applicationContext, next)
        mutableState.value = next
        resetProgressPersistence(next)
    }

    suspend fun waitingForRetry(context: Context, operationId: String) = mutex.withLock {
        val current = mutableState.value
        val next = ManagedLibraryProcessingStateMachine.waitingForRetry(
            current = current,
            operationId = operationId
        )
        if (next != current) {
            persist(context.applicationContext, next)
            mutableState.value = next
            lastProgressPersistedAtMs = System.currentTimeMillis()
            lastProgressPersisted = next
        }
    }

    /** 在迁移预检前持久化等待态，进程被杀后仍能复用同一个 operation */
    suspend fun ensureWaitingForRetry(
        context: Context,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase = ManagedLibraryProcessingPhase.WAITING_FOR_RETRY
    ): String? = mutex.withLock {
        persistenceContext = context.applicationContext
        val current = mutableState.value
        if (current != ManagedLibraryProcessingState.Idle) {
            return@withLock current.operationId
                ?.takeIf { current.reason == reason && current is ManagedLibraryProcessingState.WaitingForRetry }
        }
        val next = ManagedLibraryProcessingState.WaitingForRetry(
            operationId = UUID.randomUUID().toString(),
            reason = reason,
            phase = phase
        )
        persist(context.applicationContext, next)
        mutableState.value = next
        resetProgressPersistence(next)
        next.operationId
    }

    suspend fun complete(context: Context, operationId: String) = mutex.withLock {
        val current = mutableState.value
        val next = ManagedLibraryProcessingStateMachine.complete(current, operationId)
        if (next != current) {
            persist(context.applicationContext, next)
            mutableState.value = next
            lastProgressPersistedAtMs = 0L
            lastProgressPersisted = null
        }
    }

    /**
     * 只清理由已终态迁移留下且没有活动 Worker 的目录等待状态
     */
    suspend fun completeOrphanedTerminalDirectoryChange(
        context: Context,
        expectedOperationId: String?,
        requestAutoResume: Boolean,
        activeMigrationWorkPresent: Boolean?
    ): Boolean = mutex.withLock {
        persistenceContext = context.applicationContext
        val current = mutableState.value
        if (!shouldCompleteOrphanedTerminalDirectoryChange(
                current = current,
                expectedOperationId = expectedOperationId,
                requestAutoResume = requestAutoResume,
                activeMigrationWorkPresent = activeMigrationWorkPresent
            )
        ) {
            return@withLock false
        }
        val next = ManagedLibraryProcessingState.Idle
        persist(context.applicationContext, next)
        mutableState.value = next
        resetProgressPersistence(next)
        true
    }

    @SuppressLint("UseKtx", "ApplySharedPref")
    private suspend fun persist(
        context: Context,
        state: ManagedLibraryProcessingState
    ) = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val committed = if (state == ManagedLibraryProcessingState.Idle) {
            preferences.edit().clear().commit()
        } else {
            val processed = state.processed
            val total = state.total
            val currentItem = state.currentItem
            preferences.edit()
                .putString(KEY_OPERATION_ID, state.operationId)
                .putString(KEY_REASON, state.reason?.name)
                .putString(KEY_PHASE, state.phase?.name)
                .putString(
                    KEY_STATE_KIND,
                    if (state is ManagedLibraryProcessingState.Running) {
                        STATE_KIND_RUNNING
                    } else {
                        STATE_KIND_WAITING
                    }
                )
                .putString(KEY_OWNER_PROCESS_TOKEN, processToken)
                .apply {
                    if (processed == null) remove(KEY_PROCESSED)
                    else putInt(KEY_PROCESSED, processed)
                    if (total == null) remove(KEY_TOTAL)
                    else putInt(KEY_TOTAL, total)
                    if (currentItem == null) remove(KEY_CURRENT_ITEM)
                    else putString(KEY_CURRENT_ITEM, currentItem)
                }
                .commit()
        }
        if (!committed) {
            throw IOException("failed to persist managed library processing state")
        }
    }

    private fun readPersistedState(context: Context): ManagedLibraryProcessingState {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val restoredRunning = isManagedLibraryProcessingOwnedByCurrentProcess(
            stateKind = preferences.getString(KEY_STATE_KIND, null),
            ownerProcessToken = preferences.getString(KEY_OWNER_PROCESS_TOKEN, null),
            currentProcessToken = processToken
        )
        return restoreManagedLibraryProcessingState(
            operationId = preferences.getString(KEY_OPERATION_ID, null),
            reasonName = preferences.getString(KEY_REASON, null),
            phaseName = preferences.getString(KEY_PHASE, null),
            processed = preferences.getIntOrNull(KEY_PROCESSED),
            total = preferences.getIntOrNull(KEY_TOTAL),
            currentItem = preferences.getString(KEY_CURRENT_ITEM, null),
            restoredRunning = restoredRunning
        )
    }

    private fun shouldPersistProgress(state: ManagedLibraryProcessingState): Boolean {
        val previous = lastProgressPersisted ?: return true
        if (previous.operationId != state.operationId || previous.phase != state.phase) {
            return true
        }
        val processed = state.processed
        val total = state.total
        if (processed != null && total != null && processed >= total) {
            return true
        }
        val previousProcessed = previous.processed
        val processedDelta = if (processed != null && previousProcessed != null) {
            processed - previousProcessed
        } else {
            0
        }
        return processedDelta >= PROGRESS_PERSIST_DELTA ||
            System.currentTimeMillis() - lastProgressPersistedAtMs >=
            PROGRESS_PERSIST_INTERVAL_MS
    }

    private fun resetProgressPersistence(state: ManagedLibraryProcessingState) {
        lastProgressPersisted = state.takeIf { current ->
            current.processed != null || current.total != null || current.currentItem != null
        }
        lastProgressPersistedAtMs = if (lastProgressPersisted == null) {
            0L
        } else {
            System.currentTimeMillis()
        }
    }

    private fun SharedPreferences.getIntOrNull(key: String): Int? {
        return if (contains(key)) getInt(key, 0) else null
    }
}
