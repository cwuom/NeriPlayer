package moe.ouom.neriplayer.core.player.service

internal fun shouldKeepPlaybackServiceSticky(
    playerRuntimeReady: Boolean,
    hasPlaybackSurfaceContent: Boolean,
    hasResumableQueue: Boolean,
    foregroundPlaybackRequired: Boolean,
    listenTogetherSessionActive: Boolean,
): Boolean {
    if (!playerRuntimeReady || !hasPlaybackSurfaceContent) return false
    return hasResumableQueue || foregroundPlaybackRequired || listenTogetherSessionActive
}

internal fun shouldUseStickyStartModeWhilePlayerRuntimeInitializes(
    hasExplicitAction: Boolean,
): Boolean = !hasExplicitAction
