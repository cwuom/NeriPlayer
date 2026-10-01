package moe.ouom.neriplayer.core.player.usb.session

import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveSessionReusePolicyTest {
    private val healthy = UsbExclusiveNativeState(
        opened = true,
        source = "player_pcm",
        handle = 7L,
        outputFormat = "rate=96000 channels=2 bits=24 subslot=4",
        runtimeReport = "deviceOnline=true running=false transportFailed=false lastError=none",
        lastError = null
    )

    @Test
    fun `reuse rejects stale identity output and native errors`() {
        assertTrue(UsbExclusiveSessionReusePolicy.canReuseSession(healthy))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseSession(healthy.copy(handle = 0L)))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseSession(healthy.copy(source = "tone")))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseSession(healthy.copy(opened = false)))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseSession(healthy.copy(outputFormat = "none")))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseSession(healthy.copy(outputFormat = "")))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseSession(
            healthy.copy(runtimeReport = "deviceOnline=false lastError=none")
        ))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseSession(healthy.copy(lastError = "failed")))
        assertTrue(UsbExclusiveSessionReusePolicy.canReuseSession(healthy.copy(lastError = "none")))
        assertTrue(UsbExclusiveSessionReusePolicy.hasPlayerHandle(healthy))
        assertFalse(UsbExclusiveSessionReusePolicy.hasPlayerHandle(healthy.copy(handle = 0L)))
        assertFalse(UsbExclusiveSessionReusePolicy.hasPlayerHandle(healthy.copy(source = "tone")))
        assertFalse(UsbExclusiveSessionReusePolicy.canReconfigureInPlace(
            healthy.copy(runtimeReport = "deviceOnline=true running=true lastError=none")
        ))
        assertTrue(UsbExclusiveSessionReusePolicy.canReconfigureInPlace(healthy))
    }

    @Test
    fun `healthy session requires live gate and clean transport`() {
        assertTrue(UsbExclusiveSessionReusePolicy.hasHealthySession(healthy, true))
        assertFalse(UsbExclusiveSessionReusePolicy.hasHealthySession(healthy, false))
        assertFalse(UsbExclusiveSessionReusePolicy.hasHealthySession(healthy.copy(transitioning = true), true))
        assertFalse(UsbExclusiveSessionReusePolicy.hasHealthySession(healthy.copy(lastError = "failed"), true))
        assertFalse(UsbExclusiveSessionReusePolicy.hasHealthySession(
            healthy.copy(runtimeReport = "deviceOnline=false lastError=none"), true
        ))
        assertFalse(UsbExclusiveSessionReusePolicy.hasHealthySession(
            healthy.copy(runtimeReport = "transportFailed=true lastError=none"), true
        ))
        assertTrue(UsbExclusiveSessionReusePolicy.canRearmPlayerSession(healthy, 7L))
        assertFalse(UsbExclusiveSessionReusePolicy.canRearmPlayerSession(healthy, 8L))
        assertFalse(UsbExclusiveSessionReusePolicy.canRearmPlayerSession(
            healthy.copy(transitioning = true), 7L
        ))
        assertFalse(UsbExclusiveSessionReusePolicy.canRearmPlayerSession(
            healthy.copy(runtimeReport = "running=true lastError=none"), 7L
        ))
    }

    @Test
    fun `output equivalence requires known formats and selected candidate`() {
        val output = healthy.outputFormat
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseOutput("", output))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseOutput("none", output))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseOutput(output, "none"))
        assertTrue(UsbExclusiveSessionReusePolicy.canReuseOutput(output, output))
        assertFalse(UsbExclusiveSessionReusePolicy.canReuseResolvedOutput(
            output, output, output, emptySet()
        ))
        assertTrue(UsbExclusiveSessionReusePolicy.canReuseResolvedOutput(
            output, output, output, setOf(output)
        ))
        assertFalse(UsbExclusiveSessionReusePolicy.shouldRetryAlternativeReconfigure(""))
    }
}
