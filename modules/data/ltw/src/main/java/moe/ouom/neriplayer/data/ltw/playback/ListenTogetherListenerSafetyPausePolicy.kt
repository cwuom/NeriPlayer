package moe.ouom.neriplayer.data.ltw.playback

const val LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE =
    "LISTENER_SAFETY_RESUME"

fun shouldMuteListenTogetherListenerForAudioRouteLoss(
    listenTogetherActive: Boolean,
    isCurrentUserController: Boolean
): Boolean {
    return listenTogetherActive &&
        !isCurrentUserController
}

fun shouldMuteListenTogetherListenerForOutputDisconnect(
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

fun shouldHoldListenTogetherPlaybackForSafetyPause(
    safetyPausePendingResume: Boolean,
    causeType: String?
): Boolean {
    return safetyPausePendingResume &&
        causeType != LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE
}
