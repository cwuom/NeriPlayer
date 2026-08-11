package moe.ouom.neriplayer.listentogether.playback

internal const val LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE =
    "LISTENER_SAFETY_RESUME"

internal fun shouldMuteListenTogetherListenerForAudioRouteLoss(
    listenTogetherActive: Boolean,
    isCurrentUserController: Boolean,
    allowMemberControl: Boolean?
): Boolean {
    return listenTogetherActive &&
        !isCurrentUserController &&
        allowMemberControl == false
}

internal fun shouldHoldListenTogetherPlaybackForSafetyPause(
    safetyPausePendingResume: Boolean,
    causeType: String?
): Boolean {
    return safetyPausePendingResume &&
        causeType != LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE
}
