package moe.ouom.neriplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriAppStartupGlassGatePolicyTest {
    @Test
    fun disabledGlassDoesNotHoldStartupGate() {
        assertTrue(
            shouldReleaseStartupGlassGate(
                baseBlurEnabled = false,
                backgroundEffectReady = false,
                contentEffectReady = false
            )
        )
        assertFalse(
            shouldShowStartupGlassGate(
                baseBlurEnabled = false,
                gateReleased = false,
                backgroundEffectReady = false,
                contentEffectReady = false
            )
        )
    }

    @Test
    fun enabledGlassHoldsStartupGateUntilAnEffectIsReady() {
        assertFalse(
            shouldReleaseStartupGlassGate(
                baseBlurEnabled = true,
                backgroundEffectReady = false,
                contentEffectReady = false
            )
        )
        assertTrue(
            shouldShowStartupGlassGate(
                baseBlurEnabled = true,
                gateReleased = false,
                backgroundEffectReady = false,
                contentEffectReady = false
            )
        )

        assertTrue(
            shouldReleaseStartupGlassGate(
                baseBlurEnabled = true,
                backgroundEffectReady = false,
                contentEffectReady = true
            )
        )
        assertFalse(
            shouldShowStartupGlassGate(
                baseBlurEnabled = true,
                gateReleased = false,
                backgroundEffectReady = false,
                contentEffectReady = true
            )
        )
    }

    @Test
    fun releasedStartupGateStaysHidden() {
        assertFalse(
            shouldShowStartupGlassGate(
                baseBlurEnabled = true,
                gateReleased = true,
                backgroundEffectReady = false,
                contentEffectReady = false
            )
        )
    }
}
