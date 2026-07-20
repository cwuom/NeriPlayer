@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UsbExclusiveAudioSinkTest {

    @After
    fun resetUsbState() {
        PlayerManager.usbExclusivePlaybackEnabled = false
        UsbExclusiveAudioPathTracker.updateRequested(enabled = false)
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = null,
            inputFormat = "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
    }

    @Test
    fun `system fallback keeps pending play until fallback sink is configured`() {
        PlayerManager.usbExclusivePlaybackEnabled = false
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        val fallbackSink = mock(AudioSink::class.java)
        val sink = UsbExclusiveAudioSink(
            context = context,
            fallbackSink = fallbackSink,
            observeSystemVolume = false
        )
        val format = rawPcmFormat()

        sink.play()

        verify(fallbackSink, never()).play()

        sink.configure(format, 0, null)

        verify(fallbackSink).configure(format, 0, null)
        verify(fallbackSink).play()
    }

    @Test
    fun `usb native path accepts float pcm for software conversion`() {
        PlayerManager.usbExclusivePlaybackEnabled = true
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        val fallbackSink = mock(AudioSink::class.java)
        `when`(fallbackSink.supportsFormat(rawFloatPcmFormat())).thenReturn(false)
        val sink = UsbExclusiveAudioSink(
            context = context,
            fallbackSink = fallbackSink,
            observeSystemVolume = false
        )

        assertTrue(sink.supportsFormat(rawFloatPcmFormat()))
    }

    @Test
    fun `pending native recovery runs before old transport resume`() {
        val calls = mutableListOf<String>()

        val result = prepareUsbExclusiveNativeWrite(
            executePendingRecovery = {
                calls += "recovery"
                true
            },
            resumeTransport = {
                calls += "resume"
                true
            }
        )

        assertEquals(UsbExclusivePreWriteResult.RecoveryScheduled, result)
        assertEquals(listOf("recovery"), calls)
    }

    @Test
    fun `old transport resumes only when no recovery action is pending`() {
        val calls = mutableListOf<String>()

        val result = prepareUsbExclusiveNativeWrite(
            executePendingRecovery = {
                calls += "recovery"
                false
            },
            resumeTransport = {
                calls += "resume"
                true
            }
        )

        assertEquals(UsbExclusivePreWriteResult.Ready, result)
        assertEquals(listOf("recovery", "resume"), calls)
    }

    @Test
    fun `failed old transport resume is reported after recovery check`() {
        val calls = mutableListOf<String>()

        val result = prepareUsbExclusiveNativeWrite(
            executePendingRecovery = {
                calls += "recovery"
                false
            },
            resumeTransport = {
                calls += "resume"
                false
            }
        )

        assertEquals(UsbExclusivePreWriteResult.TransportFailed, result)
        assertEquals(listOf("recovery", "resume"), calls)
    }

    private fun rawPcmFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(44_100)
        .setChannelCount(2)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()

    private fun rawFloatPcmFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(96_000)
        .setChannelCount(2)
        .setPcmEncoding(C.ENCODING_PCM_FLOAT)
        .build()
}
