package moe.ouom.neriplayer.data.settings.usb

import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.playback.sanitized

import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot

fun PlaybackPreferenceSnapshot.toUsbExclusivePreferences(): UsbExclusivePreferences {
    val normalizedSnapshot = sanitized()
    return UsbExclusivePreferences.fromStorageValues(
        selectedDeviceKey = normalizedSnapshot.usbExclusiveDeviceKey,
        sampleRateMode = normalizedSnapshot.usbExclusiveSampleRateMode,
        bitDepthMode = normalizedSnapshot.usbExclusiveBitDepthMode,
        bitPerfect = normalizedSnapshot.usbExclusiveBitPerfect,
        bufferProfile = normalizedSnapshot.usbExclusiveBufferProfile,
        unsupportedFormatPolicy = normalizedSnapshot.usbExclusiveUnsupportedFormatPolicy,
        sampleRateCompatibilityEnabled =
            normalizedSnapshot.usbExclusiveSampleRateCompatibility,
        bitDepthCompatibilityEnabled =
            normalizedSnapshot.usbExclusiveBitDepthCompatibility,
        channelCompatibilityEnabled =
            normalizedSnapshot.usbExclusiveChannelCompatibility,
        foregroundBufferMs = normalizedSnapshot.usbExclusiveForegroundBufferMs,
        backgroundBufferMs = normalizedSnapshot.usbExclusiveBackgroundBufferMs,
        volumeRiskThresholdDbfs = normalizedSnapshot.usbExclusiveVolumeRiskThresholdDbfs
    )
}
