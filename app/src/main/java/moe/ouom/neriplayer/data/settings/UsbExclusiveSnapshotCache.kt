package moe.ouom.neriplayer.data.settings

import android.content.SharedPreferences

private const val SAMPLE_RATE_MODE_KEY = "usb_exclusive_sample_rate_mode"
private const val DEVICE_KEY = "usb_exclusive_device_key"
private const val BIT_DEPTH_MODE_KEY = "usb_exclusive_bit_depth_mode"
private const val BUFFER_PROFILE_KEY = "usb_exclusive_buffer_profile"
private const val UNSUPPORTED_FORMAT_POLICY_KEY = "usb_exclusive_unsupported_format_policy"

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
        )
    )
}
