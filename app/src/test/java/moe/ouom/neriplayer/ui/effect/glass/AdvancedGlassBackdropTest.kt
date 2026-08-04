package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassBackdropTest {
    @Test
    fun localBlurHandoffGuardsRemainEnabledUntilEveryHandoffEnds() {
        val backdrop = AdvancedGlassBackdrop()
        val firstHandoff = Any()
        val secondHandoff = Any()

        assertFalse(backdrop.hasLocalBlurHandoffGuard)

        backdrop.setLocalBlurHandoffGuard(firstHandoff, enabled = true)
        backdrop.setLocalBlurHandoffGuard(secondHandoff, enabled = true)
        backdrop.setLocalBlurHandoffGuard(firstHandoff, enabled = false)

        assertTrue(backdrop.hasLocalBlurHandoffGuard)

        backdrop.removeLocalBlurHandoffGuard(secondHandoff)

        assertFalse(backdrop.hasLocalBlurHandoffGuard)
    }

    @Test
    fun localBlurRequiresASupportedBackend() {
        val plan = requireNotNull(
            resolveAdvancedGlassLocalBlurPlan(
                regions = listOf(region()),
                radiusPx = 24f,
                maximumMergedInputAreaRatio = 1f
            )
        )

        assertTrue(
            plan.groups.isNotEmpty()
        )
        assertFalse(
            ADVANCED_GLASS_BACKEND_MIN_SDK <= 0
        )
    }

    private fun region() = AdvancedGlassRenderRegion(
        left = 0f,
        top = 0f,
        right = 100f,
        bottom = 100f,
        cornerRadiiPx = AdvancedGlassCornerRadii.Zero
    )
}
