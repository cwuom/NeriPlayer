package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveBufferResizePolicyTest {
    @Test
    fun `active stream never resizes the ring`() {
        assertFalse(shouldRestoreReservedUsbBuffer(streaming = true, currentBufferMs = 250, reservedBufferMs = 1_500))
        assertFalse(shouldRestoreReservedUsbBuffer(streaming = true, currentBufferMs = 12_000, reservedBufferMs = 5_000))
    }

    @Test
    fun `idle stream grows back to the reserved ring`() {
        assertTrue(shouldRestoreReservedUsbBuffer(streaming = false, currentBufferMs = 250, reservedBufferMs = 1_500))
    }

    @Test
    fun `idle stream never shrinks below the reserved ring`() {
        assertFalse(shouldRestoreReservedUsbBuffer(streaming = false, currentBufferMs = 1_500, reservedBufferMs = 1_500))
        assertFalse(shouldRestoreReservedUsbBuffer(streaming = false, currentBufferMs = 12_000, reservedBufferMs = 5_000))
    }

    @Test
    fun `running waterline keeps half of the lifecycle buffer`() {
        assertEquals(125L, usbExclusiveRunningQueueTargetMs(250))
        assertEquals(750L, usbExclusiveRunningQueueTargetMs(1_500))
    }

    @Test
    fun `foreground transfer window follows the configured safe buffer`() {
        assertEquals(
            1_000,
            usbExclusiveTransferWindowDurationMs(
                bufferDurationMs = 1_000,
                appInForeground = true
            )
        )
    }

    @Test
    fun `foreground transfer window preserves a four hundred millisecond safety reserve`() {
        assertEquals(
            400,
            usbExclusiveTransferWindowDurationMs(
                bufferDurationMs = 400,
                appInForeground = true
            )
        )
    }

    @Test
    fun `background transfer window follows the normalized buffer`() {
        assertEquals(
            3_000,
            usbExclusiveTransferWindowDurationMs(
                bufferDurationMs = 3_020,
                appInForeground = false
            )
        )
    }
}
