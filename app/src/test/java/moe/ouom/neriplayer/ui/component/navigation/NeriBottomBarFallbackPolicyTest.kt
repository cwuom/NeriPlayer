package moe.ouom.neriplayer.ui.component.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriBottomBarFallbackPolicyTest {
    @Test
    fun newTabStyleHidesSelectionIndicatorWithoutCustomBackground() {
        assertEquals(
            0f,
            resolveBottomBarSelectionAlpha(
                hasCustomBackground = false,
                alwaysUseNewTabStyle = true
            ),
            0f
        )
    }

    @Test
    fun oldTabStyleKeepsTranslucentSelectionIndicatorWithoutCustomBackground() {
        assertEquals(
            0.72f,
            resolveBottomBarSelectionAlpha(
                hasCustomBackground = false,
                alwaysUseNewTabStyle = false
            ),
            0f
        )
    }

    @Test
    fun customBackgroundHidesSelectionIndicator() {
        assertEquals(
            0f,
            resolveBottomBarSelectionAlpha(
                hasCustomBackground = true,
                alwaysUseNewTabStyle = false
            ),
            0f
        )
    }

    @Test
    fun customBackgroundStaysTransparentWhenBlurIsNotRequested() {
        assertFalse(
            shouldUseOpaqueBottomBarFallback(
                selectAlpha = 0f,
                baseBlurRequested = false
            )
        )
    }

    @Test
    fun solidThemeOrRequestedBlurKeepsAnOpaqueFailureFallback() {
        assertTrue(
            shouldUseOpaqueBottomBarFallback(
                selectAlpha = 1f,
                baseBlurRequested = false
            )
        )
        assertTrue(
            shouldUseOpaqueBottomBarFallback(
                selectAlpha = 0f,
                baseBlurRequested = true
            )
        )
    }
}
