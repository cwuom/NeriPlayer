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
    fun `sampling window outlasts the platform noisy reroute delay`() {
        val windowMs = (0 until BLUETOOTH_DISCONNECT_CONFIRMATION_MAX_SAMPLES).sumOf(::bluetoothDisconnectSampleDelayMs)
        assertTrue(windowMs > ANDROID_BECOMING_NOISY_REROUTE_DELAY_MS)
        assertTrue(BLUETOOTH_DISCONNECT_CONFIRMATION_MAX_SAMPLES >= BLUETOOTH_DISCONNECT_CONFIRMATION_SAMPLE_COUNT)
    }

    @Test
    fun `routes that leave Bluetooth after the reroute delay still confirm within the window`() {
        assertTrue(
            shouldConfirmBluetoothDisconnect(
                stopOnBluetoothDisconnectEnabled = true,
                playbackActive = true,
                previousRouteWasBluetooth = true,
                sampledRoutesAreBluetooth = listOf(true, true, false, false, false)
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

    private companion object {
        // AudioDeviceInventory.BECOMING_NOISY_DELAY_MS
        const val ANDROID_BECOMING_NOISY_REROUTE_DELAY_MS = 1_000L
    }
}
