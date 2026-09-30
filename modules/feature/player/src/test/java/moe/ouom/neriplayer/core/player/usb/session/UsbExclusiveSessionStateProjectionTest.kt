package moe.ouom.neriplayer.core.player.usb.session

import moe.ouom.neriplayer.core.player.usb.sink.ResolvedUsbOutputFormat
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveSessionStateProjectionTest {
    @Test
    fun `runtime metrics replace reported values and preserve absent counters`() {
        val original = UsbExclusiveNativeState(
            pcmLevelBytes = 1L,
            pcmCapacityBytes = 2L,
            pcmFreeBytes = 3L,
            pcmBackpressureEvents = 4L,
            pcmBackpressureTotalMs = 5L,
            pcmBackpressureCurrentMs = 6L,
            pcmBackpressureMaxMs = 7L,
            playerSignalFrames = 8L,
            playerSilentFrames = 9L,
            playerSignalBytes = 10L,
            playerDroppedBytes = 11L,
            playerUnderrunBytes = 12L,
            playerZeroFillBytes = 13L,
            playerPausedZeroFillBytes = 14L,
            outputPeak = 0.1f,
            lastOutputPeak = 0.2f,
            channel0OutputPeak = 0.3f,
            channel1OutputPeak = 0.4f,
            lastChannel0OutputPeak = 0.5f,
            lastChannel1OutputPeak = 0.6f
        )
        val report = listOf(
            "pcmLevel=21/42", "pcmFreeBytes=21",
            "pcmBackpressureEvents=24", "pcmBackpressureTotalMs=25",
            "pcmBackpressureCurrentMs=26", "pcmBackpressureMaxMs=27",
            "playerSignalFrames=28", "playerSilentFrames=29", "playerSignalBytes=30",
            "playerDroppedBytes=31", "playerUnderrunBytes=32", "playerZeroFillBytes=33",
            "playerPausedZeroFillBytes=34", "outputPeak=0.21", "lastOutputPeak=0.22",
            "channel0OutputPeak=0.23", "channel1OutputPeak=0.24",
            "lastChannel0OutputPeak=0.25", "lastChannel1OutputPeak=0.26"
        ).joinToString(" ")

        val updated = original.withRuntimeReport(report)

        assertEquals(report, updated.runtimeReport)
        assertEquals(21L, updated.pcmLevelBytes)
        assertEquals(42L, updated.pcmCapacityBytes)
        assertEquals(27L, updated.pcmBackpressureMaxMs)
        assertEquals(28L, updated.playerSignalFrames)
        assertEquals(34L, updated.playerPausedZeroFillBytes)
        assertEquals(0.21f, updated.outputPeak, 0.0001f)
        assertEquals(0.26f, updated.lastChannel1OutputPeak, 0.0001f)
        assertEquals(original, original.withRuntimeReport("idle").copy(runtimeReport = "idle"))
    }

    @Test
    fun `live free bytes stay within known capacity and update queue level`() {
        val state = UsbExclusiveNativeState(pcmCapacityBytes = 100L, pcmFreeBytes = 10L)

        assertEquals(state, state.withLivePlayerPcmFreeBytes(null))
        assertEquals(100L, state.withLivePlayerPcmFreeBytes(120L).pcmFreeBytes)
        assertEquals(0L, state.withLivePlayerPcmFreeBytes(-1L).pcmFreeBytes)
        assertEquals(60L, state.withLivePlayerPcmFreeBytes(40L).pcmLevelBytes)
        assertEquals(40L, UsbExclusiveNativeState().withLivePlayerPcmFreeBytes(40L).pcmFreeBytes)
    }

    @Test
    fun `only active player handle matches and transient idle errors are discarded`() {
        val state = UsbExclusiveNativeState(handle = 7L, source = "player_pcm", opened = true)
        assertTrue(state.matchesPlayerSession(7L))
        assertFalse(state.matchesPlayerSession(8L))
        assertFalse(state.copy(opened = false).matchesPlayerSession(7L))
        assertFalse(state.copy(source = "tone").matchesPlayerSession(7L))
        listOf(
            null, "none", "idle", "native_idle usbHostDevices=1", "native_open_deferred:x",
            "native_reopen_cooling_down", "native_refresh_deferred", "native_transition_in_flight",
            "stop_deferred", "stop_applied", "usb_exclusive_disabled"
        ).forEach { assertFalse(it.isPersistentIdleNativeError()) }
        assertTrue("claim_interface_failed".isPersistentIdleNativeError())
        assertTrue(UsbExclusiveNativeState(lastError = "native_open_deferred:close").isIdleOpenDeferred())
        assertTrue(UsbExclusiveNativeState(runtimeReport = "native_open_deferred:close").isIdleOpenDeferred())
        assertFalse(UsbExclusiveNativeState(handle = 7L, lastError = "native_open_deferred").isIdleOpenDeferred())
        assertFalse(UsbExclusiveNativeState(runtimeReport = "native_idle").isIdleOpenDeferred())
        assertTrue(UsbExclusiveNativeState(lastError = "usb_device_detached").isDetachedIdleState())
        assertTrue(UsbExclusiveNativeState(runtimeReport = "native_open_deferred:usb_device_detached").isDetachedIdleState())
        assertFalse(UsbExclusiveNativeState(handle = 7L, lastError = "usb_device_detached").isDetachedIdleState())
        assertFalse(UsbExclusiveNativeState(runtimeReport = "native_idle").isDetachedIdleState())
        assertTrue(UsbExclusiveNativeState(handle = 0L, source = "player_pcm").canPublishIdleOpenGateError())
        assertTrue(UsbExclusiveNativeState(handle = 7L, source = "idle").canPublishIdleOpenGateError())
        assertFalse(UsbExclusiveNativeState(handle = 7L, source = "player_pcm").canPublishIdleOpenGateError())
        assertEquals("native_idle", attachedIdleRuntimeReport(0))
        assertEquals("native_open_deferred:native_close_in_flight count=2", attachedIdleRuntimeReport(2))
        assertTrue(UsbExclusiveNativeState(runtimeReport = "native_open_deferred:native_close_in_flight").isWaitingForNativeCloseGate())
        assertTrue(UsbExclusiveNativeState(lastError = "native_close_in_flight").isWaitingForNativeCloseGate())
        assertFalse(UsbExclusiveNativeState(handle = 7L, lastError = "native_close_in_flight").isWaitingForNativeCloseGate())
        assertFalse(UsbExclusiveNativeState(runtimeReport = "native_idle").isWaitingForNativeCloseGate())
    }

    @Test
    fun `stop and output transitions project all session fields`() {
        val output = ResolvedUsbOutputFormat(
            sampleRate = 96_000,
            channelCount = 2,
            bitDepth = 24,
            subslotBytes = 4,
            bufferDurationMs = 100,
            description = "rate=96000 channels=2 bits=24 subslot=4"
        )
        val current = UsbExclusiveNativeState(
            opened = true,
            streaming = true,
            paused = true,
            source = "player_pcm",
            handle = 7L,
            inputFormat = "old_input",
            outputFormat = "old_output",
            completedAudioFrames = 99L
        )

        val reconfigured = current.withReconfiguredOutput(output, output, "new_input", "running=false")
        assertEquals(7L, reconfigured.handle)
        assertEquals(output.description, reconfigured.outputFormat)
        assertEquals("new_input", reconfigured.inputFormat)
        assertEquals(0L, reconfigured.completedAudioFrames)

        val failedRearm = reconfigured.withRearmedOutput(
            output,
            "rearmed_input",
            UsbExclusiveSessionResources.RearmResult(
                reconfigured = true,
                bufferConfigured = true,
                transferWindowConfigured = true,
                prepared = false,
                report = "prepare_failed",
                completedFrames = 12L,
                queuedFrames = 13L
            )
        )
        assertEquals("prepare_failed", failedRearm.lastError)
        assertEquals(13L, failedRearm.queuedAudioFrames)
        assertEquals("rearmed_input", failedRearm.inputFormat)
        assertEquals(null, failedRearm.withRearmedOutput(
            output,
            "rearmed_input",
            UsbExclusiveSessionResources.RearmResult(true, true, true, true, "running=false", 0L, 0L)
        ).lastError)

        val stopped = failedRearm.afterNativeStop("deviceOnline=false")
        assertEquals(0L, stopped.handle)
        assertEquals("idle", stopped.source)
        assertEquals("deviceOnline=false", stopped.runtimeReport)
        assertEquals("deviceOnline=false", stopped.lastError)
        assertEquals("idle", failedRearm.afterNativeStop(null).runtimeReport)
    }
}
