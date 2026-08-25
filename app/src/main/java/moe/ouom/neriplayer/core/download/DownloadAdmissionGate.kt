package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DownloadAdmissionGate {
    internal data class ClearToken(
        internal val completion: CompletableDeferred<Unit>,
        internal val generation: Long,
        internal val ownsClear: Boolean
    )

    private data class GateSnapshot(
        val completion: CompletableDeferred<Unit>?,
        val generation: Long
    )

    private val admissionMutex = Mutex()
    private val stateLock = Any()
    private var activeClear: CompletableDeferred<Unit>? = null
    private var generation = 0L

    fun ticket(): Long = synchronized(stateLock) { generation }

    fun openTicketOrNull(): Long? = synchronized(stateLock) {
        generation.takeIf { activeClear == null }
    }

    fun beginClear(): ClearToken = synchronized(stateLock) {
        activeClear?.let { completion ->
            return@synchronized ClearToken(
                completion = completion,
                generation = generation,
                ownsClear = false
            )
        }
        generation++
        val completion = CompletableDeferred<Unit>()
        activeClear = completion
        ClearToken(
            completion = completion,
            generation = generation,
            ownsClear = true
        )
    }

    suspend fun awaitOpen() {
        while (true) {
            val completion = snapshot().completion ?: return
            completion.await()
        }
    }

    suspend fun awaitClear(token: ClearToken) {
        token.completion.await()
    }

    suspend fun admit(
        ticket: Long,
        block: suspend () -> Unit
    ): Boolean {
        while (true) {
            val beforeLock = snapshot()
            if (beforeLock.generation != ticket) {
                return false
            }
            beforeLock.completion?.await()

            var retryAfterClear = false
            val admitted = admissionMutex.withLock {
                val insideLock = snapshot()
                when {
                    insideLock.generation != ticket -> false
                    insideLock.completion != null -> {
                        retryAfterClear = true
                        false
                    }
                    else -> {
                        block()
                        true
                    }
                }
            }
            if (admitted || !retryAfterClear) {
                return admitted
            }
        }
    }

    suspend fun runClear(
        token: ClearToken,
        block: suspend () -> Unit
    ) {
        require(token.ownsClear) { "clear token does not own the active clear" }
        try {
            admissionMutex.withLock {
                block()
            }
        } finally {
            synchronized(stateLock) {
                if (activeClear === token.completion && generation == token.generation) {
                    activeClear = null
                }
            }
            token.completion.complete(Unit)
        }
    }

    private fun snapshot(): GateSnapshot = synchronized(stateLock) {
        GateSnapshot(
            completion = activeClear,
            generation = generation
        )
    }
}

internal class DownloadClearVisibility {
    private val stateLock = Any()
    private val _isClearing = MutableStateFlow(false)
    val isClearing: StateFlow<Boolean> = _isClearing.asStateFlow()
    private var activeGeneration: Long? = null

    fun begin(token: DownloadAdmissionGate.ClearToken) {
        synchronized(stateLock) {
            activeGeneration = token.generation
            _isClearing.value = true
        }
    }

    fun finish(token: DownloadAdmissionGate.ClearToken) {
        synchronized(stateLock) {
            if (activeGeneration == token.generation) {
                activeGeneration = null
                _isClearing.value = false
            }
        }
    }
}
