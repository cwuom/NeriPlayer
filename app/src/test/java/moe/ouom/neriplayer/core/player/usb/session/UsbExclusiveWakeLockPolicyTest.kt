package moe.ouom.neriplayer.core.player.usb.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveWakeLockPolicyTest {

    @Test
    fun `opened but paused player session does not hold wake lock`() {
        assertFalse(
            shouldHoldUsbExclusiveWakeLock(
                streaming = false,
                transitioning = false,
                nativeCloseInFlightCount = 0
            )
        )
    }

    @Test
    fun `active native stream holds wake lock`() {
        assertTrue(
            shouldHoldUsbExclusiveWakeLock(
                streaming = true,
                transitioning = false,
                nativeCloseInFlightCount = 0
            )
        )
    }

    @Test
    fun `native transition and close retain wake lock until resources settle`() {
        assertTrue(
            shouldHoldUsbExclusiveWakeLock(
                streaming = false,
                transitioning = true,
                nativeCloseInFlightCount = 0
            )
        )
        assertTrue(
            shouldHoldUsbExclusiveWakeLock(
                streaming = false,
                transitioning = false,
                nativeCloseInFlightCount = 1
            )
        )
    }
}
