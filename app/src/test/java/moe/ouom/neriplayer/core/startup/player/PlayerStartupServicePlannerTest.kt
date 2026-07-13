package moe.ouom.neriplayer.core.startup.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerStartupServicePlannerTest {
    @Test
    fun `does not start service without restored items`() {
        assertNull(
            PlayerStartupServicePlanner.plan(
                hasItems = false,
                shouldBootstrapPlaybackService = true,
                preemptAudioFocus = true,
                allowMixedPlayback = false
            )
        )
    }

    @Test
    fun `foreground bootstrap wins over preempt focus session`() {
        assertEquals(
            PlayerStartupServiceStart(
                source = PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE,
                forceForeground = true
            ),
            PlayerStartupServicePlanner.plan(
                hasItems = true,
                shouldBootstrapPlaybackService = true,
                preemptAudioFocus = true,
                allowMixedPlayback = false
            )
        )
    }

    @Test
    fun `preempt audio focus starts passive service when mixed playback is disabled`() {
        assertEquals(
            PlayerStartupServiceStart(
                source = PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE,
                forceForeground = false
            ),
            PlayerStartupServicePlanner.plan(
                hasItems = true,
                shouldBootstrapPlaybackService = false,
                preemptAudioFocus = true,
                allowMixedPlayback = false
            )
        )
    }

    @Test
    fun `mixed playback disables preempt focus bootstrap`() {
        assertNull(
            PlayerStartupServicePlanner.plan(
                hasItems = true,
                shouldBootstrapPlaybackService = false,
                preemptAudioFocus = true,
                allowMixedPlayback = true
            )
        )
    }
}
