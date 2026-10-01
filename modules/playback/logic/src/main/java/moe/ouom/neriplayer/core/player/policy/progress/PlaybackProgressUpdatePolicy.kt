package moe.ouom.neriplayer.core.player.policy.progress

const val PLAYBACK_PROGRESS_STARTUP_UPDATE_INTERVAL_MS = 80L
const val PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS = 250L
const val PLAYBACK_PROGRESS_BACKGROUND_UPDATE_INTERVAL_MS = 1_500L
const val PLAYBACK_PROGRESS_STATS_UPDATE_INTERVAL_MS = 1_000L

fun shouldRunPlaybackProgressUpdates(
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

fun hasPlaybackProgressAdvancedSinceBaseline(
    currentPositionMs: Long,
    baselinePositionMs: Long,
    toleranceMs: Long
): Boolean {
    val current = currentPositionMs.coerceAtLeast(0L)
    val baseline = baselinePositionMs.coerceAtLeast(0L)
    return current - baseline > toleranceMs.coerceAtLeast(0L)
}

fun resolvePlaybackProgressUpdateIntervalMs(
    playbackProgressAdvanceReported: Boolean,
    interactiveNowPlayingVisible: Boolean,
    realtimeExternalLyricsActive: Boolean = false
): Long {
    if (!playbackProgressAdvanceReported) {
        return PLAYBACK_PROGRESS_STARTUP_UPDATE_INTERVAL_MS
    }
    return if (interactiveNowPlayingVisible || realtimeExternalLyricsActive) {
        PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS
    } else {
        PLAYBACK_PROGRESS_BACKGROUND_UPDATE_INTERVAL_MS
    }
}
