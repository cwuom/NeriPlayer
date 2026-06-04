package moe.ouom.neriplayer.core.player.policy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal data class RefreshRequestSemantics(
    val songKey: String,
    val requestGeneration: Long,
    val resumePositionMs: Long,
    val fallbackSeekPositionMs: Long?,
    val resumePlaybackAfterRefresh: Boolean,
    val allowFallback: Boolean,
    val reason: String,
    val resumedPlaybackCommandSource: PlaybackCommandSource?
)

internal data class RefreshInFlightOwner(
    val semantics: RefreshRequestSemantics,
    val isActive: Boolean
)

internal enum class RefreshInFlightDecision {
    StartNew,
    ReuseExisting,
    CancelExistingAndStartNew
}

internal enum class RefreshResultKind {
    SUCCESS,
    FALLBACK,
    FAILURE
}

internal data class RefreshApplyAction(
    val updateDuration: Boolean,
    val updateUrl: Boolean,
    val updateAudioInfo: Boolean,
    val persist: Boolean,
    val installMediaItem: Boolean,
    val clearPendingMediaLoad: Boolean,
    val resetFailureCounter: Boolean,
    val emitPlaybackCommand: Boolean,
    val clearPendingSeek: Boolean,
    val updateLoadedGeneration: Boolean,
    val fallbackSeek: Boolean,
    val fallbackPlayPause: Boolean,
    val emitFailureError: Boolean,
    val pauseAfterFailure: Boolean
)

internal data class RefreshInFlightStart<T>(
    val operation: T,
    val startedNew: Boolean
)

internal class RefreshSideEffectGate(
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

internal class RefreshResolverSideEffects(
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

internal class RefreshDeferredCompletion<T>(
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

internal class RefreshResultSideEffects(
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

internal fun resolveRefreshInFlightDecision(
    owner: RefreshInFlightOwner?,
    incoming: RefreshRequestSemantics
): RefreshInFlightDecision {
    if (owner == null || !owner.isActive) return RefreshInFlightDecision.StartNew
    return if (owner.semantics == incoming) {
        RefreshInFlightDecision.ReuseExisting
    } else {
        RefreshInFlightDecision.CancelExistingAndStartNew
    }
}

internal fun shouldApplyRefreshResult(
    owner: RefreshRequestSemantics,
    current: RefreshRequestSemantics,
    currentRequestGeneration: Long,
    ownerActive: Boolean
): Boolean {
    return ownerActive &&
        owner == current &&
        owner.requestGeneration == currentRequestGeneration
}

internal fun resolveRefreshApplyAction(
    accepted: Boolean,
    resultKind: RefreshResultKind
): RefreshApplyAction {
    val success = accepted && resultKind == RefreshResultKind.SUCCESS
    val fallback = accepted && resultKind == RefreshResultKind.FALLBACK
    val failure = accepted && resultKind == RefreshResultKind.FAILURE
    return RefreshApplyAction(
        updateDuration = success,
        updateUrl = success,
        updateAudioInfo = success,
        persist = success,
        installMediaItem = success,
        clearPendingMediaLoad = success,
        resetFailureCounter = success,
        emitPlaybackCommand = success,
        clearPendingSeek = success || failure,
        updateLoadedGeneration = success,
        fallbackSeek = fallback,
        fallbackPlayPause = fallback,
        emitFailureError = failure,
        pauseAfterFailure = failure
    )
}

internal class RefreshInFlightController<T> {
    private var owner: RefreshInFlightOwner? = null
    private var operation: T? = null
    private var cancelCurrent: (() -> Unit)? = null

    fun startOrReuse(
        semantics: RefreshRequestSemantics,
        start: () -> T,
        cancel: () -> Unit,
        fallback: () -> Unit
    ): RefreshInFlightStart<T> {
        return synchronized(this) {
            when (resolveRefreshInFlightDecision(owner, semantics)) {
                RefreshInFlightDecision.StartNew -> startLocked(semantics, start, cancel)
                RefreshInFlightDecision.ReuseExisting -> {
                    @Suppress("UNCHECKED_CAST")
                    RefreshInFlightStart(operation as T, startedNew = false)
                }
                RefreshInFlightDecision.CancelExistingAndStartNew -> {
                    cancelCurrent?.invoke()
                    startLocked(semantics, start, cancel)
                }
            }
        }
    }

    fun cancelIfNotReusable(semantics: RefreshRequestSemantics): Boolean {
        return synchronized(this) {
            val currentOwner = owner ?: return@synchronized false
            if (!currentOwner.isActive || currentOwner.semantics == semantics) {
                return@synchronized false
            }
            cancelCurrent?.invoke()
            clearLocked(currentOwner.semantics)
            true
        }
    }

    fun isCurrent(semantics: RefreshRequestSemantics): Boolean {
        return synchronized(this) {
            owner?.let { it.isActive && it.semantics == semantics } == true
        }
    }

    fun currentSemantics(): RefreshRequestSemantics? {
        return synchronized(this) { owner?.semantics }
    }

    fun clear(semantics: RefreshRequestSemantics) {
        synchronized(this) { clearLocked(semantics) }
    }

    private fun startLocked(
        semantics: RefreshRequestSemantics,
        start: () -> T,
        cancel: () -> Unit
    ): RefreshInFlightStart<T> {
        val newOperation = start()
        owner = RefreshInFlightOwner(semantics = semantics, isActive = true)
        operation = newOperation
        cancelCurrent = cancel
        return RefreshInFlightStart(newOperation, startedNew = true)
    }

    private fun clearLocked(semantics: RefreshRequestSemantics) {
        if (owner?.semantics != semantics) return
        owner = null
        operation = null
        cancelCurrent = null
    }
}
