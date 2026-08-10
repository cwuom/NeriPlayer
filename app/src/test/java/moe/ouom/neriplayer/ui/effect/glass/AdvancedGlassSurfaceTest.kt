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

    @Test
    fun preparedInactiveNavigationOwnerRegistersForTheNextGlassHandoff() {
        assertTrue(
            shouldRegisterAdvancedGlassRegion(
                sceneActive = true,
                backdropRegistrationEnabled = true,
                belongsToActiveNavigationScreen = false,
                belongsToPrewarmedNavigationScreen = true
            )
        )
        assertFalse(
            shouldRegisterAdvancedGlassRegion(
                sceneActive = true,
                backdropRegistrationEnabled = true,
                belongsToActiveNavigationScreen = false,
                belongsToPrewarmedNavigationScreen = false
            )
        )
        assertFalse(
            shouldRegisterAdvancedGlassRegion(
                sceneActive = false,
                backdropRegistrationEnabled = true,
                belongsToActiveNavigationScreen = true,
                belongsToPrewarmedNavigationScreen = true
            )
        )
    }

    @Test
    fun prewarmedInactiveNavigationOwnerCanPreserveGlassTintDuringOnboarding() {
        assertTrue(
            shouldPreserveAdvancedGlassTintForInactiveNavigationOwner(
                preserveTintForInactiveNavigationOwner = true,
                canRenderGlass = true,
                belongsToActiveNavigationScreen = false,
                belongsToPrewarmedNavigationScreen = true
            )
        )
        assertFalse(
            shouldPreserveAdvancedGlassTintForInactiveNavigationOwner(
                preserveTintForInactiveNavigationOwner = false,
                canRenderGlass = true,
                belongsToActiveNavigationScreen = false,
                belongsToPrewarmedNavigationScreen = true
            )
        )
        assertFalse(
            shouldPreserveAdvancedGlassTintForInactiveNavigationOwner(
                preserveTintForInactiveNavigationOwner = true,
                canRenderGlass = true,
                belongsToActiveNavigationScreen = true,
                belongsToPrewarmedNavigationScreen = true
            )
        )
        assertFalse(
            shouldPreserveAdvancedGlassTintForInactiveNavigationOwner(
                preserveTintForInactiveNavigationOwner = true,
                canRenderGlass = false,
                belongsToActiveNavigationScreen = false,
                belongsToPrewarmedNavigationScreen = true
            )
        )
    }
}
