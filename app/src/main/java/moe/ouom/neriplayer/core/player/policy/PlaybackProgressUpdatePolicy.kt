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
