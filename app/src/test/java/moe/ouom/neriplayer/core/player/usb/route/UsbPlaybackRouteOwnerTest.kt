package moe.ouom.neriplayer.core.player.usb.route

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.settings.usb.UsbExclusivePreferences
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class UsbPlaybackRouteOwnerTest {
    @Test
    fun `active preference change is owned as deferred sink rebuild`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(playbackActive = true)

        fixture.owner.changePreferences(UsbExclusivePreferences(selectedDeviceKey = "new-device"))

        assertTrue(fixture.sink.pendingPreferenceReconfiguration)
        assertEquals(listOf("activeBuffer:preferences_changed"), fixture.port.events)
        assertEquals("new-device", fixture.port.currentPreferencesValue.selectedDeviceKey)
    }

    @Test
    fun `native failure clears transition and stops playback once`() = runTest {
        val fixture = fixture()
        fixture.transition.beginSettingToggle(true, true)

        fixture.owner.stopAfterNativeFailure("native_transport_failed")

        assertFalse(fixture.transition.toggleActive)
        assertEquals(1, fixture.port.events.count { it == "stop:native_transport_failed" })
        assertTrue(fixture.port.events.contains("preparing:false:native_failure:native_transport_failed"))
        assertTrue(fixture.port.events.contains("cancelLiveness"))
    }

    @Test
    fun `transient native open gate does not stop playback`() = runTest {
        val fixture = fixture()

        fixture.owner.stopAfterNativeFailure("native_transition_in_flight")

        assertTrue(fixture.port.events.isEmpty())
    }

    @Test
    fun `stale policy coroutine cannot apply preferred route`() = runTest {
        val fixture = fixture()
        fixture.owner.applyPolicy()
        fixture.transition.advanceGeneration()
        runCurrent()

        assertTrue(fixture.port.events.contains("enabledPolicy"))
        assertFalse(fixture.port.events.contains("preferredDevice"))
    }

    @Test
    fun `release cancels route state before native resources`() = runTest {
        val fixture = fixture()
        fixture.transition.beginSettingToggle(true, true)
        fixture.sink.markPendingPreferenceReconfiguration(true)

        fixture.owner.release()

        assertFalse(fixture.transition.toggleActive)
        assertEquals(listOf("preparing:false:player_release", "cancelLiveness", "releaseNative"), fixture.port.events)
    }

    @Test
    fun `unhealthy system route schedules a fresh native sink generation`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(playbackActive = true)

        assertTrue(fixture.owner.recoverIfUnhealthy("foreground"))

        assertEquals(1L, fixture.transition.generation)
        assertTrue(fixture.native.events.contains("fresh:usb_recovery:foreground"))
        assertTrue(fixture.port.events.contains("preparing:true:usb_recovery:foreground"))
    }

    @Test
    fun `manual playback rebuilds non reusable native route`() = runTest {
        val fixture = fixture()

        assertTrue(fixture.owner.prepareManualPlayback("user_play"))

        assertEquals(1L, fixture.transition.generation)
        assertTrue(fixture.native.events.contains("clearBlock:manual_play:user_play"))
        assertTrue(fixture.port.events.contains("rebuild:user_play"))
    }

    @Test
    fun `first completion timeout is bounded to one attempt`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(playbackActive = true)

        assertTrue(fixture.owner.recoverAfterTransferFailure("event_loop_first_completion_timeout", "idle"))
        assertFalse(fixture.owner.recoverAfterTransferFailure("event_loop_first_completion_timeout", "idle"))
        assertEquals(1, fixture.transition.recoveryAttempts)
    }

    @Test
    fun `enabling USB without media finishes toggle immediately`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(enabled = false, hasMediaItem = false, mediaItemCount = 0)

        fixture.owner.changeSetting(true)

        assertEquals(2L, fixture.transition.generation)
        assertFalse(fixture.transition.toggleActive)
        assertTrue(fixture.port.events.contains("preparing:false:usb_toggle_no_media"))
        assertTrue(fixture.native.events.contains("requested:true"))
    }

    @Test
    fun `disabling USB preserves media position for system reset`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(mediaItemCount = 2, mediaItemIndex = 1, positionMs = 86L)

        fixture.owner.changeSetting(false)

        assertEquals(1L, fixture.transition.generation)
        assertTrue(fixture.native.events.contains("requested:false"))
        assertTrue(fixture.native.events.contains("stopPcm:apply_policy_disabled"))
    }

    @Test
    fun `active automatic retry defers native switch until playback stops`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(playbackActive = true)

        fixture.owner.retry("usb_device_changed")

        assertTrue(fixture.sink.pendingPreferenceReconfiguration)
        assertTrue(fixture.native.events.contains("clearBlock:retry:usb_device_changed"))
    }

    @Test
    fun `inactive transport failure does not stop playback`() = runTest {
        val fixture = fixture()

        fixture.owner.scheduleTransportRecovery("usb_device_detached")

        assertFalse(fixture.port.events.any { it.startsWith("stop:") })
    }

    @Test
    fun `native activation clears bounded recovery attempts`() = runTest {
        val fixture = fixture()
        fixture.transition.claimRecoveryAttempt(1)
        fixture.port.current = fixture.port.current.copy(mixedPlaybackEnabled = true)

        fixture.owner.markNativePathActive("permission_granted")

        assertEquals(0, fixture.transition.recoveryAttempts)
        assertTrue(fixture.native.events.contains("clearFallback"))
    }

    @Test
    fun `manual playback without selected USB route is blocked`() = runTest {
        val fixture = fixture()
        fixture.port.manualAvailable = false

        assertFalse(fixture.owner.prepareManualPlayback("user_play"))

        assertTrue(fixture.port.events.contains("block:user_play"))
        assertEquals(0L, fixture.transition.generation)
    }

    @Test
    fun `manual open gate resumes after native gate clears`() = runTest {
        val fixture = fixture()
        fixture.native.gate = "native_transition_in_flight"
        fixture.port.current = fixture.port.current.copy(resumeRequested = true)

        assertFalse(fixture.owner.prepareManualPlayback("user_play"))
        fixture.native.gate = null
        advanceTimeBy(100L)
        runCurrent()

        assertTrue(fixture.port.events.contains("rebuild:open_gate_retry:user_play"))
        assertTrue(fixture.port.events.contains("resume:user_play"))
    }

    @Test
    fun `unchanged enabled setting clears only the disabled fallback`() = runTest {
        val fixture = fixture()
        fixture.native.forcedReason = "usb_exclusive_disabled"

        fixture.owner.changeSetting(true)

        assertEquals(0L, fixture.transition.generation)
        assertTrue(fixture.native.events.contains("requested:true"))
        assertTrue(fixture.native.events.contains("clearFallback"))
        assertTrue(fixture.port.events.contains("enabledPolicy"))
    }

    @Test
    fun `inactive preference change retries while unchanged preference remains idle`() = runTest {
        val fixture = fixture()
        val next = fixture.port.currentPreferencesValue.copy(selectedDeviceKey = "next")

        fixture.owner.changePreferences(fixture.port.currentPreferencesValue)
        assertEquals(0L, fixture.transition.generation)
        fixture.owner.changePreferences(next)

        assertEquals(1L, fixture.transition.generation)
        assertTrue(fixture.native.events.contains("clearFallback"))
        assertTrue(fixture.port.events.contains("enabledPolicy"))
    }

    @Test
    fun `disabled route saves preferences without applying USB policy`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(enabled = false)

        fixture.owner.changePreferences(UsbExclusivePreferences(selectedDeviceKey = "next"))

        assertEquals(0L, fixture.transition.generation)
        assertTrue(fixture.port.events.isEmpty())
    }

    @Test
    fun `transfer failure recovers only first completion timeout in exclusive mode`() = runTest {
        val fixture = fixture()
        assertFalse(fixture.owner.recoverAfterTransferFailure("idle", "idle"))
        assertFalse(fixture.owner.recoverAfterTransferFailure("native_transport_failed", "LIBUSB_ERROR_IO"))
        fixture.port.current = fixture.port.current.copy(mixedPlaybackEnabled = true)
        assertFalse(fixture.owner.recoverAfterTransferFailure("event_loop_first_completion_timeout", "idle"))
        fixture.port.current = fixture.port.current.copy(mixedPlaybackEnabled = false)
        assertTrue(fixture.owner.recoverAfterTransferFailure("idle", "event_loop_first_completion_timeout"))
    }

    @Test
    fun `expired manual open gate stops pending USB playback`() = runTest {
        val fixture = fixture()
        fixture.native.gate = "native_transition_in_flight"
        fixture.port.current = fixture.port.current.copy(resumeRequested = true)

        assertFalse(fixture.owner.prepareManualPlayback("user_play"))
        advanceTimeBy(8_100L)
        runCurrent()

        assertTrue(fixture.port.events.any { it.startsWith("preparing:false:manual_play_gate_timeout") })
    }

    @Test
    fun `canceled manual open gate does not resume USB playback`() = runTest {
        val fixture = fixture()
        fixture.native.gate = "native_transition_in_flight"
        fixture.port.current = fixture.port.current.copy(resumeRequested = true)

        assertFalse(fixture.owner.prepareManualPlayback("user_play"))
        fixture.port.current = fixture.port.current.copy(resumeRequested = false)
        advanceTimeBy(100L)
        runCurrent()

        assertTrue(fixture.port.events.contains("preparing:false:manual_play_cancelled:user_play"))
        assertFalse(fixture.port.events.contains("resume:user_play"))
    }

    @Test
    fun `recovery declines a transitioning native session or an open gate`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(playbackActive = true)
        fixture.native.nativeState = UsbExclusiveNativeState(transitioning = true)
        assertFalse(fixture.owner.recoverIfUnhealthy("foreground"))

        fixture.native.nativeState = UsbExclusiveNativeState()
        fixture.native.gate = "native_transition_in_flight"
        assertFalse(fixture.owner.recoverIfUnhealthy("foreground"))
        assertEquals(0L, fixture.transition.generation)
    }

    @Test
    fun `healthy native playback does not schedule recovery`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(playbackActive = true)
        fixture.native.pathState = UsbExclusiveAudioPathState(
            effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB
        )
        fixture.native.nativeState = UsbExclusiveNativeState(source = "player_pcm", streaming = true)

        assertFalse(fixture.owner.recoverIfUnhealthy("foreground"))
        assertEquals(0L, fixture.transition.generation)
    }

    @Test
    fun `manual retry can rebuild while active but disabled route cannot retry`() = runTest {
        val fixture = fixture()
        fixture.port.current = fixture.port.current.copy(playbackActive = true)
        fixture.owner.retry("usb_manual_playback_start")
        assertEquals(1L, fixture.transition.generation)

        fixture.port.current = fixture.port.current.copy(enabled = false)
        fixture.owner.retry("usb_device_changed")
        assertEquals(1L, fixture.transition.generation)
    }

    private fun TestScope.fixture(): Fixture {
        val port = RecordingPlaybackPort()
        val transition = UsbRouteTransitionOwner(backgroundScope, {})
        val sink = UsbSinkRouteOwner(backgroundScope, object : UsbSinkRoutePort {
            override fun routeGeneration(): Long = transition.generation
            override fun snapshot(): UsbSinkRouteSnapshot = UsbSinkRouteSnapshot(
                transition.generation, true, true, false, true, true, 1, 0, 0L, false
            )
            override fun hasHealthyNativePlayerSession(): Boolean = false
            override fun stopCurrentSink(reason: String, resumePlayback: Boolean): Boolean = true
            override fun prepareSink(mediaItemIndex: Int, positionMs: Long, resumePlayback: Boolean, reason: String): Boolean = true
            override fun finishToggleTransition(preparingReason: String) = Unit
            override fun clearForcedSystemFallback() = Unit
        }, { testScheduler.currentTime })
        val systemPort = mock(UsbSystemAudioRoutePort::class.java)
        `when`(systemPort.snapshot()).thenReturn(
            UsbSystemAudioSnapshot(false, false, 0, false, 0, 0L, false, false, 1)
        )
        val system = UsbSystemAudioRouteOwner(transition, sink, systemPort) { testScheduler.currentTime }
        val native = RecordingNativePort()
        return Fixture(UsbPlaybackRouteOwner(backgroundScope, transition, sink, system, port, native,
            { testScheduler.currentTime }),
            port, transition, sink, native)
    }

    private data class Fixture(
        val owner: UsbPlaybackRouteOwner,
        val port: RecordingPlaybackPort,
        val transition: UsbRouteTransitionOwner,
        val sink: UsbSinkRouteOwner,
        val native: RecordingNativePort
    )

    private class RecordingNativePort : UsbPlaybackNativeRoutePort {
        var pathState = UsbExclusiveAudioPathState()
        var nativeState = UsbExclusiveNativeState()
        var gate: String? = null
        var forcedReason: String? = null
        val events = mutableListOf<String>()
        override fun path(): UsbExclusiveAudioPathState = pathState
        override fun native(): UsbExclusiveNativeState = nativeState
        override fun forcedFallbackReason(): String? = forcedReason
        override fun updateRequested(enabled: Boolean) { events += "requested:$enabled" }
        override fun clearForcedFallback() { events += "clearFallback" }
        override fun clearRecoverableOpenBlock(reason: String) { events += "clearBlock:$reason" }
        override fun requireFreshOpen(reason: String) { events += "fresh:$reason" }
        override fun openGateReason(): String? = gate
        override fun stopPlayerPcm(reason: String) { events += "stopPcm:$reason" }
        override fun activateSoundGuard(application: Application, reason: String) { events += "soundGuard:$reason" }
    }

    private class RecordingPlaybackPort : UsbPlaybackRoutePort {
        var current = UsbPlaybackRouteSnapshot(true, false, true, false, true, 1, 0, 0L, false)
        var currentPreferencesValue = UsbExclusivePreferences()
        var manualAvailable = true
        val events = mutableListOf<String>()
        override fun snapshot(): UsbPlaybackRouteSnapshot = current
        override fun isMainThread(): Boolean = true
        override fun setPlaybackEnabled(enabled: Boolean) { current = current.copy(enabled = enabled) }
        override fun currentPreferences(): UsbExclusivePreferences = currentPreferencesValue
        override fun setPreferences(preferences: UsbExclusivePreferences) { currentPreferencesValue = preferences }
        override fun markPreparing(preparing: Boolean, reason: String) { events += "preparing:$preparing:$reason" }
        override fun pauseForToggle(enabled: Boolean) = Unit
        override fun applyAudioFocus() = Unit
        override fun applyOffloadPreferences() = Unit
        override fun prepareEnabledPolicy() { events += "enabledPolicy" }
        override fun logPolicyBeforeSet() = Unit
        override fun applyPreferredAudioDevice() { events += "preferredDevice" }
        override fun scheduleSoundConfigApply() = Unit
        override fun applyActiveBuffer(reason: String) { events += "activeBuffer:$reason" }
        override fun cancelLivenessJobs() { events += "cancelLiveness" }
        override fun stopAfterNativeFailure(reason: String) { events += "stop:$reason" }
        override fun manualRouteAvailable(): Boolean = manualAvailable
        override fun blockManualPlayback(reason: String) { events += "block:$reason" }
        override fun rebuildManualPlayer(reason: String): Boolean {
            events += "rebuild:$reason"
            return true
        }
        override fun resumeAfterOpenGate(reason: String) { events += "resume:$reason" }
        override fun releaseSystemSound(reason: String) = Unit
        override fun releaseNativeResources() { events += "releaseNative" }
        override fun application(): Application = error("unused")
    }
}
