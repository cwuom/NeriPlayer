package moe.ouom.neriplayer.core.player.policy.usb

import org.junit.Assert.assertFalse
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
    fun `active stream allows buffer growth`() {
        assertTrue(
            shouldApplyActiveUsbBufferResize(
                streaming = true,
                currentBufferMs = 5_000,
                targetBufferMs = 12_000
            )
        )
    }

    @Test
    fun `background target growth still applies to active stream`() {
        assertTrue(
            shouldApplyActiveUsbBufferResize(
                streaming = true,
                currentBufferMs = 250,
                targetBufferMs = 1_500
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
}
