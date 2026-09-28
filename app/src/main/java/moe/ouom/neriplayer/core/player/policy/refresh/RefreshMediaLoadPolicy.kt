package moe.ouom.neriplayer.core.player.policy.refresh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource

internal data class RefreshRequestSemantics(
    val songKey: String,
    val requestGeneration: Long,
    val resumePositionMs: Long,
    val positionGeneration: Long = 0L,
    val fallbackSeekPositionMs: Long?,
    val resumePlaybackAfterRefresh: Boolean,
    val allowFallback: Boolean,
    val reason: String,
    val resumedPlaybackCommandSource: PlaybackCommandSource?,
    val youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    val cacheKeyToInvalidateBeforeResolve: String? = null
)

internal data class YouTubePlaybackRecoveryStrategy(
    val preferredQualityOverride: String,
    val requireDirect: Boolean,
    val preferM4a: Boolean,
    val allowUnverifiedDirectFallback: Boolean = true
)

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
    val request: RefreshRequestHandle,
    val operation: T,
    val startedNew: Boolean
)

internal class RefreshRequestHandle internal constructor(val semantics: RefreshRequestSemantics)

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

internal fun resolveRefreshedMediaStartPosition(
    pendingSeekPositionMs: Long?,
    requestedResumePositionMs: Long,
    observedPlaybackPositionMs: Long,
    requestedPositionGeneration: Long,
    currentPositionGeneration: Long,
    observedPositionBelongsToRequestedMedia: Boolean = true
): Long {
    pendingSeekPositionMs?.let { return it.coerceAtLeast(0L) }
    val requestedPosition = requestedResumePositionMs.coerceAtLeast(0L)
    if (!observedPositionBelongsToRequestedMedia) return requestedPosition
    val observedPosition = observedPlaybackPositionMs.coerceAtLeast(0L)
    return if (requestedPositionGeneration == currentPositionGeneration) {
        maxOf(requestedPosition, observedPosition)
    } else {
        observedPosition
    }
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
    private class ActiveRefresh<T>(
        val request: RefreshRequestHandle,
        val operation: T,
        val cancel: (T) -> Unit
    )

    private var active: ActiveRefresh<T>? = null

    fun startOrReuse(
        semantics: RefreshRequestSemantics,
        start: (RefreshRequestHandle) -> T,
        cancel: (T) -> Unit
    ): RefreshInFlightStart<T> {
        return synchronized(this) {
            val current = active
            if (current?.request?.semantics == semantics) {
                return@synchronized RefreshInFlightStart(current.request, current.operation, startedNew = false)
            }
            cancelLocked()
            val request = RefreshRequestHandle(semantics)
            val operation = start(request)
            active = ActiveRefresh(request, operation, cancel)
            RefreshInFlightStart(request, operation, startedNew = true)
        }
    }

    fun cancelIfNotReusable(semantics: RefreshRequestSemantics): Boolean {
        return synchronized(this) {
            val current = active ?: return@synchronized false
            if (current.request.semantics == semantics) {
                return@synchronized false
            }
            cancelLocked()
            true
        }
    }

    fun cancelIfPlaybackIntentChanged(shouldResumePlayback: Boolean): Boolean {
        return synchronized(this) {
            val current = active ?: return@synchronized false
            if (current.request.semantics.resumePlaybackAfterRefresh == shouldResumePlayback) {
                return@synchronized false
            }
            cancelLocked()
            true
        }
    }

    fun isCurrent(request: RefreshRequestHandle): Boolean =
        synchronized(this) { active?.request === request }

    fun currentSemantics(): RefreshRequestSemantics? {
        return synchronized(this) { active?.request?.semantics }
    }

    fun clear(request: RefreshRequestHandle) {
        synchronized(this) {
            // 参数相同的新请求仍有独立身份，旧请求的结束回调不能清除它
            if (active?.request === request) active = null
        }
    }

    fun cancelCurrent() {
        synchronized(this) { cancelLocked() }
    }

    private fun cancelLocked() {
        val previous = active
        active = null
        previous?.cancel?.invoke(previous.operation)
    }
}
