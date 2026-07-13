package moe.ouom.neriplayer.core.player.usb.sink

import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbExclusivePcmWritePlannerTest {

    @Test
    fun `limits healthy queue writes to usb transfer window`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 288_000L)
        )

        assertEquals(12_288, writeSize)
    }

    @Test
    fun `uses available pcm target headroom when it is smaller than transfer window`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 265_200L)
        )

        assertEquals(240, writeSize)
    }

    @Test
    fun `waits when cached healthy queue is full`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 0L)
        )

        assertEquals(0, writeSize)
    }

    @Test
    fun `waits when running queue is already above target waterline`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 96_000,
            inputFrameBytes = 8,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 177_408L)
        )

        assertEquals(0, writeSize)
    }

    @Test
    fun `does not probe when full queue has transport error`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(
                pcmFreeBytes = 0L,
                transportFailed = true,
                lastError = "transfer_status=5"
            )
        )

        assertEquals(0, writeSize)
    }

    @Test
    fun `falls back to default chunk when report has no transfer size`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = UsbExclusiveRuntimeMetrics()
        )

        assertEquals(12_288, writeSize)
    }

    @Test
    fun `keeps initial preroll bounded before transport starts`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 256_000,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = false,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 288_000L)
        )

        assertEquals(12_288, writeSize)
    }

    private fun metrics(
        pcmFreeBytes: Long,
        transportFailed: Boolean = false,
        lastError: String = "none"
    ) = UsbExclusiveRuntimeMetrics(
        sampleRate = 48_000,
        channelCount = 2,
        subslotBytes = 2,
        transferBytes = 3_072,
        lastTransferBytes = 3_072,
        pcmLevelBytes = 288_000 - pcmFreeBytes,
        pcmCapacityBytes = 288_000,
        pcmFreeBytes = pcmFreeBytes,
        transportFailed = transportFailed,
        running = true,
        lastError = lastError
    )
}
