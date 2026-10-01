package moe.ouom.neriplayer.core.player.runtime.refresh

fun shouldApplyRefreshResult(
    owner: RefreshRequestSemantics,
    current: RefreshRequestSemantics,
    currentRequestGeneration: Long,
    ownerActive: Boolean
): Boolean {
    return ownerActive &&
        owner == current &&
        owner.requestGeneration == currentRequestGeneration
}

fun resolveRefreshedMediaStartPosition(
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

fun resolveRefreshApplyAction(
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
