package moe.ouom.neriplayer.core.player.policy.pending

import androidx.media3.common.Player

internal data class PendingMediaLoadEntryAction(
    val stopProgressUpdates: Boolean,
    val stopPlayer: Boolean,
    val clearMediaItems: Boolean,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val playbackState: Int,
    val positionMs: Long
)

internal data class PendingSeekAction(
    val seekPlayerNow: Boolean,
    val pendingSeekPositionMs: Long?,
    val exposedPositionMs: Long,
    val persistPositionMs: Long
)

internal data class PendingPauseAction(
    val resumePlaybackRequested: Boolean,
    val resumePlaybackAfterLoad: Boolean,
    val persistShouldResumePlayback: Boolean,
    val persistPositionMs: Long
)

internal data class PendingPlayAction(
    val resumePlaybackRequested: Boolean
)

internal fun resolvePendingMediaLoadPosition(
    pendingLoadActive: Boolean,
    requestedPositionMs: Long,
    livePlayerPositionMs: Long
): Long {
    return if (pendingLoadActive) {
        requestedPositionMs.coerceAtLeast(0L)
    } else {
        livePlayerPositionMs.coerceAtLeast(0L)
    }
}

internal fun resolvePendingMediaLoadEntryAction(
    requestedPositionMs: Long
): PendingMediaLoadEntryAction {
    return PendingMediaLoadEntryAction(
        stopProgressUpdates = true,
        stopPlayer = true,
        clearMediaItems = true,
        isPlaying = false,
        playWhenReady = false,
        playbackState = Player.STATE_IDLE,
        positionMs = requestedPositionMs.coerceAtLeast(0L)
    )
}

internal fun shouldAcceptPlayerCallback(
    currentRequestGeneration: Long,
    loadedMediaGeneration: Long,
    pendingLoadActive: Boolean = currentRequestGeneration > loadedMediaGeneration
): Boolean {
    return !pendingLoadActive || loadedMediaGeneration >= currentRequestGeneration
}

internal fun shouldExposePlayerCallbackState(
    currentRequestGeneration: Long,
    loadedMediaGeneration: Long,
    pendingLoadActive: Boolean
): Boolean {
    return shouldAcceptPlayerCallback(
        currentRequestGeneration = currentRequestGeneration,
        loadedMediaGeneration = loadedMediaGeneration,
        pendingLoadActive = pendingLoadActive
    )
}

internal fun resolvePendingSeekAction(
    pendingLoadActive: Boolean,
    requestedPositionMs: Long
): PendingSeekAction {
    val positionMs = requestedPositionMs.coerceAtLeast(0L)
    return PendingSeekAction(
        seekPlayerNow = !pendingLoadActive,
        pendingSeekPositionMs = if (pendingLoadActive) positionMs else null,
        exposedPositionMs = positionMs,
        persistPositionMs = positionMs
    )
}

internal fun resolvePendingPauseAction(
    pendingLoadActive: Boolean,
    exposedPositionMs: Long
): PendingPauseAction {
    return PendingPauseAction(
        resumePlaybackRequested = !pendingLoadActive,
        resumePlaybackAfterLoad = !pendingLoadActive,
        persistShouldResumePlayback = false,
        persistPositionMs = exposedPositionMs.coerceAtLeast(0L)
    )
}

internal fun resolvePendingPlayAction(pendingLoadActive: Boolean): PendingPlayAction {
    return PendingPlayAction(
        resumePlaybackRequested = true
    )
}

internal fun shouldApplyResolvedMedia(
    requestGeneration: Long,
    currentRequestGeneration: Long
): Boolean {
    return requestGeneration == currentRequestGeneration
}

internal fun shouldApplyResolvedMediaSideEffects(
    requestGeneration: Long,
    currentRequestGeneration: Long,
    requestActive: Boolean
): Boolean {
    return requestActive && shouldApplyResolvedMedia(
        requestGeneration = requestGeneration,
        currentRequestGeneration = currentRequestGeneration
    )
}
