package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassLocalBlurPlanTest {
    @Test
    fun lowProfileMergesNearbyRegionsWithoutChangingTheirMasks() {
        val first = region(left = 0f, top = 0f, right = 200f, bottom = 100f)
        val second = region(left = 0f, top = 112f, right = 200f, bottom = 212f)

        val plan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = listOf(first, second),
                radiusPx = 24f,
                maximumMergedInputAreaRatio =
                    AdvancedGlassRenderProfile.Low.maximumMergedInputAreaRatio,
                downscaleFactor = AdvancedGlassRenderProfile.Low.downscaleFactorFor(radiusPx = 24f)
            )
        )

        assertEquals(1, plan.groups.size)
        assertEquals(listOf(first, second), plan.groups.single().regions)
        assertEquals(0f, plan.groups.single().bounds.left)
        assertEquals(212f, plan.groups.single().bounds.bottom)
        assertEquals(48f, plan.inputPaddingPx)
        assertEquals(2, plan.downscaleFactor)
    }

    @Test
    fun farApartRegionsKeepIndependentNativeRenderTargets() {
        val plan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = listOf(
                    region(left = 0f, top = 0f, right = 200f, bottom = 100f),
                    region(left = 0f, top = 800f, right = 200f, bottom = 900f)
                ),
                radiusPx = 24f,
                maximumMergedInputAreaRatio =
                    AdvancedGlassRenderProfile.UltraLow.maximumMergedInputAreaRatio,
                downscaleFactor = AdvancedGlassRenderProfile.UltraLow.downscaleFactorFor(radiusPx = 24f)
            )
        )

        assertEquals(2, plan.groups.size)
    }

    @Test
    fun ultraLowCanReuseOneHardwareTargetForSlightlyWiderGroups() {
        val regions = listOf(
            region(left = 0f, top = 0f, right = 200f, bottom = 100f),
            region(left = 0f, top = 250f, right = 200f, bottom = 350f)
        )

        val lowPlan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = regions,
                radiusPx = 24f,
                maximumMergedInputAreaRatio =
                    AdvancedGlassRenderProfile.Low.maximumMergedInputAreaRatio,
                downscaleFactor = AdvancedGlassRenderProfile.Low.downscaleFactorFor(radiusPx = 24f)
            )
        )
        val ultraLowPlan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = regions,
                radiusPx = 24f,
                maximumMergedInputAreaRatio =
                    AdvancedGlassRenderProfile.UltraLow.maximumMergedInputAreaRatio,
                downscaleFactor = AdvancedGlassRenderProfile.UltraLow.downscaleFactorFor(radiusPx = 24f)
            )
        )

        assertEquals(2, lowPlan.groups.size)
        assertEquals(1, ultraLowPlan.groups.size)
    }

    @Test
    fun ultraLowUsesOneQuarterOfLowQualityBlurredInputAreaAtFourX() {
        val regions = listOf(
            region(left = 100f, top = 100f, right = 300f, bottom = 260f)
        )
        val lowPlan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = regions,
                radiusPx = 72f,
                maximumMergedInputAreaRatio =
                    AdvancedGlassRenderProfile.Low.maximumMergedInputAreaRatio,
                downscaleFactor = AdvancedGlassRenderProfile.Low.downscaleFactorFor(radiusPx = 72f)
            )
        )
        val ultraLowPlan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = regions,
                radiusPx = 72f,
                maximumMergedInputAreaRatio =
                    AdvancedGlassRenderProfile.UltraLow.maximumMergedInputAreaRatio,
                downscaleFactor =
                    AdvancedGlassRenderProfile.UltraLow.downscaleFactorFor(radiusPx = 72f)
            )
        )

        assertEquals(2, lowPlan.downscaleFactor)
        assertEquals(4, ultraLowPlan.downscaleFactor)
        assertEquals(
            lowPlan.estimatedBlurredInputArea / 4.0,
            ultraLowPlan.estimatedBlurredInputArea,
            0.001
        )
    }

    @Test
    fun invalidBlurInputsDoNotCreateAHardwarePlan() {
        assertNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = listOf(region(left = 0f, top = 0f, right = 1f, bottom = 1f)),
                radiusPx = 0f,
                maximumMergedInputAreaRatio = 1f
            )
        )
        assertNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = listOf(region(left = 0f, top = 0f, right = 1f, bottom = 1f)),
                radiusPx = 12f,
                maximumMergedInputAreaRatio = 1f,
                downscaleFactor = 3
            )
        )
    }

    @Test
    fun navigationHandoffRetainsAnExistingPlanUntilTheNextSceneMaskArrives() {
        val currentPlan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = listOf(region(left = 0f, top = 0f, right = 200f, bottom = 100f)),
                radiusPx = 24f,
                maximumMergedInputAreaRatio = 1f
            )
        )
        val nextPlan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = listOf(region(left = 20f, top = 0f, right = 220f, bottom = 100f)),
                radiusPx = 24f,
                maximumMergedInputAreaRatio = 1f
            )
        )

        assertTrue(
            shouldRetainCurrentLocalBlurPlan(
                currentPlan = currentPlan,
                handoffActive = true
            )
        )
        assertFalse(
            shouldRetainCurrentLocalBlurPlan(
                currentPlan = null,
                handoffActive = true
            )
        )
        assertFalse(
            shouldRetainCurrentLocalBlurPlan(
                currentPlan = currentPlan,
                handoffActive = false
            )
        )
        assertTrue(
            shouldFreezeLocalBlurFrame(
                currentPlan = currentPlan,
                nextPlan = null,
                retainCurrentPlan = true,
                allowOneFrameHandoff = true
            )
        )
        assertTrue(
            shouldFreezeLocalBlurFrame(
                currentPlan = currentPlan,
                nextPlan = null,
                retainCurrentPlan = false,
                allowOneFrameHandoff = true
            )
        )
        assertFalse(
            shouldFreezeLocalBlurFrame(
                currentPlan = currentPlan,
                nextPlan = nextPlan,
                retainCurrentPlan = true,
                allowOneFrameHandoff = true
            )
        )
        assertFalse(
            shouldFreezeLocalBlurFrame(
                currentPlan = currentPlan,
                nextPlan = null,
                retainCurrentPlan = false,
                allowOneFrameHandoff = false
            )
        )
    }

    private fun region(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) = AdvancedGlassRenderRegion(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        cornerRadiiPx = AdvancedGlassCornerRadii(
            topLeft = 24f,
            topRight = 18f,
            bottomRight = 12f,
            bottomLeft = 6f
        )
    )
}
