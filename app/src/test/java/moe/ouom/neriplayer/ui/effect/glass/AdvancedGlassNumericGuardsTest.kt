package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.animation.EnterExitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassNumericGuardsTest {

    @Test
    fun `relative opacity drops the mask for non finite or non positive inputs`() {
        val invalidInputs = listOf(
            Float.NaN to 1f,
            Float.POSITIVE_INFINITY to 1f,
            Float.NEGATIVE_INFINITY to 1f,
            0.5f to Float.NaN,
            0.5f to Float.POSITIVE_INFINITY,
            0.5f to Float.NEGATIVE_INFINITY,
            0.5f to -0.5f
        )

        invalidInputs.forEach { (scene, backdrop) ->
            assertEquals(
                "scene=$scene backdrop=$backdrop",
                0f,
                resolveAdvancedGlassRelativeOpacity(scene, backdrop),
                0f
            )
        }
    }

    @Test
    fun `relative opacity clamps the scene to backdrop ratio into the unit range`() {
        assertEquals(1f, resolveAdvancedGlassRelativeOpacity(0.9f, 0.3f), 0f)
        assertEquals(0f, resolveAdvancedGlassRelativeOpacity(-0.2f, 0.5f), 0f)
    }

    @Test
    fun `fullscreen pipeline never downscales regardless of radius`() {
        assertEquals(1, AdvancedGlassRenderProfile.Native.downscaleFactorFor(radiusPx = 512f))
    }

    @Test
    fun `region local profiles ignore non finite radii`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { radius ->
            assertEquals(1, AdvancedGlassRenderProfile.UltraLow.downscaleFactorFor(radius))
            assertEquals(1, AdvancedGlassRenderProfile.Low.downscaleFactorFor(radius))
        }
    }

    @Test
    fun `downscale factor follows the profile cap and radius thresholds`() {
        val ultraLow = AdvancedGlassRenderProfile.UltraLow
        assertEquals(4, ultraLow.downscaleFactorFor(48f))
        assertEquals(2, ultraLow.downscaleFactorFor(47.9f))
        assertEquals(2, ultraLow.downscaleFactorFor(18f))
        assertEquals(1, ultraLow.downscaleFactorFor(17.9f))

        val low = AdvancedGlassRenderProfile.Low
        assertEquals(2, low.downscaleFactorFor(480f))
        assertEquals(1, low.downscaleFactorFor(17.9f))

        val regionLocalWithoutDownscale = AdvancedGlassRenderProfile(
            algorithm = AdvancedGlassBlurAlgorithm.Native,
            pipeline = AdvancedGlassRenderPipeline.RegionLocal,
            maximumDownscaleFactor = 1
        )
        assertEquals(1, regionLocalWithoutDownscale.downscaleFactorFor(480f))
    }

    @Test
    fun `visible scene keeps the neutral pose`() {
        listOf(true, false).forEach { deeper ->
            assertEquals(
                AdvancedGlassSceneMotion.None,
                resolveAdvancedGlassVisibilitySceneMotion(
                    state = EnterExitState.Visible,
                    enteringFromDeeperScene = deeper,
                    exitingToDeeperScene = deeper
                )
            )
        }
    }

    @Test
    fun `deeper scene transitions use the recessed drawer pose on both edges`() {
        val recessed = AdvancedGlassSceneMotion(
            revealTopFraction = 0f,
            contentTranslationYFraction = DRAWER_BACKGROUND_SINK_FRACTION,
            contentScale = DRAWER_RECESSED_CONTENT_SCALE
        )

        assertEquals(
            recessed,
            resolveAdvancedGlassVisibilitySceneMotion(
                state = EnterExitState.PreEnter,
                enteringFromDeeperScene = true,
                exitingToDeeperScene = false
            )
        )
        assertEquals(
            recessed,
            resolveAdvancedGlassVisibilitySceneMotion(
                state = EnterExitState.PostExit,
                enteringFromDeeperScene = false,
                exitingToDeeperScene = true
            )
        )
    }

    @Test
    fun `shallower scene transitions slide the whole surface off screen`() {
        val offscreen = AdvancedGlassSceneMotion(
            revealTopFraction = 1f,
            contentTranslationYFraction = 1f,
            contentScale = 1f
        )

        assertEquals(
            offscreen,
            resolveAdvancedGlassVisibilitySceneMotion(
                state = EnterExitState.PreEnter,
                enteringFromDeeperScene = false,
                exitingToDeeperScene = true
            )
        )
        assertEquals(
            offscreen,
            resolveAdvancedGlassVisibilitySceneMotion(
                state = EnterExitState.PostExit,
                enteringFromDeeperScene = true,
                exitingToDeeperScene = false
            )
        )
    }

    @Test
    fun `overscroll damping is inert without drag or resistance`() {
        assertEquals(0f, dampedAdvancedGlassOverscrollOffset(rawDrag = 0f, resistanceScale = 300f), 0f)
        assertEquals(0f, dampedAdvancedGlassOverscrollOffset(rawDrag = 120f, resistanceScale = 0f), 0f)
        assertEquals(0f, dampedAdvancedGlassOverscrollOffset(rawDrag = -120f, resistanceScale = -1f), 0f)
    }

    @Test
    fun `overscroll restore is inert without offset or resistance`() {
        assertEquals(0f, restoredAdvancedGlassOverscrollDrag(offset = 0f, resistanceScale = 300f), 0f)
        assertEquals(0f, restoredAdvancedGlassOverscrollDrag(offset = 40f, resistanceScale = 0f), 0f)
        assertEquals(0f, restoredAdvancedGlassOverscrollDrag(offset = -40f, resistanceScale = -2f), 0f)
    }

    @Test
    fun `restoring an offset beyond the damped cap stays finite and keeps its direction`() {
        val resistanceScale = 300f
        val cap = maxAdvancedGlassOverscrollOffset(resistanceScale)

        val restoredPull = restoredAdvancedGlassOverscrollDrag(cap * 2f, resistanceScale)
        val restoredPush = restoredAdvancedGlassOverscrollDrag(-cap * 2f, resistanceScale)

        assertTrue(restoredPull.isFinite() && restoredPull > 0f)
        assertEquals(-restoredPull, restoredPush, 0f)
        assertTrue(restoredPull <= maxAdvancedGlassOverscrollRawDrag(resistanceScale) + 0.01f)
    }
}
