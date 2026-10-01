package moe.ouom.neriplayer.core.player.usb.session

import android.content.Context
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.settings.playback.readPlaybackPreferenceSnapshotSync
import moe.ouom.neriplayer.data.settings.usb.toUsbExclusivePreferences

internal fun interface UsbExclusiveSelectedDeviceKeyPort {
    fun readSelectedDeviceKey(context: Context): String
}

internal object AndroidUsbExclusiveSelectedDeviceKeyPort : UsbExclusiveSelectedDeviceKeyPort {
    override fun readSelectedDeviceKey(context: Context): String {
        if (PlayerManager.isPlayerInitialized()) {
            return PlayerManager.usbExclusivePreferences.selectedDeviceKey
        }
        return readPlaybackPreferenceSnapshotSync(context)
            .toUsbExclusivePreferences()
            .selectedDeviceKey
    }
}
