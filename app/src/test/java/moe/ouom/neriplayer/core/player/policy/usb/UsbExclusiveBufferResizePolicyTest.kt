package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveBufferResizePolicyTest {
    @Test
    fun `active stream defers destructive buffer shrink`() {
        assertFalse(
            shouldApplyActiveUsbBufferResize(
                streaming = true,
                currentBufferMs = 12_000,
                targetBufferMs = 5_000
            )
        )
    }

    @Test
    fun `active stream defers buffer growth to avoid live reallocation`() {
        assertFalse(
            shouldApplyActiveUsbBufferResize(
                streaming = true,
                currentBufferMs = 5_000,
                targetBufferMs = 12_000
            )
        )
    }

    @Test
    fun `background transition only changes the transfer window`() {
        assertFalse(
            shouldApplyActiveUsbBufferResize(
                streaming = true,
                currentBufferMs = 250,
                targetBufferMs = 1_500
            )
        )
    }

    @Test
    fun `unchanged active capacity only needs transfer window update`() {
        assertFalse(
            shouldApplyActiveUsbBufferResize(
                streaming = true,
                currentBufferMs = 3_000,
                targetBufferMs = 3_000
            )
        )
    }

    @Test
    fun `idle stream applies the next configured buffer`() {
        assertTrue(
            shouldApplyActiveUsbBufferResize(
                streaming = false,
                currentBufferMs = 12_000,
                targetBufferMs = 5_000
            )
        )
    }

    @Test
    fun `idle stream skips an unchanged buffer`() {
        assertFalse(
            shouldApplyActiveUsbBufferResize(
                streaming = false,
                currentBufferMs = 1_500,
                targetBufferMs = 1_500
            )
        )
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
