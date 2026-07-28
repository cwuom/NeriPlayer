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

internal const val USB_EXCLUSIVE_DEFERRED_RUNTIME_REFRESH_MAX_RETRIES = 4
internal const val USB_EXCLUSIVE_DEFERRED_RUNTIME_REFRESH_RETRY_DELAY_MS = 150L

internal fun shouldRetryUsbExclusiveDeferredRuntimeRefresh(
    runtimeReportValid: Boolean,
    runtimeReportInvalidReason: String?,
    retryAttempt: Int
): Boolean {
    return !runtimeReportValid &&
        runtimeReportInvalidReason == "native_refresh_deferred" &&
        retryAttempt < USB_EXCLUSIVE_DEFERRED_RUNTIME_REFRESH_MAX_RETRIES
}
