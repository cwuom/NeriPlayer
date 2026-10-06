package moe.ouom.neriplayer.core.player.usb.transport

import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveFeedbackClockFailure
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveFeedbackMode
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveRuntimeReportFieldTest {
    private val v2ExplicitReport = listOf(
        "reportVersion=2", "source=player_pcm", "syncType=async",
        "feedbackMode=explicit", "feedbackEndpoint=0x84", "feedbackState=Locked",
        "transportRunning=true", "feedbackReady=true", "realPcmReleased=true",
        "canAcceptPcm=true", "playbackReady=true", "feedbackReusable=true",
        "terminalFailure=false", "nativeStreamGeneration=9", "candidateId=cs43131",
        "recoveryEpoch=4", "recommendedAction=Holdover", "actionId=7",
        "actionGeneration=9", "actionOwner=native", "actionLatched=false", "lastError=none"
    )

    @Test
    fun `typed fields fail closed on malformed, negative or non-finite values`() {
        listOf(
            "sampleRate=abc" to "invalid_sampleRate",
            "channels=-2" to "invalid_channels",
            "transferBytes=lots" to "invalid_transferBytes",
            "completedTransfers=-1" to "invalid_completedTransfers",
            "feedbackRateHz=fast" to "invalid_feedbackRateHz",
            "feedbackRateHz=NaN" to "invalid_feedbackRateHz",
            "feedbackRateHz=Infinity" to "invalid_feedbackRateHz",
            "outputPeak=loud" to "invalid_outputPeak",
            "outputPeak=Infinity" to "invalid_outputPeak",
            "outputPeak=-0.5" to "invalid_outputPeak",
            "feedbackMode=bogus" to "invalid_feedbackMode"
        ).forEach { (field, reason) ->
            val metrics = "source=player_pcm $field lastError=none".usbRuntimeMetrics()

            assertFalse(field, metrics.reportValid)
            assertEquals(field, reason, metrics.reportInvalidReason)
        }
    }

    @Test
    fun `typed fields parse valid values and default when absent`() {
        val metrics = buildString {
            append("source=player_pcm sampleRate=44100 transferBytes=4096 feedbackRateHz=48000.25 ")
            append("outputPeak=0.5 feedbackMode=implicit lastError=none")
        }.usbRuntimeMetrics()
        val defaults = "source=player_pcm lastError=none".usbRuntimeMetrics()

        assertTrue(metrics.reportValid)
        assertEquals(44100, metrics.sampleRate)
        assertEquals(4096L, metrics.transferBytes)
        assertEquals(48000.25, metrics.feedbackRateHz!!, 0.0)
        assertEquals(0.5f, metrics.outputPeak!!, 0.0f)
        assertEquals(UsbExclusiveFeedbackMode.Implicit, metrics.feedbackMode)
        assertTrue(defaults.reportValid)
        assertNull(defaults.sampleRate)
        assertNull(defaults.transferBytes)
        assertNull(defaults.feedbackRateHz)
        assertNull(defaults.outputPeak)
        assertEquals(UsbExclusiveFeedbackMode.Disabled, defaults.feedbackMode)
    }

    @Test
    fun `v2 reports fail closed when a required field is missing`() {
        assertTrue(v2ExplicitReport.joinToString(" ").usbRuntimeMetrics().reportValid)

        listOf("actionId", "feedbackMode").forEach { key ->
            val metrics = v2ExplicitReport.filterNot { it.startsWith("$key=") }
                .joinToString(" ")
                .usbRuntimeMetrics()

            assertFalse(key, metrics.reportValid)
            assertEquals(key, "missing_$key", metrics.reportInvalidReason)
        }
    }

    @Test
    fun `feedback endpoints accept decimal or hex byte addresses`() {
        fun endpoint(raw: String) = "source=player_pcm feedbackEndpoint=$raw lastError=none"
            .usbRuntimeMetrics()
            .feedbackEndpointAddress

        assertEquals(0x84, endpoint("0X84"))
        assertEquals(129, endpoint("129"))
        assertNull(endpoint("0x100"))
        assertNull(endpoint("0"))
        assertNull(endpoint("0xZZ"))
        assertNull(endpoint("ep1"))
    }

    @Test
    fun `feedback clock failures accept loosely formatted names`() {
        fun clockFailure(raw: String) = "source=player_pcm feedbackClockFailure=$raw lastError=none"
            .usbRuntimeMetrics()
            .feedbackClockFailure

        assertEquals(UsbExclusiveFeedbackClockFailure.None, clockFailure("none"))
        assertEquals(UsbExclusiveFeedbackClockFailure.AcquireTimeout, clockFailure("acquire_timeout"))
        assertEquals(UsbExclusiveFeedbackClockFailure.HoldoverTimeout, clockFailure("Holdover-Timeout"))
        assertEquals(UsbExclusiveFeedbackClockFailure.NonMonotonicTime, clockFailure("NON_MONOTONIC_TIME"))
        assertEquals(UsbExclusiveFeedbackClockFailure.None, clockFailure("drift"))
    }

    @Test
    fun `live free bytes are clamped to the reported queue capacity`() {
        val metrics = UsbExclusiveRuntimeMetrics(pcmLevelBytes = 10L, pcmCapacityBytes = 1_000L, pcmFreeBytes = 990L)

        assertEquals(0L to 1_000L, metrics.withLivePcmFreeBytes(1_500L).levelAndFree())
        assertEquals(1_000L to 0L, metrics.withLivePcmFreeBytes(-5L).levelAndFree())
        assertEquals(600L to 400L, metrics.withLivePcmFreeBytes(400L).levelAndFree())
        assertEquals(10L to 0L, metrics.copy(pcmCapacityBytes = null).withLivePcmFreeBytes(-5L).levelAndFree())
        assertEquals(10L to 50L, metrics.copy(pcmCapacityBytes = 0L).withLivePcmFreeBytes(50L).levelAndFree())
    }

    @Test
    fun `queue is full only with a real capacity and no free space`() {
        fun full(free: Long?, level: Long?, capacity: Long?) = UsbExclusiveRuntimeMetrics(
            pcmFreeBytes = free,
            pcmLevelBytes = level,
            pcmCapacityBytes = capacity
        ).isQueueFull

        assertTrue(full(free = 0L, level = null, capacity = 100L))
        assertFalse(full(free = 0L, level = null, capacity = null))
        assertFalse(full(free = 0L, level = null, capacity = 0L))
        assertFalse(full(free = 5L, level = null, capacity = 100L))
        assertFalse(full(free = null, level = null, capacity = 100L))
        assertFalse(full(free = null, level = 50L, capacity = null))
        assertTrue(full(free = null, level = 100L, capacity = 100L))
        assertFalse(full(free = null, level = 50L, capacity = 100L))
        assertFalse(full(free = null, level = 50L, capacity = 0L))
    }

    @Test
    fun `buffer starvation counters only count for running player pcm`() {
        val running = UsbExclusiveRuntimeMetrics(source = "player_pcm", running = true, playerUnderrunBytes = 10L)

        assertTrue(running.hasPlayerPcmBufferStarvationCounters)
        assertTrue(running.copy(playerUnderrunBytes = null, playerZeroFillBytes = 5L).hasPlayerPcmBufferStarvationCounters)
        assertFalse(running.copy(playerUnderrunBytes = 0L, playerZeroFillBytes = null).hasPlayerPcmBufferStarvationCounters)
        assertFalse(running.copy(playerUnderrunBytes = 0L, playerZeroFillBytes = 0L).hasPlayerPcmBufferStarvationCounters)
        assertFalse(running.copy(source = "file").hasPlayerPcmBufferStarvationCounters)
        assertFalse(running.copy(running = null).hasPlayerPcmBufferStarvationCounters)
        assertFalse(running.copy(paused = true).hasPlayerPcmBufferStarvationCounters)
    }

    @Test
    fun `legacy reports with asynchronous feedback cannot reuse the native session`() {
        val legacy = UsbExclusiveRuntimeMetrics(reportVersion = 1, syncType = "adaptive")

        assertTrue(legacy.canReuseNativePlayerSession)
        assertTrue(legacy.copy(feedback = "NONE").canReuseNativePlayerSession)
        assertTrue(legacy.copy(feedback = "disabled").canReuseNativePlayerSession)
        assertFalse(legacy.copy(feedback = "explicit").canReuseNativePlayerSession)
        assertFalse(legacy.copy(syncType = "Asynchronous").canReuseNativePlayerSession)
        assertFalse(legacy.copy(syncType = "async").canReuseNativePlayerSession)
    }

    private fun UsbExclusiveRuntimeMetrics.levelAndFree() = pcmLevelBytes to pcmFreeBytes
}
