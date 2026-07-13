package moe.ouom.neriplayer.core.player.policy.progress

internal const val PLAYBACK_PROGRESS_STARTUP_UPDATE_INTERVAL_MS = 80L
internal const val PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS = 100L
internal const val PLAYBACK_PROGRESS_BACKGROUND_UPDATE_INTERVAL_MS = 750L

internal fun shouldRunPlaybackProgressUpdates(
    initialized: Boolean,
    pendingMediaLoad: Boolean,
    hasMediaItem: Boolean,
    isPlaying: Boolean,
    playWhenReady: Boolean
): Boolean {
    return initialized &&
        !pendingMediaLoad &&
        hasMediaItem &&
        (isPlaying || playWhenReady)
}

internal fun hasPlaybackProgressAdvancedSinceBaseline(
    currentPositionMs: Long,
    baselinePositionMs: Long,
    toleranceMs: Long
): Boolean {
    val current = currentPositionMs.coerceAtLeast(0L)
    val baseline = baselinePositionMs.coerceAtLeast(0L)
    return current - baseline > toleranceMs.coerceAtLeast(0L)
}

internal fun resolvePlaybackProgressUpdateIntervalMs(
    playbackProgressAdvanceReported: Boolean,
    interactiveNowPlayingVisible: Boolean
): Long {
    if (!playbackProgressAdvanceReported) {
        return PLAYBACK_PROGRESS_STARTUP_UPDATE_INTERVAL_MS
    }
    return if (interactiveNowPlayingVisible) {
        PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS
    } else {
        PLAYBACK_PROGRESS_BACKGROUND_UPDATE_INTERVAL_MS
    }
}
