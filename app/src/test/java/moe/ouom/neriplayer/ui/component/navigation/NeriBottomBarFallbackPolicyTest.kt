package moe.ouom.neriplayer.ui.component.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriBottomBarFallbackPolicyTest {
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
