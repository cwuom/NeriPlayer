package moe.ouom.neriplayer.data.settings

import android.content.SharedPreferences

private const val SAMPLE_RATE_MODE_KEY = "usb_exclusive_sample_rate_mode"
private const val DEVICE_KEY = "usb_exclusive_device_key"
private const val BIT_DEPTH_MODE_KEY = "usb_exclusive_bit_depth_mode"
private const val BUFFER_PROFILE_KEY = "usb_exclusive_buffer_profile"
private const val UNSUPPORTED_FORMAT_POLICY_KEY = "usb_exclusive_unsupported_format_policy"
private const val SAMPLE_RATE_COMPATIBILITY_KEY = "usb_exclusive_sample_rate_compatibility"
private const val BIT_DEPTH_COMPATIBILITY_KEY = "usb_exclusive_bit_depth_compatibility"
private const val CHANNEL_COMPATIBILITY_KEY = "usb_exclusive_channel_compatibility"
private const val FOREGROUND_BUFFER_MS_KEY = "usb_exclusive_foreground_buffer_ms"
private const val BACKGROUND_BUFFER_MS_KEY = "usb_exclusive_background_buffer_ms"
private const val VOLUME_RISK_THRESHOLD_DBFS_KEY = "usb_exclusive_volume_risk_threshold_dbfs"

internal fun SharedPreferences.Editor.putUsbExclusivePreferences(
    preferences: UsbExclusivePreferences
): SharedPreferences.Editor {
    return putString(SAMPLE_RATE_MODE_KEY, preferences.sampleRateMode.storageValue)
        .putString(DEVICE_KEY, preferences.selectedDeviceKey)
        .putString(BIT_DEPTH_MODE_KEY, preferences.bitDepthMode.storageValue)
        .putString(BUFFER_PROFILE_KEY, preferences.bufferProfile.storageValue)
        .putString(
            UNSUPPORTED_FORMAT_POLICY_KEY,
            preferences.unsupportedFormatPolicy.storageValue
        )
        .putBoolean(
            SAMPLE_RATE_COMPATIBILITY_KEY,
            preferences.sampleRateCompatibilityEnabled
        )
        .putBoolean(BIT_DEPTH_COMPATIBILITY_KEY, preferences.bitDepthCompatibilityEnabled)
        .putBoolean(CHANNEL_COMPATIBILITY_KEY, preferences.channelCompatibilityEnabled)
        .putInt(FOREGROUND_BUFFER_MS_KEY, preferences.foregroundBufferMs)
        .putInt(BACKGROUND_BUFFER_MS_KEY, preferences.backgroundBufferMs)
        .putInt(VOLUME_RISK_THRESHOLD_DBFS_KEY, preferences.volumeRiskThresholdDbfs)
}

internal fun SharedPreferences.readUsbExclusivePreferences(): UsbExclusivePreferences {
    return UsbExclusivePreferences.fromStorageValues(
        sampleRateMode = getString(
            SAMPLE_RATE_MODE_KEY,
            DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_MODE
        ),
        selectedDeviceKey = getString(
            DEVICE_KEY,
            DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
        ),
        bitDepthMode = getString(
            BIT_DEPTH_MODE_KEY,
            DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_MODE
        ),
        bufferProfile = getString(
            BUFFER_PROFILE_KEY,
            DEFAULT_USB_EXCLUSIVE_BUFFER_PROFILE
        ),
        unsupportedFormatPolicy = getString(
            UNSUPPORTED_FORMAT_POLICY_KEY,
            DEFAULT_USB_EXCLUSIVE_UNSUPPORTED_FORMAT_POLICY
        ),
        sampleRateCompatibilityEnabled = getBoolean(
            SAMPLE_RATE_COMPATIBILITY_KEY,
            DEFAULT_USB_EXCLUSIVE_SAMPLE_RATE_COMPATIBILITY
        ),
        bitDepthCompatibilityEnabled = getBoolean(
            BIT_DEPTH_COMPATIBILITY_KEY,
            DEFAULT_USB_EXCLUSIVE_BIT_DEPTH_COMPATIBILITY
        ),
        channelCompatibilityEnabled = getBoolean(
            CHANNEL_COMPATIBILITY_KEY,
            DEFAULT_USB_EXCLUSIVE_CHANNEL_COMPATIBILITY
        ),
        foregroundBufferMs = getInt(
            FOREGROUND_BUFFER_MS_KEY,
            DEFAULT_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS
        ),
        backgroundBufferMs = getInt(
            BACKGROUND_BUFFER_MS_KEY,
            DEFAULT_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS
        ),
        volumeRiskThresholdDbfs = getInt(
            VOLUME_RISK_THRESHOLD_DBFS_KEY,
            DEFAULT_USB_EXCLUSIVE_VOLUME_RISK_THRESHOLD_DBFS
        )
    )
}
