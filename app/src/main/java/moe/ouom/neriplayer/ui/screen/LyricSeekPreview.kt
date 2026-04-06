package moe.ouom.neriplayer.ui.screen

import kotlin.math.abs

internal const val LyricSeekPreviewSettleToleranceMs = 280L

internal fun resolveLyricPreviewTimeMs(
    isDraggingSlider: Boolean,
    sliderPreviewPositionMs: Long,
    pendingSeekPreviewPositionMs: Long?,
    playbackPositionMs: Long
): Long {
    return when {
        isDraggingSlider -> sliderPreviewPositionMs
        pendingSeekPreviewPositionMs != null -> pendingSeekPreviewPositionMs
        else -> playbackPositionMs
    }.coerceAtLeast(0L)
}

internal fun shouldReleaseLyricSeekPreview(
    playbackPositionMs: Long,
    pendingSeekPreviewPositionMs: Long,
    toleranceMs: Long = LyricSeekPreviewSettleToleranceMs
): Boolean {
    return abs(playbackPositionMs - pendingSeekPreviewPositionMs) <= toleranceMs
}

internal fun shouldAnimateAdvancedLyricsFromPlayback(
    isPlaying: Boolean,
    isDraggingSlider: Boolean,
    pendingSeekPreviewPositionMs: Long?
): Boolean {
    return isPlaying && !isDraggingSlider && pendingSeekPreviewPositionMs == null
}
