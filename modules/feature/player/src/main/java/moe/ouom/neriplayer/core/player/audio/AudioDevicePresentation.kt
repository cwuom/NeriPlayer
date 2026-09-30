package moe.ouom.neriplayer.core.player.audio

import android.media.AudioDeviceInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import moe.ouom.neriplayer.data.model.playback.AudioDevice

val AudioDevice.icon: ImageVector
    get() = when {
        isBluetoothOutputType(type) -> Icons.Default.BluetoothAudio
        isUsbOutputType(type) -> Icons.Default.Usb
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Icons.Default.SpeakerGroup
        else -> Icons.Default.Headset
    }
