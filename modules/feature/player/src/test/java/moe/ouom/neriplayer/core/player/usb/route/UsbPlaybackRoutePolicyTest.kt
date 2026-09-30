package moe.ouom.neriplayer.core.player.usb.route

import kotlinx.coroutines.Job
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusiveBufferProfile
import moe.ouom.neriplayer.data.model.settings.usb.UsbExclusivePreferences
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPlaybackRoutePolicyTest {
    @Test
    fun `only route affecting preferences rebuild the sink`() {
        val previous = UsbExclusivePreferences()
        assertFalse(previous.requiresRouteReconfiguration(previous.copy(bufferProfile = UsbExclusiveBufferProfile.LOW_LATENCY)))
        assertTrue(previous.requiresRouteReconfiguration(previous.copy(selectedDeviceKey = "new-device")))
    }

    @Test
    fun `activation excludes disable fallback and failures`() {
        assertTrue("usb_device_attached".isActivationReason())
        assertTrue("native_permission_granted".isActivationReason())
        assertFalse("usb_exclusive_disabled".isActivationReason())
        assertFalse("native_transport_failed".isActivationReason())
        assertFalse("system_route_ready".isActivationReason())
        assertTrue("usb_manual_playback_start".isUserDrivenActivation())
        assertFalse("usb_foreground_recovery".isUserDrivenActivation())
    }

    @Test
    fun `permanent fallback and transient gate never auto recover`() {
        assertFalse((null as String?).isRecoverableFallback())
        assertFalse("native_transition_in_flight".isRecoverableFallback())
        assertFalse("usb_device_detached".isRecoverableFallback())
        assertFalse("No permitted device".isRecoverableFallback())
        assertFalse("sample_rate_unsupported:96000".isRecoverableFallback())
        assertTrue("SAMPLE_RATE_UNSUPPORTED:96000".isRecoverableFallback())
        assertTrue("native_transport_failed".isRecoverableFallback())
    }

    @Test
    fun `transfer failures distinguish retryable errors from detached devices`() {
        assertTrue("event_loop_first_completion_timeout".isFirstCompletionTimeout())
        assertTrue("native_transport_failed".isRecoverableTransferFailure())
        assertFalse("LIBUSB_ERROR_NO_DEVICE".isRecoverableTransferFailure())
        assertFalse("permission denied".isRecoverableTransferFailure())
        assertFalse("playback_idle".isRecoverableTransferFailure())
    }

    @Test
    fun `recovery requires an active exclusive route unless forced`() {
        val route = UsbPlaybackRouteSnapshot(true, false, true, false, true, 1, 0, 0L, true)
        assertFalse(route.allowsRecovery(false))
        assertTrue(route.allowsRecovery(true))
        assertFalse(route.copy(mixedPlaybackEnabled = true).allowsRecovery(true))
        assertFalse(route.copy(playerInitialized = false).allowsRecovery(true))
        assertFalse(route.copy(enabled = false).allowsRecovery(true))
        assertTrue(route.copy(playbackActive = true).allowsRecovery(false))
        assertTrue(route.canRecoverTransport())
        assertFalse(route.copy(enabled = false).canRecoverTransport())
        assertFalse(route.copy(playerInitialized = false).canRecoverTransport())
    }

    @Test
    fun `recovery distinguishes intentional fallback from stopped native stream`() {
        val native = UsbExclusiveNativeState(source = "player_pcm", streaming = true)
        val system = UsbExclusiveAudioPathState()
        val intentional = system.copy(fallbackReason = "usb_device_detached")
        val nativePath = system.copy(effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB)
        assertTrue(needsUsbRouteRecovery(system, native, false))
        assertFalse(needsUsbRouteRecovery(intentional, native, false))
        assertTrue(needsUsbRouteRecovery(intentional, native, true))
        assertFalse(needsUsbRouteRecovery(nativePath, native, false))
        assertTrue(needsUsbRouteRecovery(nativePath, native.copy(streaming = false), false))
        assertTrue(native.copy(transitioning = true).blocksRouteRecovery())
        assertTrue(native.copy(source = "tone").blocksRouteRecovery())
        assertFalse(native.blocksRouteRecovery())
    }

    @Test
    fun `manual playback reuses only a clean opened native player route`() {
        val item = UsbPlaybackRouteSnapshot(true, false, true, false, true, 1, 0, 0L, true)
        val nativePath = UsbExclusiveAudioPathState(effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB)
        val native = UsbExclusiveNativeState(source = "player_pcm", opened = true)
        assertTrue(item.hasManualPlaybackItem())
        assertTrue(item.hasPendingManualIntent())
        assertFalse(item.copy(resumeRequested = false).hasPendingManualIntent())
        assertFalse(item.copy(enabled = false).hasPendingManualIntent())
        assertFalse(item.copy(mediaItemCount = 0).hasManualPlaybackItem())
        assertFalse(item.copy(enabled = false).hasManualPlaybackItem())
        assertEquals(0, item.copy(mediaItemIndex = 9).releaseMediaItemIndex())
        assertEquals(0L, item.copy(positionMs = -5L).releasePositionMs())
        assertNull(item.copy(hasMediaItem = false).releaseMediaItemIndex())
        assertNull(item.copy(playerInitialized = false).releasePositionMs())
        assertTrue(reusableNativePlaybackRoute(nativePath, native))
        assertFalse(reusableNativePlaybackRoute(UsbExclusiveAudioPathState(), native))
        assertFalse(reusableNativePlaybackRoute(nativePath.copy(fallbackReason = "failed"), native))
        assertFalse(reusableNativePlaybackRoute(nativePath, native.copy(source = "tone")))
        assertFalse(reusableNativePlaybackRoute(nativePath, native.copy(opened = false)))
        assertFalse(reusableNativePlaybackRoute(nativePath, native.copy(transitioning = true)))
        assertFalse(reusableNativePlaybackRoute(nativePath, native.copy(lastError = "transfer_failed")))
    }

    @Test
    fun `pending intent and transport signals preserve route switching playback`() {
        assertFalse(hasPendingPlaybackIntent(false, false, false))
        assertTrue(hasPendingPlaybackIntent(true, false, false))
        assertTrue(hasPendingPlaybackIntent(false, true, false))
        assertTrue(hasPendingPlaybackIntent(false, false, true))
        assertFalse(playbackSignalsActive(false, false))
        assertTrue(playbackSignalsActive(true, false))
        assertTrue(playbackSignalsActive(false, true))
    }

    @Test
    fun `manual gate wait ends on route cancellation clearance or timeout`() {
        val route = UsbPlaybackRouteSnapshot(true, false, true, false, true, 1, 0, 0L, true)
        assertTrue(shouldWaitForOpenGate(route, "native_transition_in_flight", 50L, 8_000L))
        assertFalse(shouldWaitForOpenGate(route, null, 50L, 8_000L))
        assertFalse(shouldWaitForOpenGate(route, "gate", 8_000L, 8_000L))
        assertFalse(shouldWaitForOpenGate(route.copy(enabled = false), "gate", 50L, 8_000L))
        assertFalse(shouldWaitForOpenGate(route.copy(resumeRequested = false), "gate", 50L, 8_000L))
    }

    @Test
    fun `only active automatic USB activation is deferred`() {
        val active = UsbPlaybackRouteSnapshot(true, false, true, true, true, 1, 0, 0L, true)
        assertTrue(shouldDeferAutomaticRetry(active, "usb_device_changed"))
        assertFalse(shouldDeferAutomaticRetry(active, "usb_manual_playback_start"))
        assertTrue(shouldDeferAutomaticRetry(active, "usb_device_detached"))
        assertFalse(shouldDeferAutomaticRetry(active, "usb_disabled"))
        assertFalse(shouldDeferAutomaticRetry(active.copy(playbackActive = false), "usb_device_changed"))
    }

    @Test
    fun `switch liveness reads transport only when there is no pending intent`() {
        var transportReads = 0
        val transport = { transportReads++; false }
        assertFalse(keepPlaybackActiveForSwitch(false, { true }, transport))
        assertTrue(keepPlaybackActiveForSwitch(true, { true }, transport))
        assertEquals(0, transportReads)
        assertFalse(keepPlaybackActiveForSwitch(true, { false }, transport))
        assertEquals(1, transportReads)
        assertTrue(keepPlaybackActiveForSwitch(true, { false }, { true }))
        assertTrue(canStopAfterNativeFailure(true, true))
        assertFalse(canStopAfterNativeFailure(false, true))
        assertFalse(canStopAfterNativeFailure(true, false))
        assertTrue(hasCurrentTrack(true, true))
        assertFalse(hasCurrentTrack(false, true))
        assertFalse(hasCurrentTrack(true, false))
        val job = Job()
        assertFalse(activePlaybackJob(null))
        assertTrue(activePlaybackJob(job))
        job.cancel()
        assertFalse(activePlaybackJob(job))
    }
}
