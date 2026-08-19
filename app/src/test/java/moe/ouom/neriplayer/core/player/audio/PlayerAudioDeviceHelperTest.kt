package moe.ouom.neriplayer.core.player.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAudioDeviceHelperTest {

    @Test
    fun `bluetooth SCO is recognized as a headset-like output`() {
        assertTrue(isBluetoothOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(isHeadsetLikeOutput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }
}
