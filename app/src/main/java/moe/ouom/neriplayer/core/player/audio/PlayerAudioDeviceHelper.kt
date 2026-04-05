package moe.ouom.neriplayer.core.player.audio

import android.media.AudioDeviceInfo

internal fun isBluetoothOutputType(type: Int): Boolean {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
}

internal fun isWiredOutputType(type: Int): Boolean {
    return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET
}

internal fun isHeadsetLikeOutput(type: Int): Boolean {
    return isBluetoothOutputType(type) || isWiredOutputType(type)
}

internal fun requiresDisconnectConfirmation(type: Int): Boolean {
    return isBluetoothOutputType(type)
}
