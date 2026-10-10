package moe.ouom.neriplayer.core.player.audio

import android.media.AudioDeviceInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Usb
import moe.ouom.neriplayer.data.model.playback.AudioDevice
import org.junit.Assert.assertSame
import org.junit.Test

class AudioDeviceIconTest {

    @Test
    fun `bluetooth outputs use the bluetooth audio icon`() {
        listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO).forEach { type ->
            assertSame(Icons.Default.BluetoothAudio, AudioDevice("Buds", type).icon)
        }
    }

    @Test
    fun `usb outputs use the usb icon`() {
        listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET
        ).forEach { type -> assertSame(Icons.Default.Usb, AudioDevice("DAC", type).icon) }
    }

    @Test
    fun `the built in speaker and every other output keep distinct icons`() {
        assertSame(Icons.Default.SpeakerGroup, AudioDevice("Phone", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).icon)
        assertSame(Icons.Default.Headset, AudioDevice("Wired", AudioDeviceInfo.TYPE_WIRED_HEADPHONES).icon)
        assertSame(Icons.Default.Headset, AudioDevice("HDMI", AudioDeviceInfo.TYPE_HDMI).icon)
    }
}
