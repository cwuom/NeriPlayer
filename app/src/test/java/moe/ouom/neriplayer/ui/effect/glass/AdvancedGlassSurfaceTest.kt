package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassSurfaceTest {
    @Test
    fun inactiveNavigationOwnerCannotRenderGlassDuringAStageHandoff() {
        val activeOwner = Any()
        val inactiveOwner = Any()
        val activeOwners = setOf<Any>(activeOwner)

        assertTrue(
            isAdvancedGlassNavigationOwnerActive(
                requiresContentBackdrop = false,
                activeNavigationOwners = activeOwners,
                navigationOwner = activeOwner
            )
        )
        assertFalse(
            isAdvancedGlassNavigationOwnerActive(
                requiresContentBackdrop = false,
                activeNavigationOwners = activeOwners,
                navigationOwner = inactiveOwner
            )
        )
        assertTrue(
            isAdvancedGlassNavigationOwnerActive(
                requiresContentBackdrop = true,
                activeNavigationOwners = activeOwners,
                navigationOwner = inactiveOwner
            )
        )
    }
}
