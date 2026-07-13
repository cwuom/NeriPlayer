package moe.ouom.neriplayer.core.player.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceIdlePolicyTest {
    @Test
    fun `paused queue schedules idle shutdown`() {
        assertTrue(resolveShouldScheduleIdleShutdown())
    }

    @Test
    fun `active playback surfaces keep service alive`() {
        assertFalse(resolveShouldScheduleIdleShutdown(transportActive = true))
        assertFalse(resolveShouldScheduleIdleShutdown(transportBuffering = true))
        assertFalse(resolveShouldScheduleIdleShutdown(listenTogetherSessionActive = true))
        assertFalse(resolveShouldScheduleIdleShutdown(usbSessionActiveOrTransitioning = true))
        assertFalse(resolveShouldScheduleIdleShutdown(sleepTimerActive = true))
    }

    @Test
    fun `empty or uninitialized runtime does not schedule shutdown`() {
        assertFalse(resolveShouldScheduleIdleShutdown(playerInitialized = false))
        assertFalse(resolveShouldScheduleIdleShutdown(hasPlaybackSurfaceContent = false))
    }

    private fun resolveShouldScheduleIdleShutdown(
        playerInitialized: Boolean = true,
        hasPlaybackSurfaceContent: Boolean = true,
        transportActive: Boolean = false,
        transportBuffering: Boolean = false,
        listenTogetherSessionActive: Boolean = false,
        usbSessionActiveOrTransitioning: Boolean = false,
        sleepTimerActive: Boolean = false,
    ): Boolean {
        return shouldSchedulePlaybackServiceIdleShutdown(
            playerInitialized = playerInitialized,
            hasPlaybackSurfaceContent = hasPlaybackSurfaceContent,
            transportActive = transportActive,
            transportBuffering = transportBuffering,
            listenTogetherSessionActive = listenTogetherSessionActive,
            usbSessionActiveOrTransitioning = usbSessionActiveOrTransitioning,
            sleepTimerActive = sleepTimerActive,
        )
    }
}
