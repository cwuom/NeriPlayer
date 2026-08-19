package moe.ouom.neriplayer.listentogether.playback

internal const val LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE =
    "LISTENER_SAFETY_RESUME"

internal fun shouldMuteListenTogetherListenerForAudioRouteLoss(
    listenTogetherActive: Boolean,
    isCurrentUserController: Boolean
): Boolean {
    return listenTogetherActive &&
        !isCurrentUserController
}

internal fun shouldMuteListenTogetherListenerForOutputDisconnect(
    listenTogetherActive: Boolean,
    isCurrentUserController: Boolean,
    previousRouteWasHeadsetLike: Boolean,
    newRouteIsBuiltinSpeaker: Boolean,
    outputDeviceRemoved: Boolean,
    routeChanged: Boolean
): Boolean {
    if (!shouldMuteListenTogetherListenerForAudioRouteLoss(
            listenTogetherActive = listenTogetherActive,
            isCurrentUserController = isCurrentUserController
        )
    ) {
        return false
    }
    if (!previousRouteWasHeadsetLike) return false
    return newRouteIsBuiltinSpeaker || (outputDeviceRemoved && routeChanged)
}

internal fun shouldHoldListenTogetherPlaybackForSafetyPause(
    safetyPausePendingResume: Boolean,
    causeType: String?
): Boolean {
    return safetyPausePendingResume &&
        causeType != LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE
}
