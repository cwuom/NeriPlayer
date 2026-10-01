package moe.ouom.neriplayer.data.ltw.playback

import kotlin.math.abs

enum class ListenTogetherSoftSyncRecheckAction {
    NONE,
    KEEP_RATE,
    RESET_RATE,
    FORCE_POSITION_SYNC
}

fun resolveListenTogetherSoftSyncRecheckAction(
    currentRate: Float,
    sessionConnected: Boolean,
    isController: Boolean,
    desiredPlaying: Boolean,
    localPlaying: Boolean,
    currentTrackMatchesRoom: Boolean,
    signedDriftMs: Long,
    softSyncMinDriftMs: Long,
    forcePositionSyncDriftMs: Long
): ListenTogetherSoftSyncRecheckAction {
    if (abs(currentRate - 1f) < 0.001f) {
        return ListenTogetherSoftSyncRecheckAction.NONE
    }
    if (
        !sessionConnected ||
        isController ||
        !desiredPlaying ||
        !localPlaying ||
        !currentTrackMatchesRoom
    ) {
        return ListenTogetherSoftSyncRecheckAction.RESET_RATE
    }
    val driftMs = abs(signedDriftMs)
    if (driftMs >= forcePositionSyncDriftMs) {
        return ListenTogetherSoftSyncRecheckAction.FORCE_POSITION_SYNC
    }
    return if (driftMs < softSyncMinDriftMs) {
        ListenTogetherSoftSyncRecheckAction.RESET_RATE
    } else {
        ListenTogetherSoftSyncRecheckAction.KEEP_RATE
    }
}
