package moe.ouom.neriplayer.core.player.usb.sink

import androidx.media3.common.C
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.UsbExclusiveSampleRateMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveOutputFormatResolverTest {

    @Test
    fun `auto float mode keeps 32-bit first and defers 24-in-32 fallback`() {
        val preferred = ResolvedUsbOutputFormat(
            sampleRate = 96_000,
            channelCount = 2,
            bitDepth = 32,
            subslotBytes = 4,
            bufferDurationMs = 250,
            description = "rate=96000 channels=2 bits=32 subslot=4 " +
                "rateMode=follow_source bitMode=auto policy=closest_supported"
        )

        val candidates = UsbExclusiveOutputFormatResolver.openCandidates(
            preferred = preferred,
            inputEncoding = C.ENCODING_PCM_FLOAT
        )

        assertEquals(
            listOf(
                "rate=96000 channels=2 bits=32 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                "rate=96000 channels=2 bits=24 subslot=3 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                "rate=96000 channels=2 bits=24 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                "rate=96000 channels=2 bits=16 subslot=2 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported"
            ),
            candidates.map(ResolvedUsbOutputFormat::description)
        )
        assertTrue(candidates.all { it.sampleRate == 96_000 })
    }

    @Test
    fun `24-bit preferred output keeps source container first`() {
        val preferred = ResolvedUsbOutputFormat(
            sampleRate = 48_000,
            channelCount = 2,
            bitDepth = 24,
            subslotBytes = 3,
            bufferDurationMs = 250,
            description = "rate=48000 channels=2 bits=24 subslot=3 " +
                "rateMode=follow_source bitMode=auto policy=closest_supported"
        )

        val candidates = UsbExclusiveOutputFormatResolver.openCandidates(
            preferred = preferred,
            inputEncoding = C.ENCODING_PCM_24BIT
        )

        assertEquals(
            listOf(
                "rate=48000 channels=2 bits=24 subslot=3 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                "rate=48000 channels=2 bits=24 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                "rate=48000 channels=2 bits=16 subslot=2 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported"
            ),
            candidates.map(ResolvedUsbOutputFormat::description)
        )
        assertTrue(candidates.all { it.sampleRate == 48_000 })
    }

    @Test
    fun `float decoder output requests full 32-bit usb source depth`() {
        assertEquals(
            32,
            UsbExclusiveOutputFormatResolver.sourceBitDepthForEncoding(C.ENCODING_PCM_FLOAT)
        )
    }

    @Test
    fun `integer decoder output preserves its declared bit depth`() {
        assertEquals(
            24,
            UsbExclusiveOutputFormatResolver.sourceBitDepthForEncoding(C.ENCODING_PCM_24BIT)
        )
        assertEquals(
            32,
            UsbExclusiveOutputFormatResolver.sourceBitDepthForEncoding(C.ENCODING_PCM_32BIT)
        )
    }

    @Test
    fun `follow source blocks implicit native sample rate conversion`() {
        assertTrue(
            UsbExclusiveOutputFormatResolver.shouldBlockImplicitNativeSampleRateConversion(
                preferences = UsbExclusivePreferences(),
                inputSampleRate = 96_000,
                resolvedSampleRate = 48_000
            )
        )
        assertFalse(
            UsbExclusiveOutputFormatResolver.shouldBlockImplicitNativeSampleRateConversion(
                preferences = UsbExclusivePreferences(
                    sampleRateMode = UsbExclusiveSampleRateMode.RATE_48000
                ),
                inputSampleRate = 96_000,
                resolvedSampleRate = 48_000
            )
        )
    }

    @Test
    fun `32 bit request also tries 24 bit usb container variants`() {
        val preferred = ResolvedUsbOutputFormat(
            sampleRate = 96_000,
            channelCount = 2,
            bitDepth = 32,
            subslotBytes = 4,
            bufferDurationMs = 250,
            description = "rate=96000 channels=2 bits=32 subslot=4 rateMode=follow_source bitMode=auto policy=closest_supported"
        )

        val candidates = UsbExclusiveOutputFormatResolver.openCandidates(
            preferred = preferred,
            inputEncoding = C.ENCODING_PCM_FLOAT
        )

        assertEquals(32, candidates.first().bitDepth)
        assertEquals(4, candidates.first().subslotBytes)
        assertTrue(candidates.any { it.bitDepth == 32 && it.subslotBytes == 4 })
        assertTrue(candidates.any { it.bitDepth == 24 && it.subslotBytes == 3 })
        assertTrue(candidates.any { it.bitDepth == 24 && it.subslotBytes == 4 })
        assertTrue(candidates.any { it.bitDepth == 16 && it.subslotBytes == 2 })
    }

    @Test
    fun `float input prepares 32-bit native pcm for 24-in-32 usb output`() {
        val prepared = UsbExclusiveOutputFormatResolver.preparedInputPcmFormat(
            inputEncoding = C.ENCODING_PCM_FLOAT,
            outputFormat = ResolvedUsbOutputFormat(
                sampleRate = 96_000,
                channelCount = 2,
                bitDepth = 24,
                subslotBytes = 4,
                bufferDurationMs = 250,
                description = "rate=96000 channels=2 bits=24 subslot=4"
            )
        )

        assertEquals(C.ENCODING_PCM_32BIT, prepared?.encoding)
        assertEquals(4, prepared?.bytesPerSample)
    }

    @Test
    fun `float input keeps packed 24-bit native pcm for packed usb output`() {
        val prepared = UsbExclusiveOutputFormatResolver.preparedInputPcmFormat(
            inputEncoding = C.ENCODING_PCM_FLOAT,
            outputFormat = ResolvedUsbOutputFormat(
                sampleRate = 96_000,
                channelCount = 2,
                bitDepth = 24,
                subslotBytes = 3,
                bufferDurationMs = 250,
                description = "rate=96000 channels=2 bits=24 subslot=3"
            )
        )

        assertEquals(C.ENCODING_PCM_24BIT, prepared?.encoding)
        assertEquals(3, prepared?.bytesPerSample)
    }

    @Test
    fun `24 bit request keeps both packed and 24 in 4 usb variants`() {
        val preferred = ResolvedUsbOutputFormat(
            sampleRate = 44_100,
            channelCount = 2,
            bitDepth = 24,
            subslotBytes = 3,
            bufferDurationMs = 250,
            description = "rate=44100 channels=2 bits=24 subslot=3 rateMode=follow_source bitMode=auto policy=closest_supported"
        )

        val candidates = UsbExclusiveOutputFormatResolver.openCandidates(
            preferred = preferred,
            inputEncoding = C.ENCODING_PCM_24BIT
        )

        assertEquals(24, candidates.first().bitDepth)
        assertEquals(3, candidates.first().subslotBytes)
        assertTrue(candidates.any { it.bitDepth == 24 && it.subslotBytes == 4 })
        assertTrue(candidates.any { it.bitDepth == 16 && it.subslotBytes == 2 })
    }

    @Test
    fun `system fallback policy does not silently downshift bit depth`() {
        val preferred = ResolvedUsbOutputFormat(
            sampleRate = 192_000,
            channelCount = 2,
            bitDepth = 32,
            subslotBytes = 4,
            bufferDurationMs = 250,
            description = "rate=192000 channels=2 bits=32 subslot=4 rateMode=follow_source bitMode=32 policy=system_fallback"
        )

        val candidates = UsbExclusiveOutputFormatResolver.openCandidates(
            preferred = preferred,
            inputEncoding = C.ENCODING_PCM_FLOAT
        )

        assertTrue(candidates.all { it.bitDepth == 32 })
        assertEquals(listOf(192_000), candidates.map(ResolvedUsbOutputFormat::sampleRate))
    }

    @Test
    fun `open candidates never change sample rate without a dedicated quality path`() {
        val preferred = ResolvedUsbOutputFormat(
            sampleRate = 192_000,
            channelCount = 2,
            bitDepth = 32,
            subslotBytes = 4,
            bufferDurationMs = 250,
            description = "rate=192000 channels=2 bits=32 subslot=4 " +
                "rateMode=follow_source bitMode=auto policy=closest_supported"
        )

        val candidates = UsbExclusiveOutputFormatResolver.openCandidates(
            preferred = preferred,
            inputEncoding = C.ENCODING_PCM_FLOAT
        )

        assertEquals(192_000, candidates.first().sampleRate)
        assertTrue(candidates.all { it.sampleRate == 192_000 })
        assertTrue(candidates.any { it.bitDepth == 16 && it.subslotBytes == 2 })
    }

    @Test
    fun `24 bit usb container changes require an actual reopen`() {
        assertFalse(
            UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput(
                currentDescription = "rate=96000 channels=2 bits=24 subslot=3 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                preferredDescription = "rate=96000 channels=2 bits=24 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported"
            )
        )
    }

    @Test
    fun `different usb bit depths are not treated as reusable equivalents`() {
        assertFalse(
            UsbExclusiveOutputFormatResolver.canReuseEquivalentOutput(
                currentDescription = "rate=96000 channels=2 bits=16 subslot=2 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported",
                preferredDescription = "rate=96000 channels=2 bits=32 subslot=4 " +
                    "rateMode=follow_source bitMode=auto policy=closest_supported"
            )
        )
    }
}
