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
    fun `uses capacity aware queue headroom when it is smaller than transfer window`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 48_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = metrics(pcmFreeBytes = 155_760L)
        )

        assertEquals(11_760, writeSize)
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
            metrics = metrics(pcmFreeBytes = 140_000L)
        )

        assertEquals(0, writeSize)
    }

    @Test
    fun `keeps accepting writes while running queue is below stabilized waterline`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 17_408,
            inputSampleRate = 96_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = UsbExclusiveRuntimeMetrics(
                sampleRate = 96_000,
                channelCount = 2,
                subslotBytes = 2,
                transferBytes = 768,
                lastTransferBytes = 768,
                pcmLevelBytes = 48_128,
                pcmCapacityBytes = 576_000,
                pcmFreeBytes = 527_872,
                transportFailed = false,
                running = true,
                lastError = "none"
            )
        )

        assertEquals(7_680, writeSize)
    }

    @Test
    fun `waits once running queue crosses stabilized waterline`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 17_408,
            inputSampleRate = 96_000,
            inputFrameBytes = 4,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 300L,
            metrics = UsbExclusiveRuntimeMetrics(
                sampleRate = 96_000,
                channelCount = 2,
                subslotBytes = 2,
                transferBytes = 768,
                lastTransferBytes = 768,
                pcmLevelBytes = 288_000,
                pcmCapacityBytes = 576_000,
                pcmFreeBytes = 288_000,
                transportFailed = false,
                running = true,
                lastError = "none"
            )
        )

        assertEquals(0, writeSize)
    }

    @Test
    fun `recovery mode allows larger refill chunks when queue is nearly drained`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 96_000,
            inputFrameBytes = 8,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 150L,
            metrics = UsbExclusiveRuntimeMetrics(
                sampleRate = 96_000,
                channelCount = 2,
                subslotBytes = 4,
                transferBytes = 1_536,
                lastTransferBytes = 1_536,
                pcmLevelBytes = 3_072L,
                pcmCapacityBytes = 192_000L,
                pcmFreeBytes = 188_928L,
                playerZeroFillBytes = 1_536L,
                transportFailed = false,
                running = true,
                lastError = "none"
            )
        )

        assertEquals(15_360, writeSize)
    }

    @Test
    fun `192 kHz 32 bit path reserves enough write budget for renderer cadence`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 192_000,
            inputFrameBytes = 8,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 80L,
            metrics = UsbExclusiveRuntimeMetrics(
                sampleRate = 192_000,
                channelCount = 2,
                subslotBytes = 4,
                transferBytes = 3_200,
                lastTransferBytes = 1_536,
                pcmLevelBytes = 12_800L,
                pcmCapacityBytes = 384_000L,
                pcmFreeBytes = 371_200L,
                playerZeroFillBytes = 1_536L,
                transportFailed = false,
                running = true,
                lastError = "none"
            )
        )

        assertEquals(32_000, writeSize)
    }

    @Test
    fun `background high resolution queue keeps accepting writes below its reserve`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 192_000,
            inputFrameBytes = 8,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 80L,
            metrics = UsbExclusiveRuntimeMetrics(
                sampleRate = 192_000,
                channelCount = 2,
                subslotBytes = 4,
                transferBytes = 3_200,
                lastTransferBytes = 1_536,
                pcmLevelBytes = 181_248L,
                pcmCapacityBytes = 2_304_000L,
                pcmFreeBytes = 2_122_752L,
                transportFailed = false,
                running = true,
                lastError = "none"
            )
        )

        assertEquals(30_720, writeSize)
    }

    @Test
    fun `background high resolution queue stops at its 750 ms reserve`() {
        val writeSize = UsbExclusivePcmWritePlanner.chooseWriteSize(
            remainingBytes = 65_536,
            inputSampleRate = 192_000,
            inputFrameBytes = 8,
            nativeTransportStarted = true,
            playing = true,
            prerollMs = 80L,
            metrics = UsbExclusiveRuntimeMetrics(
                sampleRate = 192_000,
                channelCount = 2,
                subslotBytes = 4,
                transferBytes = 3_200,
                lastTransferBytes = 1_536,
                pcmLevelBytes = 1_152_000L,
                pcmCapacityBytes = 2_304_000L,
                pcmFreeBytes = 1_152_000L,
                transportFailed = false,
                running = true,
                lastError = "none"
            )
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
