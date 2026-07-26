package moe.ouom.neriplayer.core.player.policy.usb

internal enum class UsbExclusiveForegroundRecoveryAction {
    NONE,
    PROBE_PROGRESS,
    RECOVER_STOPPED_TRANSPORT
}

internal fun resolveUsbExclusiveForegroundRecoveryAction(
    nativePathActive: Boolean,
    sinkPlaying: Boolean,
    nativeOpened: Boolean,
    nativeStreaming: Boolean,
    nativePaused: Boolean,
    nativeTransitioning: Boolean
): UsbExclusiveForegroundRecoveryAction {
    if (
        !nativePathActive ||
        !sinkPlaying ||
        nativePaused ||
        nativeTransitioning
    ) {
        return UsbExclusiveForegroundRecoveryAction.NONE
    }
    return if (nativeOpened && nativeStreaming) {
        UsbExclusiveForegroundRecoveryAction.PROBE_PROGRESS
    } else {
        UsbExclusiveForegroundRecoveryAction.RECOVER_STOPPED_TRANSPORT
    }
}

internal fun shouldRestoreUsbExclusiveForegroundPlaybackIntent(
    action: UsbExclusiveForegroundRecoveryAction,
    transportActive: Boolean
): Boolean {
    return action != UsbExclusiveForegroundRecoveryAction.NONE && !transportActive
}
