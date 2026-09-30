package moe.ouom.neriplayer.core.player.policy.command

import androidx.media3.common.Player

fun resolveExoRepeatMode(
    repeatModeSetting: Int,
    shouldLetPlaybackEndForSleepTimer: Boolean
): Int {
    return if (
        repeatModeSetting == Player.REPEAT_MODE_ONE &&
        !shouldLetPlaybackEndForSleepTimer
    ) {
        Player.REPEAT_MODE_ONE
    } else {
        Player.REPEAT_MODE_OFF
    }
}

fun shouldShowPauseButtonForPlaybackControls(
    resumePlaybackRequested: Boolean,
    pendingPauseJobActive: Boolean
): Boolean {
    return resumePlaybackRequested && !pendingPauseJobActive
}

fun shouldClearResumePlaybackRequestOnPlayWhenReadyPause(
    playWhenReady: Boolean,
    playWhenReadyChangeReason: Int,
    pendingPauseJobActive: Boolean,
    playJobActive: Boolean
): Boolean {
    if (playWhenReady) return false
    if (pendingPauseJobActive || playJobActive) return false
    return when (playWhenReadyChangeReason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> true
        else -> false
    }
}

fun shouldResumeSilentlyForListenTogetherNoisyPause(
    playWhenReady: Boolean,
    playWhenReadyChangeReason: Int,
    muteListenTogetherListenerForAudioRouteLoss: Boolean
): Boolean {
    return !playWhenReady &&
        muteListenTogetherListenerForAudioRouteLoss &&
        playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
}

fun shouldPausePlaybackWhenToggling(
    resumePlaybackRequested: Boolean,
    pendingPauseJobActive: Boolean,
    playerIsPlaying: Boolean,
    playerPlayWhenReady: Boolean,
    playJobActive: Boolean
): Boolean {
    if (pendingPauseJobActive) return false
    return resumePlaybackRequested ||
        playerIsPlaying ||
        playerPlayWhenReady ||
        playJobActive
}
