@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class FfmpegPcm16AudioSinkTest {
    @Test
    fun `float pcm support is hidden from the ffmpeg renderer`() {
        val delegate = mock(AudioSink::class.java)
        val format = rawPcmFormat(C.ENCODING_PCM_FLOAT)
        `when`(delegate.supportsFormat(format)).thenReturn(true)
        `when`(delegate.getFormatSupport(format))
            .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        val sink = FfmpegPcm16AudioSink(delegate) { true }

        assertFalse(sink.supportsFormat(format))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format))
        verify(delegate, never()).supportsFormat(format)
        verify(delegate, never()).getFormatSupport(format)
    }

    @Test
    fun `pcm16 support is delegated for ffmpeg fallback`() {
        val delegate = mock(AudioSink::class.java)
        val format = rawPcmFormat(C.ENCODING_PCM_16BIT)
        `when`(delegate.supportsFormat(format)).thenReturn(true)
        `when`(delegate.getFormatSupport(format))
            .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        val sink = FfmpegPcm16AudioSink(delegate) { true }

        assertTrue(sink.supportsFormat(format))
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            sink.getFormatSupport(format)
        )
        verify(delegate).supportsFormat(format)
        verify(delegate).getFormatSupport(format)
    }

    @Test
    fun `java default methods are forwarded to the wrapped sink`() {
        val delegate = mock(AudioSink::class.java)
        val sink = FfmpegPcm16AudioSink(delegate) { true }

        sink.setOutputStreamOffsetUs(123L)

        verify(delegate).setOutputStreamOffsetUs(123L)
    }

    @Test
    fun `float pcm support is delegated when high resolution output is enabled`() {
        val delegate = mock(AudioSink::class.java)
        val format = rawPcmFormat(C.ENCODING_PCM_FLOAT)
        `when`(delegate.supportsFormat(format)).thenReturn(true)
        `when`(delegate.getFormatSupport(format))
            .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        val sink = FfmpegPcm16AudioSink(delegate) { false }

        assertTrue(sink.supportsFormat(format))
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            sink.getFormatSupport(format)
        )
        verify(delegate).supportsFormat(format)
        verify(delegate).getFormatSupport(format)
    }

    @Test
    fun `float pcm support is restored when usb exclusive output becomes active`() {
        val delegate = mock(AudioSink::class.java)
        val format = rawPcmFormat(C.ENCODING_PCM_FLOAT)
        `when`(delegate.supportsFormat(format)).thenReturn(true)
        `when`(delegate.getFormatSupport(format))
            .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        var usbExclusiveOutput = false
        val sink = FfmpegPcm16AudioSink(delegate) { !usbExclusiveOutput }

        assertFalse(sink.supportsFormat(format))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format))
        verify(delegate, never()).supportsFormat(format)
        verify(delegate, never()).getFormatSupport(format)

        usbExclusiveOutput = true

        assertTrue(sink.supportsFormat(format))
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            sink.getFormatSupport(format)
        )
        verify(delegate).supportsFormat(format)
        verify(delegate).getFormatSupport(format)
    }

    private fun rawPcmFormat(encoding: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(44_100)
        .setChannelCount(2)
        .setPcmEncoding(encoding)
        .build()
}
