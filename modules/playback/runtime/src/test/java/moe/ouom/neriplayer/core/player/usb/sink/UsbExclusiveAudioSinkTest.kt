@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `aac requires decoding even when the device advertises direct output`() {
        assertRequiresDecoding(MimeTypes.AUDIO_AAC)
        assertRequiresDecoding(MimeTypes.AUDIO_AAC, advertisedDirectSupport = false)
    }

    @Test
    fun `mp3 requires decoding even when the device advertises direct output`() {
        assertRequiresDecoding(MimeTypes.AUDIO_MPEG)
        assertRequiresDecoding(MimeTypes.AUDIO_MPEG, advertisedDirectSupport = false)
    }

    @Test
    fun `system pcm output keeps the fallback format support`() {
        PlayerManager.usbExclusivePlaybackEnabled = false
        val fallbackSink = mock(AudioSink::class.java)
        val sink = createSink(fallbackSink)
        for (format in listOf(rawPcmFormat(), rawFloatPcmFormat())) {
            `when`(fallbackSink.supportsFormat(format)).thenReturn(true)
            `when`(fallbackSink.getFormatSupport(format))
                .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)

            assertTrue(sink.supportsFormat(format))
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format))
        }
    }

    private fun assertRequiresDecoding(mimeType: String, advertisedDirectSupport: Boolean = true) {
        val format = Format.Builder()
            .setSampleMimeType(mimeType)
            .setSampleRate(44_100)
            .setChannelCount(2)
            .setAverageBitrate(320_000)
            .build()
        val fallbackSink = mock(AudioSink::class.java)
        `when`(fallbackSink.supportsFormat(format)).thenReturn(advertisedDirectSupport)
        `when`(fallbackSink.getFormatSupport(format))
            .thenReturn(
                if (advertisedDirectSupport) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                else AudioSink.SINK_FORMAT_UNSUPPORTED
            )
        `when`(fallbackSink.getFormatOffloadSupport(format)).thenReturn(
            AudioOffloadSupport.Builder().setIsFormatSupported(true).build()
        )
        val sink = createSink(fallbackSink)

        for (usbEnabled in listOf(false, true)) {
            PlayerManager.usbExclusivePlaybackEnabled = usbEnabled

            assertFalse("compressed $mimeType must be decoded", sink.supportsFormat(format))
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format))
            assertFalse(sink.getFormatOffloadSupport(format).isFormatSupported)
        }
    }

    private fun createSink(fallbackSink: AudioSink): UsbExclusiveAudioSink {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        return UsbExclusiveAudioSink(
            context = context,
            fallbackSink = fallbackSink,
            observeSystemVolume = false,
            nativeUsbAudioDeviceAvailable = { true }
        )
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
            observeSystemVolume = false,
            nativeUsbAudioDeviceAvailable = { true }
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
            observeSystemVolume = false,
            nativeUsbAudioDeviceAvailable = { true }
        )

        assertTrue(sink.supportsFormat(rawFloatPcmFormat()))
    }

    @Test
    fun `usb native pcm is rejected before decoder selection when no device is available`() {
        PlayerManager.usbExclusivePlaybackEnabled = true
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        val fallbackSink = mock(AudioSink::class.java)
        `when`(fallbackSink.supportsFormat(rawPcmFormat())).thenReturn(true)
        `when`(fallbackSink.getFormatSupport(rawPcmFormat()))
            .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        val sink = UsbExclusiveAudioSink(
            context = context,
            fallbackSink = fallbackSink,
            observeSystemVolume = false,
            nativeUsbAudioDeviceAvailable = { false }
        )

        assertFalse(sink.supportsFormat(rawPcmFormat()))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(rawPcmFormat()))
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

    @Test
    fun `native backpressure observation tolerates queue progress without an active native route`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        val sink = UsbExclusiveAudioSink(
            context = context,
            fallbackSink = mock(AudioSink::class.java),
            observeSystemVolume = false,
            nativeUsbAudioDeviceAvailable = { false }
        )

        val record = UsbExclusiveAudioSink::class.java.getDeclaredMethod(
            "recordBenignNativeBackpressure",
            java.lang.Long.TYPE, Integer.TYPE, Integer.TYPE, String::class.java
        ).apply { isAccessible = true }
        record.invoke(sink, 1_000L, 8, 0, backpressureReport(4))
        val attempts = UsbExclusiveAudioSink::class.java
            .getDeclaredField("nativeBackpressureSoftRestartAttempts")
            .apply { isAccessible = true }
        attempts.setInt(sink, 1)
        record.invoke(sink, 1_500L, 8, 0, backpressureReport(5))
        assertEquals(0, attempts.getInt(sink))
    }

    @Test
    fun `native end of stream drains the resampler tail into the native queue`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        val sink = UsbExclusiveAudioSink(
            context = context,
            fallbackSink = mock(AudioSink::class.java),
            observeSystemVolume = false,
            nativeUsbAudioDeviceAvailable = { true }
        )
        val port = mock(UsbExclusivePcmWritePort::class.java)
        setPrivateField(sink, "pcmWriter", UsbExclusivePcmWriter(port) {})
        setPrivateField(sink, "usingNative", true)
        setPrivateField(sink, "nativeHandle", 7L)

        sink.playToEndOfStream()

        verify(port).drainInputEnd(7L)
    }

    private fun setPrivateField(target: Any, name: String, value: Any) {
        UsbExclusiveAudioSink::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun backpressureReport(completedTransfers: Int): String =
        "source=player_pcm pcmLevel=288000/288000 pcmFreeBytes=0 " +
            "completedTransfers=$completedTransfers inFlight=8 running=true transportFailed=false"

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
