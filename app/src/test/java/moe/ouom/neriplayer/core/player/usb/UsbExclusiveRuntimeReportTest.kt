package moe.ouom.neriplayer.core.player.usb

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
    }
}
