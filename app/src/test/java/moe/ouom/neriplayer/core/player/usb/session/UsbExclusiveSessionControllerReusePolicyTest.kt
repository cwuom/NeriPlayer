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
    fun `same verified fallback can be reused for the same requested format`() {
        val fallback = "rate=48000 channels=2 bits=16 subslot=2"
        val requested = "rate=96000 channels=2 bits=32 subslot=4"

        assertTrue(
            UsbExclusiveSessionController.canReuseResolvedPlayerPcmOutput(
                currentOutputFormat = fallback,
                currentRequestedOutputFormat = requested,
                preferredOutputFormat = requested,
                candidateDescriptions = setOf(requested, fallback)
            )
        )
    }

    @Test
    fun `fallback from an old request cannot mask a new exact format`() {
        val fallback = "rate=48000 channels=2 bits=16 subslot=2"
        val oldRequest = "rate=96000 channels=2 bits=32 subslot=4"
        val newRequest = "rate=44100 channels=2 bits=32 subslot=4"

        assertFalse(
            UsbExclusiveSessionController.canReuseResolvedPlayerPcmOutput(
                currentOutputFormat = fallback,
                currentRequestedOutputFormat = oldRequest,
                preferredOutputFormat = newRequest,
                candidateDescriptions = setOf(newRequest, fallback)
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
    fun `v2 locked feedback session can reconfigure in place`() {
        assertTrue(
            UsbExclusiveSessionController.canReconfigurePlayerPcmOutputInPlace(
                UsbExclusiveNativeState(
                    opened = true,
                    source = "player_pcm",
                    handle = 7L,
                    outputFormat = "rate=96000 channels=2 bits=32 subslot=4",
                    runtimeReport = v2RuntimeReport(
                        feedbackState = "Locked",
                        feedbackReady = true,
                        realPcmReleased = true,
                        playbackReady = false,
                        feedbackReusable = true,
                        canAcceptPcm = true
                    ),
                    lastError = null
                )
            )
        )
    }

    @Test
    fun `v2 acquiring or terminal action session cannot reconfigure in place`() {
        assertFalse(
            UsbExclusiveSessionController.canReconfigurePlayerPcmOutputInPlace(
                UsbExclusiveNativeState(
                    opened = true,
                    source = "player_pcm",
                    handle = 7L,
                    outputFormat = "rate=96000 channels=2 bits=32 subslot=4",
                    runtimeReport = v2RuntimeReport(
                        feedbackState = "Acquiring",
                        feedbackReady = false,
                        realPcmReleased = false,
                        playbackReady = false,
                        feedbackReusable = false,
                        canAcceptPcm = false
                    ),
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
                    runtimeReport = v2RuntimeReport(
                        recommendedAction = "STOP_PRESERVE_INTENT",
                        actionOwner = "kotlin",
                        actionLatched = true,
                        terminalFailure = true,
                        canAcceptPcm = false,
                        playbackReady = false
                    ),
                    lastError = null
                )
            )
        )
    }

    @Test
    fun `same format reuse requires a reusable native runtime`() {
        val acquiring = UsbExclusiveNativeState(
            opened = true,
            source = "player_pcm",
            handle = 7L,
            outputFormat = "rate=96000 channels=2 bits=32 subslot=4",
            runtimeReport = v2RuntimeReport(
                feedbackState = "Acquiring",
                feedbackReady = false,
                realPcmReleased = false,
                playbackReady = false,
                feedbackReusable = false,
                canAcceptPcm = false
            ),
            lastError = null
        )
        val locked = acquiring.copy(
            runtimeReport = v2RuntimeReport(
                feedbackState = "Locked",
                feedbackReady = true,
                realPcmReleased = true,
                playbackReady = false,
                feedbackReusable = true,
                canAcceptPcm = true
            )
        )

        assertFalse(UsbExclusiveSessionController.canReusePlayerPcmSession(acquiring))
        assertTrue(UsbExclusiveSessionController.canReusePlayerPcmSession(locked))
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

    private fun v2RuntimeReport(
        feedbackState: String = "Locked",
        feedbackReady: Boolean = true,
        realPcmReleased: Boolean = true,
        playbackReady: Boolean = false,
        feedbackReusable: Boolean = true,
        canAcceptPcm: Boolean = true,
        recommendedAction: String = "NONE",
        actionOwner: String = if (recommendedAction == "NONE") "none" else "native",
        actionLatched: Boolean = false,
        terminalFailure: Boolean = false,
        actionId: Long = if (recommendedAction == "NONE") 0L else 7L
    ): String {
        return buildString {
            append("reportVersion=2 source=player_pcm syncType=async running=false ")
            append("deviceOnline=true transportFailed=false feedbackMode=explicit ")
            append("feedbackEndpoint=0x84 feedbackState=$feedbackState ")
            append("transportRunning=false feedbackReady=$feedbackReady ")
            append("realPcmReleased=$realPcmReleased canAcceptPcm=$canAcceptPcm ")
            append("playbackReady=$playbackReady feedbackReusable=$feedbackReusable ")
            append("terminalFailure=$terminalFailure nativeStreamGeneration=9 ")
            append("candidateId=cs43131 recoveryEpoch=4 ")
            append("recommendedAction=$recommendedAction actionId=$actionId ")
            append("actionGeneration=9 actionOwner=$actionOwner ")
            append("actionLatched=$actionLatched lastError=none")
        }
    }
}
