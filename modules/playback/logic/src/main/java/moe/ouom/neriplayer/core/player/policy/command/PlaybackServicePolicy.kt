package moe.ouom.neriplayer.core.player.policy.command

import androidx.media3.common.Player

private val LOCAL_PLAYBACK_SERVICE_COMMANDS = setOf("PLAY", "PLAY_PLAYLIST", "PLAY_FROM_QUEUE", "NEXT", "PREVIOUS")

fun shouldRunPlaybackServiceInForeground(
    hasCurrentSong: Boolean,
    resumePlaybackRequested: Boolean,
    playJobActive: Boolean,
    pendingPauseJobActive: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    playerPlaybackState: Int
): Boolean {
    if (!hasCurrentSong) return false
    return resumePlaybackRequested ||
        playJobActive ||
        pendingPauseJobActive ||
        playWhenReady ||
        isPlaying ||
        playerPlaybackState == Player.STATE_BUFFERING
}

fun shouldBootstrapPlaybackServiceOnAppLaunch(
    hasCurrentSong: Boolean,
    hasPendingRestoredPlaybackResume: Boolean,
    resumePlaybackRequested: Boolean,
    playJobActive: Boolean,
    pendingPauseJobActive: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    playerPlaybackState: Int
): Boolean {
    if (!hasCurrentSong) return false
    return hasPendingRestoredPlaybackResume ||
        resumePlaybackRequested ||
        playJobActive ||
        pendingPauseJobActive ||
        playWhenReady ||
        isPlaying ||
        playerPlaybackState == Player.STATE_BUFFERING
}

fun shouldSyncPlaybackServiceForLocalPlaybackCommand(type: String): Boolean {
    return type in LOCAL_PLAYBACK_SERVICE_COMMANDS
}
