package moe.ouom.neriplayer.core.player.usb.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveSessionOpenGateTest {
    @Test
    fun `selected audio attach clears physical detach gate and stale failure`() {
        val gate = UsbExclusiveSessionOpenGate()
        gate.markDeviceDetached()
        assertTrue(gate.block("usb_device_detached", 18_000L, nowMs = 1_000L))
        gate.queueBlock("usb_device_detached", 18_000L)
        assertFalse(gate.handleDeviceAttached(false, true))
        assertFalse(gate.handleDeviceAttached(true, false))
        assertTrue(gate.error(2_000L, 0)?.contains("usb_device_detached") == true)

        assertTrue(gate.handleDeviceAttached(true, true))
        assertNull(gate.error(2_000L, 0))
        assertNull(gate.takePendingBlock())
        assertFalse(gate.handleDeviceAttached(true, true))
        assertFalse(gate.block("native_failure:usb_device_detached", 18_000L, nowMs = 2_000L))
        assertNull(gate.error(2_000L, 0))
    }

    @Test
    fun `physical detach dominates derived failures until explicit disable`() {
        val gate = UsbExclusiveSessionOpenGate()
        gate.markDeviceDetached()
        gate.block("usb_device_detached", 18_000L, nowMs = 1_000L)

        assertFalse(gate.block("native_failure:usb_device_detached", 60_000L, nowMs = 2_000L))
        assertEquals(
            "native_open_deferred:usb_device_detached remainingMs=17000",
            gate.error(2_000L, 0)
        )
        assertTrue(gate.block("usb_exclusive_disabled", 20_000L, nowMs = 2_000L))
        assertEquals(
            "native_open_deferred:usb_exclusive_disabled remainingMs=20000",
            gate.error(2_000L, 0)
        )
    }

    @Test
    fun `queued block keeps longest delay and is consumed once`() {
        val gate = UsbExclusiveSessionOpenGate()
        gate.queueBlock("first", 4_000L)
        gate.queueBlock("shorter", 3_000L)
        gate.queueBlock("longer", 8_000L)

        assertEquals(UsbExclusiveSessionOpenGate.PendingBlock("longer", 8_000L), gate.takePendingBlock())
        assertNull(gate.takePendingBlock())
    }

    @Test
    fun `native close keeps gate and reopen pending until close count reaches zero`() {
        val gate = UsbExclusiveSessionOpenGate()
        gate.block("native_close_in_flight", 750L, nowMs = 1_000L, minimumDelayMs = 750L)
        gate.requestReopenAfterClose("open_player_pcm_reconfigure")

        assertEquals(
            "native_open_deferred:native_close_in_flight count=1",
            gate.error(2_000L, 1)
        )
        assertNull(gate.takeReopenAfterClose(1))
        gate.clearCompletedNativeCloseGate(0)
        assertNull(gate.error(2_000L, 0))
        assertEquals("open_player_pcm_reconfigure", gate.takeReopenAfterClose(0))
        assertNull(gate.takeReopenAfterClose(0))
        assertEquals(
            "usb_exclusive_open_gate_retry_after_close",
            gate.reconfigurationReasonForReopen("open_player_pcm_reconfigure")
        )
        assertEquals(
            "usb_exclusive_reopen_after_close:usb_device_attached",
            gate.reconfigurationReasonForReopen("usb_device_attached")
        )
        val request = UsbExclusiveSessionResources.CloseRequest(
            handle = 7L,
            connection = null,
            source = "player_pcm",
            reason = "open_player_pcm_reconfigure"
        )
        assertTrue(gate.shouldReopenAfterReconfigureClose(request, { true }, { true }))
        assertFalse(gate.shouldReopenAfterReconfigureClose(request, { false }, { true }))
        assertFalse(gate.shouldReopenAfterReconfigureClose(request, { true }, { false }))
        assertFalse(gate.shouldReopenAfterReconfigureClose(
            request.copy(source = "tone"),
            { error("unrelated close must not query player state") },
            { true }
        ))
    }

    @Test
    fun `high risk open failure gets long fuse and recoverable block can clear`() {
        val highRisk = UsbExclusiveSessionOpenGate()
        highRisk.recordNativeOpenFailure("nativeOpen failed", nowMs = 1_000L)
        assertEquals(
            "native_open_deferred:nativeOpen failed remainingMs=18000",
            highRisk.error(1_000L, 0)
        )
        assertFalse(highRisk.clearRecoverableUserActionBlock())

        val transient = UsbExclusiveSessionOpenGate()
        transient.recordNativeOpenFailure("temporary failure", nowMs = 1_000L)
        assertEquals(
            "native_open_deferred:temporary failure remainingMs=5000",
            transient.error(1_000L, 0)
        )
        transient.block("native_failure", 8_000L, nowMs = 1_000L)
        assertTrue(transient.clearRecoverableUserActionBlock())
        assertNull(transient.error(1_000L, 0))
    }

    @Test
    fun `fresh open request is retained only for active player session`() {
        val gate = UsbExclusiveSessionOpenGate()
        gate.requireFreshOpen("output_changed")
        assertEquals("output_changed", gate.freshOpenReason(currentIsPlayerSession = true))
        assertNull(gate.freshOpenReason(currentIsPlayerSession = false))
        gate.requireFreshOpen("new_request")
        gate.markOpened()
        assertNull(gate.freshOpenReason(currentIsPlayerSession = true))
    }

    @Test
    fun `recoverable user action excludes unsupported formats and USB claims`() {
        listOf(
            "sample_rate_unsupported:96000",
            "bit_depth_unsupported:32",
            "channel_count_unsupported:6",
            "native_failure:claim_interface_failed",
            "native_failure:set_alt_failed",
            "native_failure:nativeOpen failed"
        ).forEach { reason ->
            val gate = UsbExclusiveSessionOpenGate()
            gate.block(reason, 8_000L, nowMs = 1_000L)
            assertFalse(reason, gate.clearRecoverableUserActionBlock())
        }
        listOf("release", "failover", "foreground", "stalled", "transport").forEach { reason ->
            val gate = UsbExclusiveSessionOpenGate()
            gate.block(reason, 8_000L, nowMs = 1_000L)
            assertTrue(reason, gate.clearRecoverableUserActionBlock())
        }
    }
}
