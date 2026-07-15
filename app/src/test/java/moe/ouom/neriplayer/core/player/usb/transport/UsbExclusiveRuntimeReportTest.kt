package moe.ouom.neriplayer.core.player.usb.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveRuntimeReportTest {

    @Test
    fun `runtime metrics use explicit free bytes for queue full detection`() {
        val metrics = buildString {
            append("source=player_pcm sampleRate=48000 channels=2 subslotBytes=2 ")
            append("transferBytes=3072 lastTransferBytes=3072 ")
            append("pcmLevel=287000/288000 pcmFreeBytes=0 ")
            append("pcmBackpressureEvents=3 pcmBackpressureCurrentMs=120 ")
            append("pcmBackpressureMaxMs=240 running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertTrue(metrics.isQueueFull)
        assertTrue(metrics.isBenignBackpressure)
        assertEquals(0L, metrics.pcmFreeBytes)
        assertEquals(3L, metrics.pcmBackpressureEvents)
        assertEquals(120L, metrics.pcmBackpressureCurrentMs)
        assertEquals(240L, metrics.pcmBackpressureMaxMs)
        assertEquals(48000, metrics.sampleRate)
        assertEquals(2, metrics.channelCount)
        assertEquals(2, metrics.subslotBytes)
        assertEquals(4, metrics.outputFrameBytes)
        assertEquals(3072L, metrics.transferBytes)
        assertEquals(3072L, metrics.lastTransferBytes)
    }

    @Test
    fun `runtime metrics fall back to pcm level for old reports`() {
        val metrics = buildString {
            append("source=player_pcm pcmLevel=288000/288000 ")
            append("running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertTrue(metrics.isQueueFull)
        assertTrue(metrics.isBenignBackpressure)
    }

    @Test
    fun `transport failure is never treated as benign backpressure`() {
        val metrics = buildString {
            append("source=player_pcm pcmLevel=288000/288000 pcmFreeBytes=0 ")
            append("running=true transportFailed=true lastError=transfer_status=5")
        }.usbRuntimeMetrics()

        assertTrue(metrics.isQueueFull)
        assertFalse(metrics.isBenignBackpressure)
        assertEquals(UsbExclusiveErrorCode.TransportFailed, metrics.errorCode)
    }

    @Test
    fun `runtime metrics expose recoverable iso packet errors`() {
        val metrics = buildString {
            append("source=player_pcm isoPacketErrors=2 isoPacketErrorTransfers=1 ")
            append("isoPacketErrorScore=2 running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertEquals(2L, metrics.isoPacketErrors)
        assertEquals(1L, metrics.isoPacketErrorTransfers)
        assertEquals(2, metrics.isoPacketErrorScore)
        assertTrue(metrics.hasHealthyTransport)
        assertTrue(metrics.hasPlayerPcmAudioQualityDegradation)
    }

    @Test
    fun `runtime metrics expose player quality counters`() {
        val metrics = buildString {
            append("source=player_pcm running=true paused=false ")
            append("playerDroppedBytes=384 playerUnderrunBytes=768 ")
            append("playerZeroFillBytes=768 playerPausedZeroFillBytes=1536 lastError=none")
        }.usbRuntimeMetrics()

        assertEquals(384L, metrics.playerDroppedBytes)
        assertEquals(768L, metrics.playerUnderrunBytes)
        assertEquals(768L, metrics.playerZeroFillBytes)
        assertEquals(1536L, metrics.playerPausedZeroFillBytes)
        assertTrue(metrics.hasPlayerPcmAudioQualityDegradation)
        assertTrue(metrics.hasPlayerPcmBufferStarvationCounters)
    }

    @Test
    fun `active zero fill counters need runtime policy before declaring degradation`() {
        val metrics = buildString {
            append("source=player_pcm running=true paused=false ")
            append("playerDroppedBytes=0 playerUnderrunBytes=768 ")
            append("playerZeroFillBytes=768 lastError=none")
        }.usbRuntimeMetrics()

        assertFalse(metrics.hasPlayerPcmAudioQualityDegradation)
        assertTrue(metrics.hasPlayerPcmBufferStarvationCounters)
    }

    @Test
    fun `paused zero fill does not mark active audio as degraded`() {
        val metrics = buildString {
            append("source=player_pcm running=true paused=true ")
            append("playerPausedZeroFillBytes=4096 playerZeroFillBytes=0 lastError=none")
        }.usbRuntimeMetrics()

        assertFalse(metrics.hasPlayerPcmAudioQualityDegradation)
        assertFalse(metrics.hasPlayerPcmBufferStarvationCounters)
    }

    @Test
    fun `runtime metrics classify feedback scheduler gaps`() {
        val metrics = "lastError=async_feedback_scheduler_unavailable".usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.AsyncFeedbackUnsupported, metrics.errorCode)
        assertFalse(metrics.hasHealthyTransport)
    }

    @Test
    fun `runtime metrics classify first completion timeout`() {
        val metrics = buildString {
            append("source=player_pcm running=false transportFailed=true ")
            append("lastError=event_loop_first_completion_timeout")
        }.usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.TransferFirstCompletionTimeout, metrics.errorCode)
        assertTrue(metrics.errorCode.requiresFreshNativeOpen)
    }

    @Test
    fun `runtime metrics classify native open deferred as non fatal gate`() {
        val metrics = "native_open_deferred:native_close_in_flight count=1".usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.OpenDeferred, metrics.errorCode)
        assertFalse(metrics.errorCode.requiresFreshNativeOpen)
    }

    @Test
    fun `runtime metrics expose structured native fields`() {
        val metrics = buildString {
            append("source=player_pcm uacVersion=UAC2 syncType=adaptive feedback=none ")
            append("sampleRate=96000 channels=2 subslotBytes=3 ")
            append("completedTransfers=42 inFlight=8 deviceOnline=true paused=false ")
            append("running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertEquals("player_pcm", metrics.source)
        assertEquals("UAC2", metrics.uacVersion)
        assertEquals("adaptive", metrics.syncType)
        assertEquals("none", metrics.feedback)
        assertEquals(42L, metrics.completedTransfers)
        assertEquals(8, metrics.inFlightTransfers)
        assertEquals(true, metrics.deviceOnline)
        assertEquals(false, metrics.paused)
        assertEquals(UsbExclusiveErrorCode.None, metrics.errorCode)
        assertTrue(metrics.hasHealthyTransport)
    }

    @Test
    fun `device offline report is classified as detached`() {
        val metrics = buildString {
            append("source=player_pcm deviceOnline=false running=false ")
            append("transportFailed=true lastError=LIBUSB_ERROR_NO_DEVICE")
        }.usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.DeviceDetached, metrics.errorCode)
        assertFalse(metrics.hasHealthyTransport)
        assertTrue(metrics.errorCode.requiresFreshNativeOpen)
    }

    @Test
    fun `transport failed flag is classified even when last error is none`() {
        val metrics = "source=player_pcm transportFailed=true running=true lastError=none"
            .usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.TransportFailed, metrics.errorCode)
        assertFalse(metrics.hasHealthyTransport)
    }

    @Test
    fun `live free bytes replace stale queue level for write planning`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            pcmLevelBytes = 149_504L,
            pcmCapacityBytes = 192_000L,
            pcmFreeBytes = 42_496L
        ).withLivePcmFreeBytes(100_000L)

        assertEquals(92_000L, metrics.pcmLevelBytes)
        assertEquals(100_000L, metrics.pcmFreeBytes)
    }

    @Test
    fun `live free bytes are clamped to reported capacity`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            pcmLevelBytes = 0L,
            pcmCapacityBytes = 192_000L,
            pcmFreeBytes = 192_000L
        ).withLivePcmFreeBytes(250_000L)

        assertEquals(0L, metrics.pcmLevelBytes)
        assertEquals(192_000L, metrics.pcmFreeBytes)
    }
}
