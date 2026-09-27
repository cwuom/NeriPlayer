package moe.ouom.neriplayer.core.player.usb.route

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveErrorCode
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.transport.isRecoverableTransportFailure
import moe.ouom.neriplayer.core.player.usb.transport.usbExclusiveErrorCode
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences

internal fun UsbExclusivePreferences.requiresRouteReconfiguration(next: UsbExclusivePreferences): Boolean =
    routeSelection() != next.routeSelection()

private fun UsbExclusivePreferences.routeSelection() = listOf(
    selectedDeviceKey, sampleRateMode, bitDepthMode, unsupportedFormatPolicy,
    sampleRateCompatibilityEnabled, bitDepthCompatibilityEnabled, channelCompatibilityEnabled
)

internal fun String.isActivationReason(): Boolean {
    if (!contains("usb", true) && !contains("native", true)) return false
    if (listOf("disabled", "fallback", "failed").any { contains(it, true) }) return false
    return listOf("enabled", "preference", "policy", "foreground", "permission", "device")
        .any { contains(it, true) }
}

internal fun String.isUserDrivenActivation(): Boolean =
    listOf("enabled", "manual", "playback_start", "permission", "preference").any { contains(it, true) }

internal fun shouldDeferAutomaticRetry(state: UsbPlaybackRouteSnapshot, reason: String): Boolean =
    state.playbackActive && reason.isActivationReason() && !reason.isUserDrivenActivation()

internal fun String?.isRecoverableFallback(): Boolean {
    val reason = this ?: return false
    if (reason.isNativeTransitionInFlightGate()) return false
    if (reason.containsPermanentFallbackMarker()) return false
    if (reason.startsWithUnsupportedFormat()) return false
    return true
}

private fun String.containsPermanentFallbackMarker(): Boolean =
    listOf("permission", "usb_device_detached", "feedback_scheduler", "requires_system",
        "requires_system_audio", "playback_parameters_require", "skip_silence_requires",
        "tunneling_requires", "aux_effect_requires", "equalizer_requires", "loudness_requires")
        .any { contains(it, true) }

private fun String.startsWithUnsupportedFormat(): Boolean {
    if (listOf("no_", "No permitted", "unsupported_input", "channel_count_unsupported")
            .any { startsWith(it, true) }) return true
    return startsWith("sample_rate_unsupported") || startsWith("bit_depth_unsupported")
}

internal fun String.isNativeTransitionInFlightGate(): Boolean =
    startsWith("native_transition_in_flight") || startsWith("transition_in_flight")

internal fun String.isRecoverableTransferFailure(): Boolean {
    if (usbExclusiveErrorCode().isRecoverableTransportFailure) return true
    if (contains("LIBUSB_ERROR_NO_DEVICE", true) || contains("permission", true)) return false
    return isFirstCompletionTimeout() || listOf("native_transport_failed", "transportFailed=true",
        "LIBUSB_ERROR_IO", "transfer_status=5", "resubmit_failed", "submiturb failed")
        .any { contains(it, true) }
}

internal fun String.isFirstCompletionTimeout(): Boolean =
    usbExclusiveErrorCode() == UsbExclusiveErrorCode.TransferFirstCompletionTimeout ||
        contains("event_loop_first_completion_timeout", true)

internal fun UsbPlaybackRouteSnapshot.allowsRecovery(forceRecovery: Boolean): Boolean {
    if (!enabled || mixedPlaybackEnabled || !playerInitialized) return false
    return forceRecovery || playbackActive
}

internal fun UsbPlaybackRouteSnapshot.canRecoverTransport(): Boolean = enabled && playerInitialized

internal fun UsbPlaybackRouteSnapshot.hasPendingManualIntent(): Boolean = enabled && resumeRequested

internal fun UsbPlaybackRouteSnapshot.hasManualPlaybackItem(): Boolean =
    enabled && playerInitialized && mediaItemCount > 0 && hasMediaItem

internal fun hasPendingPlaybackIntent(resumeRequested: Boolean, interrupted: Boolean, playJobActive: Boolean): Boolean =
    resumeRequested || interrupted || playJobActive

internal fun playbackSignalsActive(isPlaying: Boolean, playWhenReady: Boolean): Boolean = isPlaying || playWhenReady

internal fun keepPlaybackActiveForSwitch(
    initialized: Boolean,
    pendingIntent: () -> Boolean,
    currentTransport: () -> Boolean
): Boolean {
    if (!initialized) return false
    if (pendingIntent()) return true
    return currentTransport()
}

internal fun canStopAfterNativeFailure(initialized: Boolean, usbEnabled: Boolean): Boolean =
    initialized && usbEnabled

internal fun hasCurrentTrack(initialized: Boolean, currentSongPresent: Boolean): Boolean =
    initialized && currentSongPresent

internal fun activePlaybackJob(job: Job?): Boolean = job?.isActive == true

internal fun shouldWaitForOpenGate(
    route: UsbPlaybackRouteSnapshot,
    gate: String?,
    elapsedMs: Long,
    timeoutMs: Long
): Boolean = route.enabled && route.resumeRequested && gate != null && elapsedMs < timeoutMs

internal fun UsbPlaybackRouteSnapshot.releaseMediaItemIndex(): Int? =
    if (playerInitialized && hasMediaItem && mediaItemCount > 0) mediaItemIndex.coerceIn(0, mediaItemCount - 1) else null

internal fun UsbPlaybackRouteSnapshot.releasePositionMs(): Long? =
    if (playerInitialized && hasMediaItem) positionMs.coerceAtLeast(0L) else null

internal fun reusableNativePlaybackRoute(
    path: UsbExclusiveAudioPathState,
    native: UsbExclusiveNativeState
): Boolean {
    if (path.effectivePath != UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB || path.fallbackReason != null) return false
    if (native.source != "player_pcm" || !native.opened || native.transitioning) return false
    return native.lastError.isNullOrBlank()
}

internal fun UsbExclusiveNativeState.blocksRouteRecovery(): Boolean = transitioning || source == "tone"

internal fun needsUsbRouteRecovery(
    path: UsbExclusiveAudioPathState,
    native: UsbExclusiveNativeState,
    forceRecovery: Boolean
): Boolean {
    val recoverableFallback = path.fallbackReason.isRecoverableFallback()
    if (path.intentionalSystemFallback(recoverableFallback) && !forceRecovery) return false
    return forceRecovery || recoverableFallback || path.staleSystemPath() || path.stoppedNativePath(native)
}

private fun UsbExclusiveAudioPathState.intentionalSystemFallback(recoverableFallback: Boolean): Boolean =
    effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM && fallbackReason != null && !recoverableFallback

private fun UsbExclusiveAudioPathState.staleSystemPath(): Boolean =
    effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM && fallbackReason == null

private fun UsbExclusiveAudioPathState.stoppedNativePath(native: UsbExclusiveNativeState): Boolean =
    effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB && !native.playerPcmStreaming()

private fun UsbExclusiveNativeState.playerPcmStreaming(): Boolean = source == "player_pcm" && streaming
