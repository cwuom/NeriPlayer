package moe.ouom.neriplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerPlaybackStartPlanTest {

    @Test
    fun `fade in plan starts muted`() {
        val plan = resolvePlaybackStartPlan(
            shouldFadeIn = true,
            fadeDurationMs = 500L
        )

        assertTrue(plan.useFadeIn)
        assertEquals(500L, plan.fadeDurationMs)
        assertEquals(0f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `disabled fade in keeps full volume`() {
        val plan = resolvePlaybackStartPlan(
            shouldFadeIn = false,
            fadeDurationMs = 500L
        )

        assertFalse(plan.useFadeIn)
        assertEquals(500L, plan.fadeDurationMs)
        assertEquals(1f, plan.initialVolume, 0.0001f)
    }

    @Test
    fun `non positive duration disables fade in`() {
        val plan = resolvePlaybackStartPlan(
            shouldFadeIn = true,
            fadeDurationMs = -120L
        )

        assertFalse(plan.useFadeIn)
        assertEquals(0L, plan.fadeDurationMs)
        assertEquals(1f, plan.initialVolume, 0.0001f)
    }
}
