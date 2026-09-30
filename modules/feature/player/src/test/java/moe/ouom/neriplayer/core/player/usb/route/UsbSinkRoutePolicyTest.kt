package moe.ouom.neriplayer.core.player.usb.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSinkRoutePolicyTest {
    private val active = UsbSinkRouteSnapshot(1L, true, true, true, true, true, 1, 0, 0L, true)

    @Test
    fun `media and foreground gates distinguish rebuild from deferred switch`() {
        assertTrue(active.hasRouteMedia())
        assertTrue(active.hasReconfigurableItemCount())
        assertTrue(active.readyForDeferredSwitch())
        assertTrue(active.waitForPlaybackToStop())
        assertFalse(active.copy(playerInitialized = false).hasRouteMedia())
        assertFalse(active.copy(hasMediaItem = false).hasRouteMedia())
        assertFalse(active.copy(mediaItemCount = 0).hasReconfigurableItemCount())
        assertFalse(active.copy(enabled = false).readyForDeferredSwitch())
        assertFalse(active.copy(appInForeground = false).readyForDeferredSwitch())
        assertFalse(active.copy(playerInitialized = false).readyForDeferredSwitch())
        assertFalse(active.copy(hasMediaItem = false).readyForDeferredSwitch())
        assertFalse(active.copy(playbackActive = false).waitForPlaybackToStop())
    }

    @Test
    fun `cooldown distinguishes explicit bypass open gate and normal USB activation`() {
        assertEquals(120L, sinkReconfigurationCooldownMs("usb_enabled", true, true))
        assertEquals(3_800L, sinkReconfigurationCooldownMs("usb_open_gate_retry", true, false))
        assertEquals(2_500L, sinkReconfigurationCooldownMs("usb_enabled", true, false))
        assertEquals(2_500L, sinkReconfigurationCooldownMs("native_device_changed", true, false))
        assertEquals(120L, sinkReconfigurationCooldownMs("ordinary", true, false))
        assertEquals(120L, sinkReconfigurationCooldownMs("usb_enabled", false, false))
    }
}
