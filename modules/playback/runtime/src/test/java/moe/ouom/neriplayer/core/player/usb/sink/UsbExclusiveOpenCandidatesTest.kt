package moe.ouom.neriplayer.core.player.usb.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveOpenCandidatesTest {

    @Test
    fun `24-bit output without fallback only tries 24-bit containers`() {
        assertEquals(
            listOf("48000/24/4", "48000/24/3"),
            candidateLayouts(preferred(bitDepth = 24, subslotBytes = 4, allowBitDepthFallback = false))
        )
    }

    @Test
    fun `16-bit output without fallback only tries 16-bit`() {
        assertEquals(
            listOf("48000/16/2"),
            candidateLayouts(preferred(bitDepth = 16, subslotBytes = 2, allowBitDepthFallback = false))
        )
    }

    @Test
    fun `16-bit output falls back to wider depths`() {
        assertEquals(
            listOf("48000/16/2", "48000/24/3", "48000/24/4", "48000/32/4"),
            candidateLayouts(preferred(bitDepth = 16, subslotBytes = 2))
        )
    }

    @Test
    fun `unsupported bit depth yields no candidates`() {
        assertTrue(UsbExclusiveOutputFormatResolver.openCandidates(preferred(bitDepth = 8, subslotBytes = 1)).isEmpty())
    }

    @Test
    fun `every distinct positive sample rate gets the full layout list in order`() {
        val preferred = preferred(bitDepth = 32, subslotBytes = 4, allowBitDepthFallback = false)
            .copy(alternativeSampleRates = listOf(44_100, 0, 48_000, -1, 96_000))

        assertEquals(listOf("48000/32/4", "44100/32/4", "96000/32/4"), candidateLayouts(preferred))
    }

    @Test
    fun `undersized container is widened to fit the bit depth`() {
        val candidates = UsbExclusiveOutputFormatResolver.openCandidates(
            preferred(bitDepth = 24, subslotBytes = 2, allowBitDepthFallback = false)
        )

        assertEquals(listOf(3, 4), candidates.map(ResolvedUsbOutputFormat::subslotBytes))
        assertEquals(
            "rate=48000 channels=2 bits=24 subslot=3 rateMode=follow_source bitMode=auto policy=closest_supported",
            candidates.first().description
        )
    }

    private fun candidateLayouts(preferred: ResolvedUsbOutputFormat): List<String> =
        UsbExclusiveOutputFormatResolver.openCandidates(preferred).map {
            "${it.sampleRate}/${it.bitDepth}/${it.subslotBytes}"
        }

    private fun preferred(
        bitDepth: Int,
        subslotBytes: Int,
        allowBitDepthFallback: Boolean = true
    ) = ResolvedUsbOutputFormat(
        sampleRate = 48_000,
        channelCount = 2,
        bitDepth = bitDepth,
        subslotBytes = subslotBytes,
        bufferDurationMs = 250,
        description = "rate=48000 channels=2 bits=$bitDepth subslot=$subslotBytes " +
            "rateMode=follow_source bitMode=auto policy=closest_supported",
        allowBitDepthFallback = allowBitDepthFallback
    )
}
