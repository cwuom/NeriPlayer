package moe.ouom.neriplayer.core.player.policy

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
