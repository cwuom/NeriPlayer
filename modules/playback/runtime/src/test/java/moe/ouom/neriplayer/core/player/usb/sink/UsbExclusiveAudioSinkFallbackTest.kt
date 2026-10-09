@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathTracker
import moe.ouom.neriplayer.core.player.usb.route.UsbRouteTransitionOwner
import moe.ouom.neriplayer.core.player.usb.route.UsbSinkRouteOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UsbExclusiveAudioSinkFallbackTest {
    private val fallbackSink = mock(AudioSink::class.java)
    private val sinkRouteOwner = mock(UsbSinkRouteOwner::class.java)
    private lateinit var originalSinkRouteOwner: UsbSinkRouteOwner
    private lateinit var originalTransitionOwner: UsbRouteTransitionOwner

    @Before
    fun installRouteOwners() {
        originalSinkRouteOwner = PlayerManager.usbSinkRouteOwner
        originalTransitionOwner = PlayerManager.usbRouteTransitionOwner
        PlayerManager.usbSinkRouteOwner = sinkRouteOwner
    }

    @After
    fun resetUsbState() {
        PlayerManager.usbSinkRouteOwner = originalSinkRouteOwner
        PlayerManager.usbRouteTransitionOwner = originalTransitionOwner
        PlayerManager.usbExclusivePlaybackEnabled = false
        PlayerManager.markUsbExclusivePlaybackPreparing(false, "test_reset")
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
    fun `cached sink state is replayed when the system AudioTrack is configured`() {
        val sink = createSink()
        sink.configure(rawPcmFormat(), 0, null)
        verify(fallbackSink).disableTunneling()
        verify(fallbackSink, never()).setPreferredDevice(any())
        verify(fallbackSink, never()).setOutputStreamOffsetUs(5L)
        verify(fallbackSink, never()).setAudioSessionId(7)

        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build()
        sink.setAudioAttributes(attributes)
        sink.setAudioSessionId(7)
        sink.setPreferredDevice(null)
        sink.setOutputStreamOffsetUs(5L)
        sink.enableTunnelingV21()
        clearInvocations(fallbackSink)

        sink.configure(rawPcmFormat(), 0, null)

        verify(fallbackSink).setAudioAttributes(attributes)
        verify(fallbackSink).setAudioSessionId(7)
        verify(fallbackSink).setAuxEffectInfo(any())
        verify(fallbackSink).setPreferredDevice(null)
        verify(fallbackSink).setOutputStreamOffsetUs(5L)
        verify(fallbackSink).enableTunnelingV21()
        verify(fallbackSink, never()).disableTunneling()
        verify(fallbackSink).configure(rawPcmFormat(), 0, null)
    }

    @Test
    fun `transient open gate holds playback instead of configuring the system AudioTrack`() {
        val sink = createSink()
        val reason = "native_open_deferred:route_jitter"
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)

        sink.configure(rawPcmFormat(), 0, null)
        assertEquals(reason, UsbExclusiveAudioPathTracker.state.value.fallbackReason)
        assertTrue(PlayerManager.usbExclusivePlaybackPreparingFlow.value)
        sink.play()

        verify(fallbackSink, never()).configure(any(), eq(0), any())
        verify(fallbackSink, never()).play()

        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        sink.configure(rawPcmFormat(), 0, null)
        UsbExclusiveAudioPathTracker.forceSystemFallback(reason)
        sink.configure(rawPcmFormat(), 0, null)

        verify(fallbackSink).pause()
        verify(fallbackSink).reset()
        assertEquals(emptyList<String>(), scheduledReconfigurations())
    }

    @Test
    fun `usb open gate retries are scheduled at most three times`() {
        PlayerManager.usbExclusivePlaybackEnabled = true
        val sink = createSink()
        UsbExclusiveAudioPathTracker.forceSystemFallback("native_open_deferred:route_jitter")

        repeat(4) { sink.configure(rawPcmFormat(), 0, null) }

        assertEquals(
            List(3) { "usb_exclusive_open_gate_retry:allowWhilePlaying=true" },
            scheduledReconfigurations()
        )
        verify(fallbackSink, never()).configure(any(), eq(0), any())
    }

    @Test
    fun `usb release barrier holds system audio until the release finishes`() {
        val transitionOwner = UsbRouteTransitionOwner(CoroutineScope(Dispatchers.Unconfined), {})
        PlayerManager.usbRouteTransitionOwner = transitionOwner
        val sink = createSink()
        transitionOwner.beginSystemAudioRelease()

        sink.configure(rawPcmFormat(), 0, null)
        sink.play()

        verify(fallbackSink, never()).configure(any(), eq(0), any())
        verify(fallbackSink, never()).play()

        transitionOwner.finishSystemAudioRelease()
        sink.configure(rawPcmFormat(), 0, null)
        sink.play()

        verify(fallbackSink).configure(rawPcmFormat(), 0, null)
        verify(fallbackSink).play()
        assertTrue(UsbExclusiveAudioPathTracker.state.value.sinkPlaying)
    }

    @Test
    fun `pause flush and reset forward to the configured system AudioTrack only`() {
        val sink = createSink()
        sink.pause()
        sink.flush()
        sink.reset()
        verify(fallbackSink).pause()
        verify(fallbackSink, never()).flush()
        verify(fallbackSink, never()).reset()

        sink.configure(rawPcmFormat(), 0, null)
        sink.play()
        assertTrue(UsbExclusiveAudioPathTracker.state.value.sinkPlaying)
        sink.pause()
        sink.flush()
        assertFalse(UsbExclusiveAudioPathTracker.state.value.sinkPlaying)
        verify(fallbackSink, times(2)).pause()
        verify(fallbackSink).flush()

        sink.reset()
        sink.reset()
        verify(fallbackSink).reset()
        assertEquals("none", UsbExclusiveAudioPathTracker.state.value.inputFormat)
    }

    @Test
    fun `tunneling changes reconfigure the sink only while usb exclusive is enabled`() {
        val sink = createSink()
        sink.enableTunnelingV21()
        sink.disableTunneling()
        assertEquals(emptyList<String>(), scheduledReconfigurations())

        PlayerManager.usbExclusivePlaybackEnabled = true
        sink.enableTunnelingV21()
        sink.disableTunneling()
        sink.disableTunneling()

        assertEquals(
            listOf(
                "tunneling_enabled:allowWhilePlaying=false",
                "tunneling_disabled:allowWhilePlaying=false"
            ),
            scheduledReconfigurations()
        )
        verify(fallbackSink, times(3)).disableTunneling()
    }

    @Test
    fun `non pcm formats keep system support while usb exclusive is enabled`() {
        PlayerManager.usbExclusivePlaybackEnabled = true
        val flac = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setSampleRate(44_100)
            .setChannelCount(2)
            .build()
        `when`(fallbackSink.supportsFormat(flac)).thenReturn(false)
        `when`(fallbackSink.getFormatSupport(flac)).thenReturn(AudioSink.SINK_FORMAT_UNSUPPORTED)
        val sink = createSink()

        assertFalse(sink.supportsFormat(flac))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(flac))

        `when`(fallbackSink.getFormatSupport(flac))
            .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, sink.getFormatSupport(flac))
    }

    @Test
    fun `usb native pcm upgrades transcoded system support to direct output`() {
        PlayerManager.usbExclusivePlaybackEnabled = true
        `when`(fallbackSink.getFormatSupport(rawPcmFormat()))
            .thenReturn(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING)
        val sink = createSink()

        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(rawPcmFormat()))
    }

    @Test
    fun `system output rejects pcm the fallback cannot play`() {
        `when`(fallbackSink.supportsFormat(rawPcmFormat())).thenReturn(false)
        val sink = createSink()

        assertFalse(sink.supportsFormat(rawPcmFormat()))
    }

    private fun scheduledReconfigurations(): List<String> =
        mockingDetails(sinkRouteOwner).invocations
            .filter { it.method.name == "schedule" }
            .map { "${it.arguments[0]}:allowWhilePlaying=${it.arguments[1]}" }

    private fun createSink(): UsbExclusiveAudioSink {
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

    private fun rawPcmFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(44_100)
        .setChannelCount(2)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()
}
