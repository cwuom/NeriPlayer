package moe.ouom.neriplayer.core.player.policy.service

fun shouldSchedulePlaybackServiceIdleShutdown(
    playerInitialized: Boolean,
    hasPlaybackSurfaceContent: Boolean,
    transportActive: Boolean,
    transportBuffering: Boolean,
    listenTogetherSessionActive: Boolean,
    usbSessionActiveOrTransitioning: Boolean,
    sleepTimerActive: Boolean,
): Boolean {
    return playerInitialized &&
        hasPlaybackSurfaceContent &&
        !transportActive &&
        !transportBuffering &&
        !listenTogetherSessionActive &&
        !usbSessionActiveOrTransitioning &&
        !sleepTimerActive
}
