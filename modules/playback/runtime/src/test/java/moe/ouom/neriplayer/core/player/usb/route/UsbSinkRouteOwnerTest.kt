package moe.ouom.neriplayer.core.player.usb.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbSinkRouteOwnerTest {
    @Test
    fun `latest request replaces delayed sink rebuild`() = runTest {
        val port = RecordingPort()
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.schedule("usb_policy_changed", allowWhilePlaybackActive = true)
        runCurrent()
        owner.schedule("usb_device_changed", allowWhilePlaybackActive = true)
        runCurrent()
        advanceTimeBy(3_200L)
        runCurrent()

        assertEquals(listOf("usb_device_changed"), port.stoppedReasons)
        assertEquals(listOf("usb_device_changed"), port.preparedReasons)
        assertFalse(owner.snapshot().pending)
    }

    @Test
    fun `new route generation invalidates delayed rebuild`() = runTest {
        val port = RecordingPort()
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.schedule("usb_policy_changed", allowWhilePlaybackActive = true)
        runCurrent()
        port.current = port.current.copy(routeGeneration = 2L)
        advanceTimeBy(2_500L)
        runCurrent()

        assertTrue(port.stoppedReasons.isEmpty())
        assertTrue(port.preparedReasons.isEmpty())
    }

    @Test
    fun `active foreground playback defers and background playback drops request`() = runTest {
        val port = RecordingPort()
        port.current = port.current.copy(playbackActive = true)
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.schedule("usb_preference_changed")
        runCurrent()
        assertTrue(owner.pendingPreferenceReconfiguration)
        assertTrue(port.stoppedReasons.isEmpty())

        port.current = port.current.copy(appInForeground = false)
        owner.schedule("usb_preference_changed")
        runCurrent()
        assertFalse(owner.pendingPreferenceReconfiguration)
        assertTrue(port.stoppedReasons.isEmpty())
    }

    @Test
    fun `deferred request waits until playback stops then schedules one rebuild`() = runTest {
        val port = RecordingPort()
        port.current = port.current.copy(playbackActive = true)
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.deferUntilPlaybackStops("usb_output_preferences_changed")
        runCurrent()
        advanceTimeBy(800L)
        runCurrent()
        assertTrue(port.preparedReasons.isEmpty())

        port.current = port.current.copy(playbackActive = false)
        advanceTimeBy(800L)
        runCurrent()
        advanceTimeBy(2_500L)
        runCurrent()

        assertEquals(1, port.fallbackClears)
        assertEquals(listOf("deferred:usb_output_preferences_changed"), port.preparedReasons)
        assertFalse(owner.pendingPreferenceReconfiguration)
    }

    @Test
    fun `healthy native route completes toggle without stopping player`() = runTest {
        val port = RecordingPort()
        port.nativeSessionHealthy = true
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.schedule("usb_permission_granted", allowWhilePlaybackActive = true)
        advanceTimeBy(2_500L)
        runCurrent()

        assertTrue(port.stoppedReasons.isEmpty())
        assertEquals(listOf("native_route_already_ready:usb_permission_granted"), port.toggleCompletions)
    }

    @Test
    fun `ordinary route rebuild stops and prepares without system release delay`() = runTest {
        val port = RecordingPort()
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.schedule("ordinary", bypassCooldown = true)
        advanceTimeBy(120L)
        runCurrent()

        assertEquals(listOf("ordinary"), port.stoppedReasons)
        assertEquals(listOf("ordinary"), port.preparedReasons)
        assertEquals(listOf("usb_reconfigure_success"), port.toggleCompletions)
    }

    @Test
    fun `missing media or zero item count cannot stop a sink`() = runTest {
        val port = RecordingPort()
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })
        port.current = port.current.copy(hasMediaItem = false)
        owner.schedule("ordinary", bypassCooldown = true)
        advanceTimeBy(120L)
        runCurrent()
        assertTrue(port.stoppedReasons.isEmpty())

        port.current = port.current.copy(hasMediaItem = true, mediaItemCount = 0)
        owner.schedule("ordinary", bypassCooldown = true)
        advanceTimeBy(120L)
        runCurrent()
        assertTrue(port.stoppedReasons.isEmpty())
    }

    @Test
    fun `foreground loss cancels deferred switch without clearing system fallback`() = runTest {
        val port = RecordingPort()
        port.current = port.current.copy(playbackActive = true, appInForeground = false)
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.deferUntilPlaybackStops("preference_changed")
        runCurrent()

        assertFalse(owner.pendingPreferenceReconfiguration)
        assertEquals(0, port.fallbackClears)
        assertTrue(port.stoppedReasons.isEmpty())
    }

    @Test
    fun `failed sink preparation finishes toggle without recording a healthy route`() = runTest {
        val port = RecordingPort()
        port.prepareSuccessful = false
        val owner = UsbSinkRouteOwner(backgroundScope, port, { testScheduler.currentTime })

        owner.schedule("ordinary", bypassCooldown = true)
        advanceTimeBy(120L)
        runCurrent()

        assertEquals(listOf("usb_reconfigure_failed"), port.toggleCompletions)
        assertEquals(0L, owner.lastReconfiguredAtMs)
    }

    private class RecordingPort : UsbSinkRoutePort {
        var current = UsbSinkRouteSnapshot(
            routeGeneration = 1L,
            enabled = true,
            appInForeground = true,
            playbackActive = false,
            playerInitialized = true,
            hasMediaItem = true,
            mediaItemCount = 2,
            mediaItemIndex = 1,
            positionMs = 42L,
            resumePlayback = true
        )
        var nativeSessionHealthy = false
        var prepareSuccessful = true
        val stoppedReasons = mutableListOf<String>()
        val preparedReasons = mutableListOf<String>()
        val toggleCompletions = mutableListOf<String>()
        var fallbackClears = 0

        override fun snapshot(): UsbSinkRouteSnapshot = current
        override fun routeGeneration(): Long = current.routeGeneration
        override fun hasHealthyNativePlayerSession(): Boolean = nativeSessionHealthy
        override fun stopCurrentSink(reason: String, resumePlayback: Boolean): Boolean {
            stoppedReasons += reason
            return true
        }

        override fun prepareSink(
            mediaItemIndex: Int,
            positionMs: Long,
            resumePlayback: Boolean,
            reason: String
        ): Boolean {
            assertEquals(1, mediaItemIndex)
            assertEquals(42L, positionMs)
            assertTrue(resumePlayback)
            preparedReasons += reason
            return prepareSuccessful
        }

        override fun finishToggleTransition(preparingReason: String) {
            toggleCompletions += preparingReason
        }

        override fun clearForcedSystemFallback() {
            fallbackClears += 1
        }
    }
}
