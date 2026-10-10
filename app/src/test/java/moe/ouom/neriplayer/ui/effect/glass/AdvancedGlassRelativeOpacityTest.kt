package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedGlassRelativeOpacityTest {
    @Test
    fun fixedBackdropKeepsTheSceneFadeAsItsMaskOpacity() {
        assertEquals(0.25f, resolveAdvancedGlassRelativeOpacity(0.25f, 1f), 0f)
    }

    @Test
    fun sceneBackdropDoesNotApplyItsParentFadeAgain() {
        assertEquals(1f, resolveAdvancedGlassRelativeOpacity(0.25f, 0.25f), 0f)
        assertEquals(0.5f, resolveAdvancedGlassRelativeOpacity(0.125f, 0.25f), 0f)
    }

    @Test
    fun invisibleBackdropCannotKeepAnOpaqueMask() {
        assertEquals(0f, resolveAdvancedGlassRelativeOpacity(0f, 0f), 0f)
        assertEquals(0f, resolveAdvancedGlassRelativeOpacity(0.25f, 0f), 0f)
    }
}
