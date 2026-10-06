package moe.ouom.neriplayer.core.player.usb.sink

import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbExclusivePcmWritePlannerBoundaryTest {

    @Test
    fun `degenerate input sizes bypass write planning`() {
        assertEquals(0, writeSize(remainingBytes = 0))
        assertEquals(1_001, writeSize(remainingBytes = 1_001, inputFrameBytes = 0))
        assertEquals(0, writeSize(remainingBytes = 3))
        assertEquals(6_000, writeSize(remainingBytes = 6_002))
    }

    @Test
    fun `preroll only bounds playing writes before transport starts`() {
        assertEquals(1_920, writeSize(nativeTransportStarted = false, playing = true, prerollMs = 10L))
        assertEquals(12_288, writeSize(nativeTransportStarted = true, playing = true, prerollMs = 10L))
        assertEquals(12_288, writeSize(nativeTransportStarted = false, playing = false, prerollMs = 10L))
        assertEquals(
            12_288,
            writeSize(nativeTransportStarted = false, playing = true, prerollMs = 10L, inputSampleRate = 0)
        )
    }

    @Test
    fun `free bytes are derived from capacity and level when not reported`() {
        assertEquals(4_000, writeSize(metrics = UsbExclusiveRuntimeMetrics(pcmCapacityBytes = 10_000L, pcmLevelBytes = 6_000L)))
        assertEquals(0, writeSize(metrics = UsbExclusiveRuntimeMetrics(pcmCapacityBytes = 10_000L, pcmLevelBytes = 12_000L)))
        assertEquals(12_288, writeSize(metrics = UsbExclusiveRuntimeMetrics(pcmCapacityBytes = 10_000L)))
        assertEquals(12_288, writeSize(metrics = UsbExclusiveRuntimeMetrics(pcmCapacityBytes = 0L, pcmLevelBytes = 0L)))
    }

    @Test
    fun `running queue headroom falls back to free bytes without a usable capacity or level`() {
        val freeOnly = UsbExclusiveRuntimeMetrics(pcmFreeBytes = 4_000L)

        assertEquals(4_000, writeSize(nativeTransportStarted = true, metrics = freeOnly))
        assertEquals(4_000, writeSize(nativeTransportStarted = true, metrics = freeOnly.copy(pcmCapacityBytes = 0L)))
        assertEquals(4_000, writeSize(nativeTransportStarted = true, metrics = freeOnly.copy(pcmCapacityBytes = 100_000L)))
    }

    @Test
    fun `running queue headroom stops at the capacity based waterline`() {
        val belowWaterline = UsbExclusiveRuntimeMetrics(
            pcmCapacityBytes = 100_000L,
            pcmLevelBytes = 40_000L,
            pcmFreeBytes = 60_000L
        )
        val aboveWaterline = belowWaterline.copy(pcmLevelBytes = 55_000L, pcmFreeBytes = 45_000L)

        assertEquals(9_920, writeSize(nativeTransportStarted = true, metrics = belowWaterline))
        assertEquals(0, writeSize(nativeTransportStarted = true, metrics = aboveWaterline))
    }

    private fun writeSize(
        remainingBytes: Int = 65_536,
        inputSampleRate: Int = 48_000,
        inputFrameBytes: Int = 4,
        nativeTransportStarted: Boolean = false,
        playing: Boolean = false,
        prerollMs: Long = 300L,
        metrics: UsbExclusiveRuntimeMetrics = UsbExclusiveRuntimeMetrics()
    ) = UsbExclusivePcmWritePlanner.chooseWriteSize(
        remainingBytes = remainingBytes,
        inputSampleRate = inputSampleRate,
        inputFrameBytes = inputFrameBytes,
        nativeTransportStarted = nativeTransportStarted,
        playing = playing,
        prerollMs = prerollMs,
        metrics = metrics
    )
}
