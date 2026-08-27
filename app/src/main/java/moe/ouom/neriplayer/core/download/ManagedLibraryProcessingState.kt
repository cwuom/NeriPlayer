package moe.ouom.neriplayer.core.download

import android.annotation.SuppressLint
import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    data object Idle : ManagedLibraryProcessingState {
        override val operationId: String? = null
        override val reason: ManagedLibraryProcessingReason? = null
        override val phase: ManagedLibraryProcessingPhase? = null
        override val processed: Int? = null
        override val total: Int? = null
    }

    data class Running(
        override val operationId: String,
        override val reason: ManagedLibraryProcessingReason,
        override val phase: ManagedLibraryProcessingPhase,
        override val processed: Int? = null,
        override val total: Int? = null
    ) : ManagedLibraryProcessingState

    data class WaitingForRetry(
        override val operationId: String,
        override val reason: ManagedLibraryProcessingReason,
        override val phase: ManagedLibraryProcessingPhase =
            ManagedLibraryProcessingPhase.WAITING_FOR_RETRY,
        override val processed: Int? = null,
        override val total: Int? = null
    ) : ManagedLibraryProcessingState
}

internal object ManagedLibraryProcessingStateMachine {
    fun begin(
        operationId: String,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase
    ): ManagedLibraryProcessingState.Running {
        return ManagedLibraryProcessingState.Running(
            operationId = operationId,
            reason = reason,
            phase = phase
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
                processed = null,
                total = null
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
        total: Int?
    ): ManagedLibraryProcessingState {
        if (current.operationId != operationId) return current
        return when (current) {
            ManagedLibraryProcessingState.Idle -> current
            is ManagedLibraryProcessingState.Running -> current.copy(
                processed = processed,
                total = total
            )
            is ManagedLibraryProcessingState.WaitingForRetry -> current.copy(
                processed = processed,
                total = total
            )
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
            processed = current.processed,
            total = current.total
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
}

internal fun restoreManagedLibraryProcessingState(
    operationId: String?,
    reasonName: String?,
    phaseName: String?
): ManagedLibraryProcessingState {
    val restoredOperationId = operationId?.takeIf(String::isNotBlank)
        ?: return ManagedLibraryProcessingState.Idle
    val restoredReason = reasonName?.let { value ->
        runCatching { ManagedLibraryProcessingReason.valueOf(value) }.getOrNull()
    } ?: return ManagedLibraryProcessingState.Idle
    val phaseIsKnown = phaseName?.let { value ->
        runCatching { ManagedLibraryProcessingPhase.valueOf(value) }.isSuccess
    } == true
    if (!phaseIsKnown) return ManagedLibraryProcessingState.Idle

    return ManagedLibraryProcessingState.WaitingForRetry(
        operationId = restoredOperationId,
        reason = restoredReason
    )
}

internal class ManagedLibraryProcessingBusyException(
    activeReason: ManagedLibraryProcessingReason?
) : IllegalStateException("managed library processing is busy: ${activeReason?.name ?: "unknown"}")

object ManagedLibraryProcessingCoordinator {
    private const val PREFERENCES_NAME = "managed_library_processing"
    private const val KEY_OPERATION_ID = "operation_id"
    private const val KEY_REASON = "reason"
    private const val KEY_PHASE = "phase"

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ManagedLibraryProcessingState>(
        ManagedLibraryProcessingState.Idle
    )

    val state: StateFlow<ManagedLibraryProcessingState> = mutableState.asStateFlow()

    suspend fun restore(context: Context): ManagedLibraryProcessingState = mutex.withLock {
        if (mutableState.value != ManagedLibraryProcessingState.Idle) {
            return@withLock mutableState.value
        }
        val restored = withContext(Dispatchers.IO) {
            readPersistedState(context.applicationContext)
        }
        mutableState.value = restored
        restored
    }

    suspend fun begin(
        context: Context,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase
    ): String = mutex.withLock {
        val next = ManagedLibraryProcessingStateMachine.begin(
            operationId = UUID.randomUUID().toString(),
            reason = reason,
            phase = phase
        )
        persist(context.applicationContext, next)
        mutableState.value = next
        next.operationId
    }

    suspend fun beginIfIdle(
        context: Context,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase
    ): String? = mutex.withLock {
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
        next.operationId
    }

    suspend fun tryBeginExclusive(
        context: Context,
        reason: ManagedLibraryProcessingReason,
        phase: ManagedLibraryProcessingPhase,
        resumeWaitingOperation: Boolean = false
    ): String? = mutex.withLock {
        val next = ManagedLibraryProcessingStateMachine.tryBeginExclusive(
            current = mutableState.value,
            operationId = UUID.randomUUID().toString(),
            reason = reason,
            phase = phase,
            resumeWaitingOperation = resumeWaitingOperation
        ) ?: return@withLock null
        persist(context.applicationContext, next)
        mutableState.value = next
        next.operationId
    }

    suspend fun updateProgress(
        operationId: String,
        processed: Int?,
        total: Int?
    ) = mutex.withLock {
        mutableState.value = ManagedLibraryProcessingStateMachine.updateProgress(
            current = mutableState.value,
            operationId = operationId,
            processed = processed,
            total = total
        )
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
        }
    }

    suspend fun complete(context: Context, operationId: String) = mutex.withLock {
        val current = mutableState.value
        val next = ManagedLibraryProcessingStateMachine.complete(current, operationId)
        if (next != current) {
            persist(context.applicationContext, next)
            mutableState.value = next
        }
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
            preferences.edit()
                .putString(KEY_OPERATION_ID, state.operationId)
                .putString(KEY_REASON, state.reason?.name)
                .putString(KEY_PHASE, state.phase?.name)
                .commit()
        }
        if (!committed) {
            throw IOException("failed to persist managed library processing state")
        }
    }

    private fun readPersistedState(context: Context): ManagedLibraryProcessingState {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        return restoreManagedLibraryProcessingState(
            operationId = preferences.getString(KEY_OPERATION_ID, null),
            reasonName = preferences.getString(KEY_REASON, null),
            phaseName = preferences.getString(KEY_PHASE, null)
        )
    }
}
