package moe.ouom.neriplayer.core.player.policy.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDisconnectConfirmationPolicyTest {

    @Test
    fun `transient speaker sample does not confirm disconnect`() {
        assertFalse(
            shouldConfirmBluetoothDisconnect(
                stopOnBluetoothDisconnectEnabled = true,
                playbackActive = true,
                previousRouteWasBluetooth = true,
                sampledRoutesAreBluetooth = listOf(false, true, false)
            )
        )
    }

    @Test
    fun `three consecutive non bluetooth samples confirm disconnect`() {
        assertTrue(
            shouldConfirmBluetoothDisconnect(
                stopOnBluetoothDisconnectEnabled = true,
                playbackActive = true,
                previousRouteWasBluetooth = true,
                sampledRoutesAreBluetooth = listOf(false, false, false)
            )
        )
    }

    @Test
    fun `inactive or disabled playback never confirms disconnect`() {
        assertFalse(
            shouldConfirmBluetoothDisconnect(
                stopOnBluetoothDisconnectEnabled = false,
                playbackActive = true,
                previousRouteWasBluetooth = true,
                sampledRoutesAreBluetooth = listOf(false, false, false)
            )
        )
        assertFalse(
            shouldConfirmBluetoothDisconnect(
                stopOnBluetoothDisconnectEnabled = true,
                playbackActive = false,
                previousRouteWasBluetooth = true,
                sampledRoutesAreBluetooth = listOf(false, false, false)
            )
        )
    }
}
