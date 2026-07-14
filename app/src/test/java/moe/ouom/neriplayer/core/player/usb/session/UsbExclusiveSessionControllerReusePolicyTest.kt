package moe.ouom.neriplayer.core.player.usb.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState

class UsbExclusiveSessionControllerReusePolicyTest {

    @Test
    fun `exact output match can reuse current native handle`() {
        assertTrue(
            UsbExclusiveSessionController.canReusePlayerPcmOutput(
                currentOutputFormat = "rate=96000 channels=2 bits=24 subslot=4",
                preferredOutputFormat = "rate=96000 channels=2 bits=24 subslot=4"
            )
        )
    }

    @Test
    fun `lower precision fallback is not reused for the same higher precision request`() {
        assertFalse(
            UsbExclusiveSessionController.canReusePlayerPcmOutput(
                currentOutputFormat = "rate=96000 channels=2 bits=24 subslot=4",
                preferredOutputFormat = "rate=96000 channels=2 bits=32 subslot=4"
            )
        )
    }

    @Test
    fun `24 bit usb container changes force a fresh native reopen path`() {
        assertFalse(
            UsbExclusiveSessionController.canReusePlayerPcmOutput(
                currentOutputFormat = "rate=96000 channels=2 bits=24 subslot=3",
                preferredOutputFormat = "rate=96000 channels=2 bits=24 subslot=4"
            )
        )
    }

    @Test
    fun `old low resolution session is not reused after preferred output upgrades`() {
        assertFalse(
            UsbExclusiveSessionController.canReusePlayerPcmOutput(
                currentOutputFormat = "rate=96000 channels=2 bits=16 subslot=2",
                preferredOutputFormat = "rate=96000 channels=2 bits=32 subslot=4"
            )
        )
    }

    @Test
    fun `idle healthy player session can reconfigure output in place`() {
        assertTrue(
            UsbExclusiveSessionController.canReconfigurePlayerPcmOutputInPlace(
                UsbExclusiveNativeState(
                    opened = true,
                    source = "player_pcm",
                    handle = 7L,
                    outputFormat = "rate=96000 channels=2 bits=32 subslot=4",
                    runtimeReport = "deviceOnline=true running=false transportFailed=false",
                    lastError = null
                )
            )
        )
    }

    @Test
    fun `running or failed player session cannot reconfigure output in place`() {
        assertFalse(
            UsbExclusiveSessionController.canReconfigurePlayerPcmOutputInPlace(
                UsbExclusiveNativeState(
                    opened = true,
                    source = "player_pcm",
                    handle = 7L,
                    outputFormat = "rate=96000 channels=2 bits=32 subslot=4",
                    runtimeReport = "deviceOnline=true running=true transportFailed=false",
                    lastError = null
                )
            )
        )
        assertFalse(
            UsbExclusiveSessionController.canReconfigurePlayerPcmOutputInPlace(
                UsbExclusiveNativeState(
                    opened = true,
                    source = "player_pcm",
                    handle = 7L,
                    outputFormat = "rate=96000 channels=2 bits=32 subslot=4",
                    runtimeReport = "deviceOnline=true running=false transportFailed=true",
                    lastError = null
                )
            )
        )
    }

    @Test
    fun `candidate level reconfigure retry only keeps retryable native reasons`() {
        assertTrue(
            UsbExclusiveSessionController.shouldRetryAlternativePlayerPcmReconfigure(
                "reconfigure_no_compatible_output:alt_missing"
            )
        )
        assertTrue(
            UsbExclusiveSessionController.shouldRetryAlternativePlayerPcmReconfigure(
                "reconfigure_sample_rate_failed:set_cur_failed"
            )
        )
        assertTrue(
            UsbExclusiveSessionController.shouldRetryAlternativePlayerPcmReconfigure(
                "reconfigure_requires_reopen:claim_or_interface_changed"
            )
        )
        assertFalse(
            UsbExclusiveSessionController.shouldRetryAlternativePlayerPcmReconfigure(
                "reconfigure_invalid_handle"
            )
        )
        assertFalse(
            UsbExclusiveSessionController.shouldRetryAlternativePlayerPcmReconfigure(
                "reconfigure_requires_idle_handle"
            )
        )
    }
}
