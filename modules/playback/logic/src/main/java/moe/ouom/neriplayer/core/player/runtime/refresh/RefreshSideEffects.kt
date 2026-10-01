package moe.ouom.neriplayer.core.player.runtime.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

class RefreshSideEffectGate(
    private val isCurrent: () -> Boolean
) {
    fun runMutation(mutate: () -> Unit): Boolean {
        if (!isCurrent()) return false
        mutate()
        return true
    }

    suspend fun runSuspendingMutation(mutate: suspend () -> Unit): Boolean {
        if (!isCurrent()) return false
        mutate()
        return true
    }

    fun runPreparedMutation(
        prepare: () -> Unit,
        mutate: () -> Unit
    ): Boolean {
        if (!isCurrent()) return false
        prepare()
        if (!isCurrent()) return false
        mutate()
        return true
    }

    fun runMutationSequence(vararg mutations: () -> Unit): Boolean {
        for (mutation in mutations) {
            if (!runMutation(mutation)) return false
        }
        return true
    }
}

class RefreshResolverSideEffects(
    private val gate: RefreshSideEffectGate? = null
) {
    fun updateDuration(update: () -> Unit): Boolean {
        return gate?.runMutation(update) ?: run {
            update()
            true
        }
    }

    fun emitError(emit: () -> Unit): Boolean {
        return gate?.runMutation(emit) ?: run {
            emit()
            true
        }
    }

    fun scanLocalFiles(scan: () -> Unit): Boolean {
        return gate?.runMutation(scan) ?: run {
            scan()
            true
        }
    }
}

class RefreshDeferredCompletion<T>(
    private val deferred: CompletableDeferred<T>
) {
    fun cancel(cause: CancellationException? = null): Boolean {
        if (cause == null) {
            deferred.cancel()
        } else {
            deferred.cancel(cause)
        }
        return deferred.isCancelled
    }

    fun completeExceptionally(error: Throwable): Boolean {
        return deferred.completeExceptionally(error)
    }
}

class RefreshResultSideEffects(
    private val gate: RefreshSideEffectGate
) {
    fun updateDuration(
        beforeMutation: () -> Unit = {},
        update: () -> Unit
    ): Boolean {
        if (!gate.runMutation {}) return false
        beforeMutation()
        return gate.runMutation(update)
    }
}
