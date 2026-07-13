package moe.ouom.neriplayer.core.player.usb.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveTransportCommandGateTest {

    @Test
    fun `only one transport command can run until the gate is released`() {
        val gate = UsbExclusiveTransportCommandGate()

        assertTrue(gate.tryAcquire())
        assertTrue(gate.isHeld())
        assertFalse(gate.tryAcquire())

        gate.release()

        assertFalse(gate.isHeld())
        assertTrue(gate.tryAcquire())
    }
}
