package moe.ouom.neriplayer.core.player.policy.command

data class PauseVolumePlan(
    val shouldFadeOut: Boolean,
    val resetVolumeBeforePause: Boolean,
    val restoreVolumeAfterPause: Boolean
)

fun resolvePauseVolumePlan(
    allowFadeOut: Boolean,
    preserveMutedVolume: Boolean,
    playbackFadeInEnabled: Boolean,
    playbackFadeOutDurationMs: Long,
    isPlayerInitialized: Boolean
): PauseVolumePlan {
    val shouldFadeOut = allowFadeOut &&
        playbackFadeInEnabled &&
        playbackFadeOutDurationMs > 0L &&
        isPlayerInitialized
    return when {
        shouldFadeOut -> PauseVolumePlan(
            shouldFadeOut = true,
            resetVolumeBeforePause = false,
            restoreVolumeAfterPause = true
        )

        preserveMutedVolume -> PauseVolumePlan(
            shouldFadeOut = false,
            resetVolumeBeforePause = false,
            restoreVolumeAfterPause = false
        )

        else -> PauseVolumePlan(
            shouldFadeOut = false,
            resetVolumeBeforePause = true,
            restoreVolumeAfterPause = false
        )
    }
}
