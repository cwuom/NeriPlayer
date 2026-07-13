package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.listentogether.control.passivePositionUpdateTypes

internal fun resolveListenTogetherSoftSyncPlaybackRate(
    driftMs: Long,
    signedDriftMs: Long,
    allowSoftSync: Boolean,
    isController: Boolean,
    softSyncMinDriftMs: Long,
    softSyncFastDriftMs: Long,
    playingDriftForceSyncMs: Long
): Float? {
    if (!allowSoftSync || isController) return null
    if (driftMs !in softSyncMinDriftMs..<playingDriftForceSyncMs) return null
    return when {
        signedDriftMs >= softSyncFastDriftMs -> 1.05f
        signedDriftMs > 0L -> 1.03f
        signedDriftMs <= -softSyncFastDriftMs -> 0.95f
        else -> 0.97f
    }
}

internal fun shouldIgnoreListenTogetherUnexpectedZeroPositionRollback(
    causeType: String?,
    desiredPlaying: Boolean,
    expectedPositionMs: Long,
    localPositionMs: Long,
    playbackContextChanged: Boolean,
    targetIndexChanged: Boolean,
    zeroPositionRollbackGuardMs: Long
): Boolean {
    if (causeType !in passivePositionUpdateTypes) return false
    if (!desiredPlaying) return false
    if (expectedPositionMs > 0L) return false
    if (localPositionMs < zeroPositionRollbackGuardMs) return false
    if (playbackContextChanged || targetIndexChanged) return false
    return true
}
