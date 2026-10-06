package moe.ouom.neriplayer.core.player.usb.sink

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusivePcmFormatPolicyTest {

    @Test
    fun `native pcm needs raw audio with a supported layout`() {
        assertTrue(isUsbNativePcmFormat(pcm()))
        assertTrue(isNativePcmFormat(pcm(channels = 8, encoding = C.ENCODING_PCM_FLOAT)))
        assertFalse(isNativePcmFormat(pcm(mimeType = MimeTypes.AUDIO_FLAC)))
        assertFalse(isNativePcmFormat(pcm(sampleRate = 0)))
        assertFalse(isNativePcmFormat(pcm(channels = 0)))
        assertFalse(isNativePcmFormat(pcm(channels = 9)))
        assertFalse(isNativePcmFormat(pcm(encoding = C.ENCODING_INVALID)))
    }

    @Test
    fun `pcm frame bytes follow the encoding sample width`() {
        assertEquals(2, pcmFrameBytes(C.ENCODING_PCM_8BIT, 2))
        assertEquals(4, pcmFrameBytes(C.ENCODING_PCM_16BIT, 2))
        assertEquals(2, pcmFrameBytes(C.ENCODING_PCM_16BIT_BIG_ENDIAN, 1))
        assertEquals(6, pcmFrameBytes(C.ENCODING_PCM_24BIT, 2))
        assertEquals(6, pcmFrameBytes(C.ENCODING_PCM_24BIT_BIG_ENDIAN, 2))
        assertEquals(8, pcmFrameBytes(C.ENCODING_PCM_32BIT, 2))
        assertEquals(4, pcmFrameBytes(C.ENCODING_PCM_32BIT_BIG_ENDIAN, 1))
        assertEquals(24, pcmFrameBytes(C.ENCODING_PCM_FLOAT, 6))
        assertEquals(0, pcmFrameBytes(C.ENCODING_INVALID, 2))
        assertEquals(0, pcmFrameBytes(C.ENCODING_PCM_16BIT, -2))
    }

    @Test
    fun `input description reports unknown mime types`() {
        assertEquals(
            "mime=unknown sampleRate=44100 channels=2 encoding=${C.ENCODING_PCM_16BIT}",
            inputFormatDescription(pcm(mimeType = null, sampleRate = 44_100))
        )
    }

    private fun pcm(
        mimeType: String? = MimeTypes.AUDIO_RAW,
        sampleRate: Int = 48_000,
        channels: Int = 2,
        encoding: Int = C.ENCODING_PCM_16BIT
    ): Format = Format.Builder()
        .setSampleMimeType(mimeType)
        .setSampleRate(sampleRate)
        .setChannelCount(channels)
        .setPcmEncoding(encoding)
        .build()
}
