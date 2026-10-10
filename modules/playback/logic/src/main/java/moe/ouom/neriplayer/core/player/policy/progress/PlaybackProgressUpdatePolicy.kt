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

/**
 * 只有外部歌词需要进度时，下一次刷新对齐到下一行开始，长句期间不必每 250 ms 唤醒；
 * 结果限制在交互与后台间隔之间，没有下一行时退回后台间隔
 */
fun resolveLyricBoundaryProgressIntervalMs(msUntilNextLine: Long?, playbackSpeed: Float): Long {
    val untilLineMs = msUntilNextLine ?: return PLAYBACK_PROGRESS_BACKGROUND_UPDATE_INTERVAL_MS
    val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
    val wallClockMs = (untilLineMs / speed).toLong() + LYRIC_BOUNDARY_PROGRESS_SLACK_MS
    return wallClockMs.coerceIn(
        PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS,
        PLAYBACK_PROGRESS_BACKGROUND_UPDATE_INTERVAL_MS
    )
}

private const val LYRIC_BOUNDARY_PROGRESS_SLACK_MS = 20L

/** 交互间隔只因外部歌词而启用时，改用歌词行边界间隔；Now Playing 可见时保持原间隔 */
fun resolveProgressIntervalWithLyricBoundary(
    baseIntervalMs: Long,
    interactiveNowPlayingVisible: Boolean,
    lyricBoundaryIntervalMs: () -> Long
): Long {
    val onlyExternalLyricsNeedProgress =
        baseIntervalMs == PLAYBACK_PROGRESS_INTERACTIVE_UPDATE_INTERVAL_MS && !interactiveNowPlayingVisible
    return if (onlyExternalLyricsNeedProgress) lyricBoundaryIntervalMs() else baseIntervalMs
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
