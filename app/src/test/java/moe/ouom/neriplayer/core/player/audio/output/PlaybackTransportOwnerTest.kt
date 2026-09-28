package moe.ouom.neriplayer.core.player.audio.output

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTransportOwnerTest {
    @Test
    fun `foreground and bootstrap decisions use the same transport snapshot`() = runTest {
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { 0L }
        port.transport = port.transport.copy(resumeRequested = true, restoredPlaybackCanAutoResume = true)

        assertTrue(owner.isTransportActive())
        assertTrue(owner.shouldRunForegroundService())
        assertTrue(owner.shouldBootstrapService())
        assertTrue(port.ensureCalls > 0)

        port.transport = port.transport.copy(hasCurrentSong = false)
        assertFalse(owner.isTransportActiveWithoutInitialization())
        assertFalse(owner.shouldRunForegroundService())
        assertFalse(owner.shouldBootstrapService())
    }

    @Test
    fun `automatic pause during track advance is guarded but user stop is not`() = runTest {
        var now = 10_000L
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { now }
        port.transport = port.transport.copy(
            resumeRequested = true,
            playWhenReady = true,
            playerPlaybackState = Player.STATE_READY,
            currentPositionMs = 100L
        )
        owner.markAutoTrackAdvance()

        assertTrue(owner.shouldIgnoreExternalPause("audio_focus"))
        assertFalse(owner.shouldIgnoreExternalPause("intent_stop"))
        now += 2_001L
        assertFalse(owner.shouldIgnoreExternalPause("audio_focus"))
        owner.resetForRelease()
        assertFalse(owner.shouldIgnoreExternalPause("audio_focus"))
    }

    @Test
    fun `USB focus guard follows native route and stable transport`() = runTest {
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { 1L }
        port.transport = port.transport.copy(resumeRequested = true)
        port.usb = usb().copy(
            effectiveNativePath = true,
            sinkPlaying = true,
            nativeOpened = true,
            nativeStreaming = true,
            nativeTransportHealthy = true
        )

        assertTrue(owner.shouldUseUsbFocusGuard())
        assertTrue(owner.shouldBypassPlatformFocus())
        assertTrue(owner.isUsbNativePlaybackStable())
        assertTrue(owner.isUsbPlaybackActiveForForegroundService())
        owner.applyAudioFocusPolicyOnMainThread()
        assertEquals(listOf(false), port.audioFocusEnabled)
        assertEquals(listOf(true), port.foregroundGuardEnabled)

        port.usb = port.usb.copy(fallbackReason = "transport_failed", effectiveNativePath = false)
        assertTrue(owner.shouldBypassPlatformFocus())
        assertFalse(owner.isUsbNativePlaybackStable())
        port.usb = port.usb.copy(enabled = false)
        assertFalse(owner.shouldBypassPlatformFocus())
    }

    @Test
    fun `short USB disruption protects resume and focus loss pauses once`() = runTest {
        var now = 5_000L
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { now }
        port.transport = port.transport.copy(resumeRequested = true, playWhenReady = true)
        port.usb = usb()
        owner.markUsbFocusDisrupted(-1)

        assertTrue(owner.isRecentUsbFocusDisruption())
        assertTrue(owner.shouldIgnoreExternalPause("audio_focus_loss"))
        assertFalse(owner.shouldIgnoreExternalPause("media_session_pause"))
        owner.pauseForUsbFocusLoss(-1)
        assertEquals(listOf(-1), port.focusLossPauses)
        now += 3_001L
        assertFalse(owner.isRecentUsbFocusDisruption())
        port.usb = port.usb.copy(mixedPlayback = true)
        owner.pauseForUsbFocusLoss(-2)
        assertEquals(listOf(-1), port.focusLossPauses)
    }

    @Test
    fun `off main focus policy is dispatched and mixed playback retains platform focus`() = runTest {
        val port = RecordingPort().apply { mainThread = false }
        val owner = PlaybackTransportOwner(backgroundScope, port) { 0L }
        port.usb = usb().copy(mixedPlayback = true)

        owner.applyAudioFocusPolicy()
        runCurrent()

        assertEquals(listOf(false), port.audioFocusEnabled)
        assertEquals(listOf(false), port.foregroundGuardEnabled)
    }

    @Test
    fun `transport intent and buffering require initialized current playback`() = runTest {
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { 0L }
        assertFalse(owner.isTransportActiveWithoutInitialization())
        assertFalse(owner.isBuffering())
        listOf(
            port.transport.copy(playJobActive = true),
            port.transport.copy(pendingPauseJobActive = true),
            port.transport.copy(playWhenReady = true),
            port.transport.copy(isPlaying = true)
        ).forEach { state ->
            port.transport = state
            assertTrue(owner.isTransportActiveWithoutInitialization())
        }
        port.transport = port.transport.copy(playJobActive = true)
        assertTrue(owner.isBuffering())
        port.transport = port.transport.copy(playJobActive = false, playerPlaybackState = Player.STATE_BUFFERING)
        assertTrue(owner.isBuffering())
        port.transport = port.transport.copy(initialized = false)
        assertFalse(owner.isBuffering())
    }

    @Test
    fun `auto advance guard covers ended job buffering and ready positions`() = runTest {
        var now = 10_000L
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { now }
        owner.markAutoTrackAdvance()
        port.transport = port.transport.copy(resumeRequested = true, playJobActive = true)
        assertTrue(owner.shouldIgnoreExternalPause("audio_focus"))
        port.transport = port.transport.copy(playJobActive = false, playerPlaybackState = Player.STATE_ENDED)
        assertTrue(owner.shouldIgnoreExternalPause("audio_focus"))
        port.transport = port.transport.copy(playerPlaybackState = Player.STATE_BUFFERING, playWhenReady = true)
        assertTrue(owner.shouldIgnoreExternalPause("audio_focus"))
        port.transport = port.transport.copy(playerPlaybackState = Player.STATE_READY, currentPositionMs = 1_501L)
        assertFalse(owner.shouldIgnoreExternalPause("audio_focus"))
        port.transport = port.transport.copy(currentPositionMs = 1_500L)
        assertTrue(owner.shouldIgnoreExternalPause("audio_focus"))
        port.transport = port.transport.copy(playWhenReady = false)
        assertFalse(owner.shouldIgnoreExternalPause("audio_focus"))
        port.transport = port.transport.copy(resumeRequested = false)
        assertFalse(owner.shouldIgnoreExternalPause("audio_focus"))
        now += 2_001L
        port.transport = port.transport.copy(resumeRequested = true, playJobActive = true)
        assertFalse(owner.shouldIgnoreExternalPause("audio_focus"))
    }

    @Test
    fun `USB native activity and fallback reasons preserve focus bypass decisions`() = runTest {
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { 0L }
        port.transport = port.transport.copy(resumeRequested = true)
        port.usb = usb()
        assertFalse(owner.isUsbPlaybackActiveForForegroundService())
        listOf(
            port.usb.copy(nativeStreaming = true),
            port.usb.copy(nativeOpened = true),
            port.usb.copy(effectiveNativePath = true),
            port.usb.copy(requestedNativePath = true)
        ).forEach { state ->
            port.usb = state
            assertTrue(owner.isUsbPlaybackActiveForForegroundService())
        }
        port.usb = usb().copy(enabled = false, nativeStreaming = true)
        assertFalse(owner.isUsbPlaybackActiveForForegroundService())
        port.usb = usb().copy(nativeSource = "fallback")
        listOf(
            "native_open_deferred", "native_reopen_cooling_down", "transport_failed",
            "start_failed", "play_failed"
        ).forEach { reason ->
            port.usb = port.usb.copy(fallbackReason = reason)
            assertTrue(owner.shouldBypassPlatformFocus())
        }
        port.usb = port.usb.copy(fallbackReason = "permanent_failure")
        assertFalse(owner.shouldBypassPlatformFocus())
        port.usb = port.usb.copy(nativeTransitioning = true)
        assertTrue(owner.shouldBypassPlatformFocus())
        port.usb = port.usb.copy(nativeTransitioning = false, nativeOpened = true, nativeSource = "player_pcm")
        assertTrue(owner.shouldBypassPlatformFocus())
    }

    @Test
    fun `native stability rejects mixed fallback incomplete and unhealthy streams`() = runTest {
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { 0L }
        val healthy = usb().copy(
            effectiveNativePath = true, sinkPlaying = true, nativeOpened = true,
            nativeStreaming = true, nativeTransportHealthy = true
        )
        port.usb = healthy
        assertTrue(owner.isUsbNativePlaybackStable())
        assertTrue(owner.shouldUseUsbFocusGuard())
        listOf(
            healthy.copy(enabled = false), healthy.copy(mixedPlayback = true),
            healthy.copy(effectiveNativePath = false), healthy.copy(sinkPlaying = false),
            healthy.copy(fallbackReason = "transport_failed"), healthy.copy(nativeSource = "other"),
            healthy.copy(nativeOpened = false), healthy.copy(nativeStreaming = false),
            healthy.copy(nativeTransitioning = true), healthy.copy(nativeTransportHealthy = false)
        ).forEach { state ->
            port.usb = state
            assertFalse(owner.isUsbNativePlaybackStable())
        }
        port.usb = healthy.copy(nativeSource = "other")
        assertFalse(owner.shouldUseUsbFocusGuard())
        listOf(
            healthy.copy(enabled = false), healthy.copy(mixedPlayback = true),
            healthy.copy(effectiveNativePath = false), healthy.copy(sinkPlaying = false),
            healthy.copy(nativeStreaming = false)
        ).forEach { state ->
            port.usb = state
            assertFalse(owner.shouldUseUsbFocusGuard())
        }
    }

    @Test
    fun `USB focus loss ignores idle transport and dispatches from worker thread`() = runTest {
        val port = RecordingPort()
        val owner = PlaybackTransportOwner(backgroundScope, port) { 0L }
        port.usb = usb()
        owner.pauseForUsbFocusLoss(-1)
        assertTrue(port.focusLossPauses.isEmpty())
        port.transport = port.transport.copy(resumeRequested = true, initialized = false)
        owner.pauseForUsbFocusLoss(-1)
        assertTrue(port.focusLossPauses.isEmpty())
        port.transport = port.transport.copy(initialized = true)
        port.mainThread = false
        owner.pauseForUsbFocusLoss(-2)
        port.mainThread = true
        runCurrent()
        assertEquals(listOf(-2), port.focusLossPauses)
    }

    private fun usb(): UsbFocusSnapshot = UsbFocusSnapshot(
        enabled = true,
        mixedPlayback = false,
        effectiveNativePath = false,
        requestedNativePath = false,
        sinkPlaying = false,
        fallbackReason = null,
        nativeSource = "player_pcm",
        nativeOpened = false,
        nativeStreaming = false,
        nativeTransitioning = false,
        nativeTransportHealthy = false,
        openGateReason = null
    )

    private class RecordingPort : PlaybackTransportPort {
        var mainThread = true
        var ensureCalls = 0
        var transport = PlaybackTransportSnapshot(
            initialized = true,
            hasCurrentSong = true,
            resumeRequested = false,
            playJobActive = false,
            pendingPauseJobActive = false,
            playWhenReady = false,
            isPlaying = false,
            playerPlaybackState = Player.STATE_IDLE,
            currentPositionMs = 0L,
            restoredPlaybackCanAutoResume = false,
            playerInitialized = true
        )
        var usb = UsbFocusSnapshot(
            enabled = false,
            mixedPlayback = false,
            effectiveNativePath = false,
            requestedNativePath = false,
            sinkPlaying = false,
            fallbackReason = null,
            nativeSource = "none",
            nativeOpened = false,
            nativeStreaming = false,
            nativeTransitioning = false,
            nativeTransportHealthy = false,
            openGateReason = null
        )
        val audioFocusEnabled = mutableListOf<Boolean>()
        val foregroundGuardEnabled = mutableListOf<Boolean>()
        val focusLossPauses = mutableListOf<Int>()
        override fun ensureInitialized() { ensureCalls++ }
        override fun transportSnapshot(includePosition: Boolean, includeRestore: Boolean): PlaybackTransportSnapshot = transport
        override fun usbFocusSnapshot(includeHealth: Boolean, includeOpenGate: Boolean): UsbFocusSnapshot = usb
        override fun isMainThread(): Boolean = mainThread
        override fun logFocusPolicy(usbEnabled: Boolean, mixedPlayback: Boolean, handleFocus: Boolean) = Unit
        override fun setAudioAttributes(handleFocus: Boolean) { audioFocusEnabled += handleFocus }
        override fun updateForegroundFocusGuard(
            enabled: Boolean,
            usbEnabled: Boolean,
            mixedPlayback: Boolean,
            transportActive: Boolean
        ) { foregroundGuardEnabled += enabled }
        override fun pauseForUsbFocusLoss(change: Int) { focusLossPauses += change }
        override fun setWakeMode(wakeMode: Int) = Unit
    }
}
