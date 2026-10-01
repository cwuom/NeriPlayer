package moe.ouom.neriplayer.core.player.usb.confirmation

import android.content.Context
import android.media.AudioManager
import moe.ouom.neriplayer.data.model.playback.AudioDevice
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveLoudnessPeakSource
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class UsbExclusiveLoudPlaybackSnapshotSourceTest {
    private val context = mock(Context::class.java)
    private val audioManager = mock(AudioManager::class.java)

    @Test
    fun `system volume uses stream range and returns null when unavailable`() {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)).thenReturn(1)
        `when`(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(11, 1)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(6)

        assertEquals(50, UsbExclusiveLoudPlaybackSnapshotSource.systemMediaVolumePercent(context))
        assertEquals(100, UsbExclusiveLoudPlaybackSnapshotSource.systemMediaVolumePercent(context))

        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        assertNull(UsbExclusiveLoudPlaybackSnapshotSource.systemMediaVolumePercent(context))
    }

    @Test
    fun `system volume read failure remains unknown for conservative owner fallback`() {
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC))
            .thenThrow(SecurityException("volume unavailable"))

        assertNull(UsbExclusiveLoudPlaybackSnapshotSource.systemMediaVolumePercent(context))
    }

    @Test
    fun `capture retains route and native output format while avoiding redundant player read`() {
        val device = mock(AudioDevice::class.java)
        `when`(device.name).thenReturn("DAC")
        `when`(device.type).thenReturn(3)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        val calls = mutableListOf<String>()
        val signals = signals(
            device = device,
            reportedPlaying = true,
            playerInitialized = { true },
            playerIsPlaying = { calls += "isPlaying"; false },
            playerVolume = { 1.5f },
            nativeState = {
                UsbExclusiveNativeState(
                    outputSampleRate = 48_000,
                    runtimeReport = "uacVersion=2 candidateId=A sampleRate=96000 subslotBytes=3 lastOutputPeak=0.5"
                )
            }
        )

        val snapshot = UsbExclusiveLoudPlaybackSnapshotSource.capture(signals)

        assertTrue(snapshot.playbackAlreadyAudible)
        assertEquals(1f, snapshot.currentPlayerVolume, 0.0001f)
        assertEquals(96_000, snapshot.outputSampleRate)
        assertEquals("DAC", snapshot.deviceName)
        assertEquals("3:DAC:2:A", snapshot.outputRouteKey)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `failed player read falls back to requested volume and native sample rate`() {
        val snapshot = UsbExclusiveLoudPlaybackSnapshotSource.capture(
            signals(
                playerInitialized = { true },
                playerIsPlaying = { throw IllegalStateException("player unavailable") },
                playerVolume = { throw IllegalStateException("player unavailable") },
                requestedVolume = { 0.4f },
                nativeState = { UsbExclusiveNativeState(outputSampleRate = 44_100) }
            )
        )

        assertFalse(snapshot.playbackAlreadyAudible)
        assertEquals(0.4f, snapshot.currentPlayerVolume, 0.0001f)
        assertEquals(44_100, snapshot.outputSampleRate)
        assertEquals("unknown:uac_unknown:candidate_unknown", snapshot.outputRouteKey)
    }

    @Test
    fun `uninitialized player uses requested volume without querying player`() {
        val snapshot = UsbExclusiveLoudPlaybackSnapshotSource.capture(
            signals(
                playerInitialized = { false },
                playerVolume = { error("player should not be read") },
                requestedVolume = { 0.25f }
            )
        )

        assertEquals(0.25f, snapshot.currentPlayerVolume, 0.0001f)
        assertFalse(snapshot.playbackAlreadyAudible)
    }

    @Test
    fun `audible recent peak can raise estimate but startup uses full scale ceiling`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            uacVersion = "2", subslotBytes = 3, lastOutputPeak = 0.5f
        )
        val base = UsbExclusiveLoudPlaybackSnapshot(
            systemVolumePercent = 100,
            usbExclusiveEnabled = true,
            appInForeground = true,
            playbackAlreadyAudible = false,
            currentPlayerVolume = 0.2f,
            bitPerfect = false,
            riskThresholdDbfs = -12,
            deviceName = "DAC",
            outputRouteKey = "3:DAC:2:A",
            outputSampleRate = 96_000,
            metrics = metrics
        )

        val startup = base.estimate(100)
        val audible = base.copy(playbackAlreadyAudible = true).estimate(100)

        assertEquals(1f, startup.playerVolume, 0.0001f)
        assertNull(startup.observedPeakDbfs)
        assertEquals(UsbExclusiveLoudnessPeakSource.RecentSample, audible.peakSource)
        assertTrue(audible.estimatedPeakDbfs > -10.0)
        assertNull(
            base.copy(
                playbackAlreadyAudible = true,
                metrics = metrics.copy(lastOutputPeak = Float.NaN)
            ).estimate(100).observedPeakDbfs
        )
    }

    private fun signals(
        device: AudioDevice? = null,
        reportedPlaying: Boolean = false,
        playerInitialized: () -> Boolean = { false },
        playerIsPlaying: () -> Boolean = { false },
        playerVolume: () -> Float = { 1f },
        requestedVolume: () -> Float = { 1f },
        nativeState: () -> UsbExclusiveNativeState = { UsbExclusiveNativeState() }
    ) = UsbExclusiveLoudPlaybackSignals(
        context = context,
        usbExclusiveEnabled = true,
        appInForeground = true,
        currentDevice = device,
        reportedPlaying = reportedPlaying,
        playerInitialized = playerInitialized,
        playerIsPlaying = playerIsPlaying,
        playerVolume = playerVolume,
        requestedVolume = requestedVolume,
        nativeState = nativeState,
        bitPerfect = false,
        riskThresholdDbfs = -12
    )
}
