package moe.ouom.neriplayer.data.sync

internal fun shouldDeferAutomaticSyncForPlayback(
    forceSync: Boolean,
    triggerByUserAction: Boolean,
    playbackIntentActive: Boolean
): Boolean {
    return !forceSync && !triggerByUserAction && playbackIntentActive
}
