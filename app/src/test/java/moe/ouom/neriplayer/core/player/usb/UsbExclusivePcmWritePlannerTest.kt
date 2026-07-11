package moe.ouom.neriplayer.core.player.usb

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
    fun `uses available pcm free space when it is smaller than transfer window`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 3_072L)
        )

        assertEquals(3_072, writeSize)
    }

    @Test
    fun `uses one transfer probe when cached healthy queue is full`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 0L)
        )

        assertEquals(3_072, writeSize)
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
