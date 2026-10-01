package moe.ouom.neriplayer.core.player.usb.route

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbSystemAudioRouteOwnerTest {
    @Test
    fun `disable waits for native close before rebuilding system sink`() = runTest {
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val port = RecordingPort()
        port.closeInFlight = 1
        val owner = createOwner(transition, port)

        owner.release(UsbSystemAudioReleaseRequest("usb_exclusive_disabled", true, false, 0L, false))
        runCurrent()
        assertTrue(transition.systemAudioReleaseInProgress)
        assertTrue(port.events.contains("forceStop"))
        assertFalse(port.events.contains("reset"))

        port.closeInFlight = 0
        advanceTimeBy(700L)
        runCurrent()
        assertTrue(port.events.indexOf("forceStop") < port.events.indexOf("reset"))
        assertFalse(transition.systemAudioReleaseInProgress)
    }

    @Test
    fun `new generation prevents stale release from clearing preferred route`() = runTest {
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val port = RecordingPort()
        val owner = createOwner(transition, port)
        owner.release(UsbSystemAudioReleaseRequest("usb_exclusive_disabled", true, false, 0L, false))
        transition.advanceGeneration()
        runCurrent()

        assertFalse(port.events.contains("clearPreferred"))
        assertFalse(port.events.contains("reset"))
        assertFalse(transition.systemAudioReleaseInProgress)
    }

    @Test
    fun `fallback watchdog retries stalled playback only once`() = runTest {
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val port = RecordingPort()
        port.current = port.current.copy(playWhenReady = true, playbackState = Player.STATE_READY)
        val owner = createOwner(transition, port)

        owner.resetSystemAudio("fallback", 0L, true)
        advanceTimeBy(1_600L)
        runCurrent()
        advanceTimeBy(1_600L)
        runCurrent()

        assertEquals(2, port.events.count { it == "reset" })
    }

    @Test
    fun `non disabling release restores focus and schedules a sink rebuild`() = runTest {
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val port = RecordingPort()
        val owner = createOwner(transition, port)

        owner.release(UsbSystemAudioReleaseRequest("transport_failure", true, true, 0L, true))
        runCurrent()

        assertTrue(port.events.contains("clearPreferred"))
        assertTrue(port.events.contains("restoreFocus"))
        assertTrue(port.events.contains("restorePlayback"))
        assertTrue(port.events.contains("scheduleSink"))
        assertFalse(transition.systemAudioReleaseInProgress)
    }

    @Test
    fun `release without initialized player stops native resources without reset`() = runTest {
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val port = RecordingPort()
        port.current = port.current.copy(playerInitialized = false)
        val owner = createOwner(transition, port)

        owner.release(UsbSystemAudioReleaseRequest("usb_exclusive_disabled", true, false, 0L, false))
        runCurrent()

        assertTrue(port.events.contains("forceStop"))
        assertFalse(port.events.contains("reset"))
        assertFalse(transition.systemAudioReleaseInProgress)
    }

    @Test
    fun `system reset without media only resumes an interrupted request`() = runTest {
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val port = RecordingPort()
        port.current = port.current.copy(mediaItemCount = 0, hasMediaItem = false)
        val owner = createOwner(transition, port)

        owner.resetSystemAudio("no_media", 0L, true)
        owner.resetSystemAudio("no_media", 1L, true)

        assertEquals(1, port.events.count { it == "resumeInterrupted" })
        assertFalse(port.events.contains("reset"))
    }

    @Test
    fun `native close timeout resets system audio without auto resume`() = runTest {
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val port = RecordingPort()
        port.closeInFlight = 1
        val owner = createOwner(transition, port)

        owner.release(UsbSystemAudioReleaseRequest("usb_exclusive_disabled", true, false, 0L, false))
        advanceTimeBy(4_100L)
        runCurrent()

        assertTrue(port.events.contains("reset"))
        assertFalse(transition.systemAudioReleaseInProgress)
    }

    private fun kotlinx.coroutines.test.TestScope.createOwner(
        transition: UsbRouteTransitionOwner,
        port: RecordingPort
    ): UsbSystemAudioRouteOwner {
        val sink = UsbSinkRouteOwner(backgroundScope, SinkPort(), { testScheduler.currentTime })
        return UsbSystemAudioRouteOwner(transition, sink, port) { testScheduler.currentTime }
    }

    private class SinkPort : UsbSinkRoutePort {
        override fun routeGeneration(): Long = 0L
        override fun snapshot(): UsbSinkRouteSnapshot = UsbSinkRouteSnapshot(
            0L, false, true, false, true, true, 1, 0, 0L, false
        )
        override fun hasHealthyNativePlayerSession(): Boolean = false
        override fun stopCurrentSink(reason: String, resumePlayback: Boolean): Boolean = true
        override fun prepareSink(mediaItemIndex: Int, positionMs: Long, resumePlayback: Boolean, reason: String): Boolean = true
        override fun finishToggleTransition(preparingReason: String) = Unit
        override fun clearForcedSystemFallback() = Unit
    }

    private class RecordingPort : UsbSystemAudioRoutePort {
        var current = UsbSystemAudioSnapshot(false, true, 1, true, 0, 0L, false, false, Player.STATE_IDLE)
        var closeInFlight = 0
        val events = mutableListOf<String>()
        override fun snapshot(): UsbSystemAudioSnapshot = current
        override fun interruptedPositionMs(): Long? = null
        override fun cancelRecovery(reason: String) { events += "cancelRecovery" }
        override fun cancelSinkReconfiguration() { events += "cancelSink" }
        override fun prepareForDisable(reason: String) { events += "prepareDisable" }
        override fun deferNativeOpen(reason: String, delayMs: Long) { events += "deferOpen" }
        override fun updatePathAfterRelease(reason: String, disabling: Boolean, playbackShouldContinue: Boolean) {
            events += "updatePath"
        }
        override fun stopNativeSessions(reason: String, disabling: Boolean) { events += "forceStop" }
        override fun releaseSystemSound(reason: String) { events += "releaseSound" }
        override fun releaseAudioFocus(reason: String) { events += "releaseFocus" }
        override fun nativeCloseInFlightCount(): Int = closeInFlight
        override fun clearPreferredDevice(reason: String) { events += "clearPreferred" }
        override fun clearReleaseMute(reason: String) { events += "clearMute" }
        override fun restoreAudioFocus() { events += "restoreFocus" }
        override fun restoreTransientPlayback(reason: String) { events += "restorePlayback" }
        override fun scheduleSinkReconfiguration(reason: String) { events += "scheduleSink" }
        override fun resumeInterruptedPlayback(reason: String) { events += "resumeInterrupted" }
        override fun resetSystemAudioPlayer(mediaItemIndex: Int, positionMs: Long, resumePlayback: Boolean, reason: String): Boolean {
            events += "reset"
            return true
        }
        override fun clearForcedSystemFallback() { events += "clearFallback" }
        override fun clearInterruptedIntent(reason: String) { events += "clearIntent" }
        override fun finishToggle(reason: String) { events += "finishToggle" }
    }
}
