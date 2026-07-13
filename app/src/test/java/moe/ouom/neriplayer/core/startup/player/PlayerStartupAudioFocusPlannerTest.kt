package moe.ouom.neriplayer.core.startup.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStartupAudioFocusPlannerTest {
    @Test
    fun `skips update before lifecycle is resumed`() {
        val plan = PlayerStartupAudioFocusPlanner.planForegroundRefresh(
            lifecycleResumed = false,
            playerInitialized = true,
            reason = "settings_changed",
            usbExclusiveNativePlaybackStable = false,
            preemptAudioFocus = true,
            allowMixedPlayback = false,
            usbExclusivePlayback = false,
            usbExclusiveNativeActive = false,
            transportActive = false
        )

        assertFalse(plan.shouldUpdate)
        assertEquals(PlayerStartupAudioFocusSkipReason.LifecycleNotResumed, plan.skipReason)
    }

    @Test
    fun `skips update before player initialization`() {
        val plan = PlayerStartupAudioFocusPlanner.planForegroundRefresh(
            lifecycleResumed = true,
            playerInitialized = false,
            reason = "settings_changed",
            usbExclusiveNativePlaybackStable = false,
            preemptAudioFocus = true,
            allowMixedPlayback = false,
            usbExclusivePlayback = false,
            usbExclusiveNativeActive = false,
            transportActive = false
        )

        assertFalse(plan.shouldUpdate)
        assertEquals(PlayerStartupAudioFocusSkipReason.PlayerNotInitialized, plan.skipReason)
    }

    @Test
    fun `keeps stable usb native playback untouched on lifecycle resume`() {
        val plan = PlayerStartupAudioFocusPlanner.planForegroundRefresh(
            lifecycleResumed = true,
            playerInitialized = true,
            reason = "lifecycle_resume",
            usbExclusiveNativePlaybackStable = true,
            preemptAudioFocus = true,
            allowMixedPlayback = false,
            usbExclusivePlayback = true,
            usbExclusiveNativeActive = true,
            transportActive = true
        )

        assertFalse(plan.shouldUpdate)
        assertEquals(PlayerStartupAudioFocusSkipReason.StableUsbNativePlayback, plan.skipReason)
    }

    @Test
    fun `enables focus when user setting or usb guard asks for it`() {
        val plan = PlayerStartupAudioFocusPlanner.planForegroundRefresh(
            lifecycleResumed = true,
            playerInitialized = true,
            reason = "settings_changed",
            usbExclusiveNativePlaybackStable = false,
            preemptAudioFocus = false,
            allowMixedPlayback = false,
            usbExclusivePlayback = true,
            usbExclusiveNativeActive = true,
            transportActive = false
        )

        assertTrue(plan.shouldUpdate)
        assertTrue(plan.enabled)
    }

    @Test
    fun `preserves foreground focus arguments for app bootstrap`() {
        val plan = PlayerStartupAudioFocusPlanner.planBootstrap(
            preemptAudioFocus = true,
            allowMixedPlayback = false,
            usbExclusivePlayback = false,
            usbExclusiveNativeActive = false,
            transportActive = true,
            reason = PlayerStartupAudioFocusPlanner.APP_BOOTSTRAP_REASON
        )

        assertTrue(plan.shouldUpdate)
        assertTrue(plan.enabled)
        assertTrue(plan.transportActive)
        assertEquals(PlayerStartupAudioFocusPlanner.APP_BOOTSTRAP_REASON, plan.reason)
    }

    @Test
    fun `releases inactive settings only when focus is no longer needed`() {
        assertTrue(
            PlayerStartupAudioFocusPlanner.shouldReleaseWhenSettingsChangeInactive(
                preemptAudioFocus = false,
                usbExclusivePlayback = false,
                allowMixedPlayback = false
            )
        )
        assertTrue(
            PlayerStartupAudioFocusPlanner.shouldReleaseWhenSettingsChangeInactive(
                preemptAudioFocus = true,
                usbExclusivePlayback = true,
                allowMixedPlayback = true
            )
        )
        assertFalse(
            PlayerStartupAudioFocusPlanner.shouldReleaseWhenSettingsChangeInactive(
                preemptAudioFocus = false,
                usbExclusivePlayback = true,
                allowMixedPlayback = false
            )
        )
    }
}
