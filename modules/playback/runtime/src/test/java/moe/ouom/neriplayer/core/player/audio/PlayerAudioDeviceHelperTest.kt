package moe.ouom.neriplayer.core.player.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerAudioDeviceHelperTest {

    @Test
    fun `bluetooth SCO is recognized as a headset-like output`() {
        assertTrue(isBluetoothOutputType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(isHeadsetLikeOutput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }

    @Test
    fun `hearing aid output receives Bluetooth disconnect protection`() {
        assertTrue(isBluetoothOutputType(AudioDeviceInfo.TYPE_HEARING_AID))
        assertTrue(isHeadsetLikeOutput(AudioDeviceInfo.TYPE_HEARING_AID))
        assertTrue(requiresDisconnectConfirmation(AudioDeviceInfo.TYPE_HEARING_AID))
    }

    @Test
    @Config(sdk = [35])
    fun `BLE broadcast output receives Bluetooth disconnect protection`() {
        assertTrue(isBluetoothOutputType(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertTrue(isHeadsetLikeOutput(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertTrue(requiresDisconnectConfirmation(AudioDeviceInfo.TYPE_BLE_BROADCAST))
    }

    @Test
    @Config(sdk = [31])
    fun `BLE broadcast is not available before Android 13`() {
        assertFalse(isBluetoothOutputType(AudioDeviceInfo.TYPE_BLE_BROADCAST))
    }
}
