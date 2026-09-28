package moe.ouom.neriplayer.core.player.audio.route

import android.media.AudioDeviceInfo
import moe.ouom.neriplayer.core.player.audio.isUsbOutputType
import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.core.player.audio.isWiredOutputType
import moe.ouom.neriplayer.core.player.audio.requiresDisconnectConfirmation
import moe.ouom.neriplayer.core.player.model.AudioDevice
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState

internal fun pausesForImmediateOutputDisconnect(
    state: AudioDeviceRouteSnapshot,
    previousType: Int?,
    nextType: Int?
): Boolean {
    if (previousType == null || !isWiredOutputType(previousType)) return false
    if (state.usbExclusiveEnabled && isUsbOutputType(previousType)) return false
    if (!state.isPlaying) return false
    return nextType == null || nextType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}

internal fun preferredOutputDevice(devices: Array<AudioDeviceInfo>, usbEnabled: Boolean): AudioDeviceInfo? {
    if (usbEnabled) devices.firstOrNull { isUsbOutputType(it.type) }?.let { return it }
    return devices.firstOrNull { isBluetoothOutputType(it.type) }
        ?: devices.firstOrNull { isWiredOutputType(it.type) }
}

internal fun treatsAsUsbRouteJitter(
    state: AudioDeviceRouteSnapshot,
    previous: AudioDevice?,
    next: AudioDevice?
): Boolean {
    if (!state.usbExclusiveEnabled) return false
    val previousUsb = previous.isUsbDevice()
    val nextUsb = next.isUsbDevice()
    if (previousUsb && nextUsb) return previous.usbIdentity() != next.usbIdentity()
    return previousUsb != nextUsb
}

private fun AudioDevice?.usbIdentity(): Pair<Int, String>? = this?.let { it.type to it.name }

private fun AudioDevice?.isUsbDevice(): Boolean = this?.type?.let(::isUsbOutputType) == true

internal fun shouldIgnoreUsbSystemNoisyRoute(state: AudioDeviceRouteSnapshot, deviceType: Int?): Boolean =
    state.usbExclusiveEnabled && deviceType?.let(::isUsbOutputType) == true

internal fun AudioDeviceRouteSnapshot.canResumeInterruptedUsb(nextType: Int): Boolean =
    usbExclusiveEnabled && !allowMixedPlayback && interruptedUsbPlayback && resumeRequested && isUsbOutputType(nextType)

internal fun AudioDeviceRouteSnapshot.playbackActiveForNoisyRoute(): Boolean =
    isPlaying || playWhenReady || resumeRequested

internal fun AudioDeviceRouteSnapshot.nativeOwnsUsbRoute(native: UsbExclusiveNativeState): Boolean =
    usbExclusiveEnabled && (native.transitioning || (native.opened && native.source == "player_pcm"))

internal fun shouldReconfigureUsbPolicy(state: AudioDeviceRouteSnapshot, routeChanged: Boolean): Boolean =
    state.usbExclusiveEnabled && routeChanged

internal fun pausesForBluetoothDisconnect(
    state: AudioDeviceRouteSnapshot,
    previousType: Int?,
    nextType: Int?
): Boolean {
    if (!state.stopOnBluetoothDisconnect || !state.isPlaying) return false
    if (previousType == null || !requiresDisconnectConfirmation(previousType)) return false
    return nextType == null || nextType == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}
