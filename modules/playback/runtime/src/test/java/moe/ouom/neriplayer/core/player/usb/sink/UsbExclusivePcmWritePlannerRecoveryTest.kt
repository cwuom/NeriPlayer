package moe.ouom.neriplayer.core.player.usb.sink

import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbExclusivePcmWritePlannerRecoveryTest {

    @Test
    fun `zero filled running queue below the low watermark writes recovery sized chunks`() {
        val starved = UsbExclusiveRuntimeMetrics(transferBytes = 1_000L, playerZeroFillBytes = 10L, pcmLevelBytes = 1_000L)

        assertEquals(10_000, writeSize(metrics = starved))
        assertEquals(4_000, writeSize(metrics = starved.copy(pcmLevelBytes = 8_000L)))
        assertEquals(4_000, writeSize(metrics = starved, nativeTransportStarted = false))
        assertEquals(4_000, writeSize(metrics = starved.copy(playerZeroFillBytes = null)))
        assertEquals(4_000, writeSize(metrics = starved.copy(playerZeroFillBytes = 0L)))
        assertEquals(4_000, writeSize(metrics = starved.copy(pcmLevelBytes = null)))
    }

    @Test
    fun `low watermark follows the reported output format`() {
        val starved = UsbExclusiveRuntimeMetrics(transferBytes = 1_000L, playerZeroFillBytes = 10L, pcmLevelBytes = 20_000L)

        assertEquals(10_000, writeSize(metrics = starved.copy(sampleRate = 96_000, channelCount = 2, subslotBytes = 4)))
        assertEquals(4_000, writeSize(metrics = starved.copy(sampleRate = 0, channelCount = 2, subslotBytes = 4)))
        assertEquals(4_000, writeSize(metrics = starved.copy(pcmLevelBytes = 1_000L), inputSampleRate = 0))
    }

    @Test
    fun `last transfer size is used when the current transfer size is unknown`() {
        assertEquals(4_000, writeSize(metrics = UsbExclusiveRuntimeMetrics(transferBytes = 0L, lastTransferBytes = 1_000L)))
        assertEquals(12_288, writeSize(metrics = UsbExclusiveRuntimeMetrics(lastTransferBytes = 0L)))
    }

    @Test
    fun `requested running queue target bounds the headroom`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            transferBytes = 1_000L,
            pcmCapacityBytes = 1_000_000L,
            pcmLevelBytes = 170_000L,
            pcmFreeBytes = 830_000L
        )

        assertEquals(
            14_320,
            writeSize(metrics = metrics, inputSampleRate = 192_000, inputFrameBytes = 8, runningQueueTargetMs = 120L)
        )
    }

    @Test
    fun `capacity based target is capped at three quarters of the ring buffer`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            transferBytes = 16_000L,
            pcmCapacityBytes = 100_000L,
            pcmLevelBytes = 50_000L,
            pcmFreeBytes = 50_000L
        )

        assertEquals(25_000, writeSize(metrics = metrics))
    }

    @Test
    fun `unknown output rate falls back to the transfer floor`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            transferBytes = 1_000L,
            pcmCapacityBytes = 100_000L,
            pcmLevelBytes = 3_000L,
            pcmFreeBytes = 97_000L
        )

        assertEquals(3_000, writeSize(metrics = metrics, inputSampleRate = 0))
    }

    @Test
    fun `resampled writes keep two frames of slack`() {
        val metrics = UsbExclusiveRuntimeMetrics(pcmFreeBytes = 4_000L, sampleRate = 96_000, channelCount = 2, subslotBytes = 2)

        assertEquals(1_992, writeSize(metrics = metrics))
        assertEquals(4, writeSize(metrics = metrics.copy(pcmFreeBytes = 8L), remainingBytes = 4))
    }

    private fun writeSize(
        metrics: UsbExclusiveRuntimeMetrics,
        remainingBytes: Int = 65_536,
        inputSampleRate: Int = 48_000,
        inputFrameBytes: Int = 4,
        nativeTransportStarted: Boolean = true,
        runningQueueTargetMs: Long? = null
    ) = UsbExclusivePcmWritePlanner.chooseWriteSize(
        remainingBytes = remainingBytes,
        inputSampleRate = inputSampleRate,
        inputFrameBytes = inputFrameBytes,
        nativeTransportStarted = nativeTransportStarted,
        playing = false,
        prerollMs = 0L,
        metrics = metrics,
        runningQueueTargetMs = runningQueueTargetMs
    )
}
