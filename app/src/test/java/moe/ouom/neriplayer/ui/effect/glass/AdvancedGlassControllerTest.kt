package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.graphics.CompositingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassControllerTest {
    @Test
    fun effectiveStateCoversParentChildApiAndBackendTruthTable() {
        listOf(30, ADVANCED_GLASS_MIN_SDK).forEach { sdkInt ->
            listOf(false, true).forEach { parentEnabled ->
                listOf(false, true).forEach { childEnabled ->
                    listOf(false, true).forEach { backendReady ->
                        val controller = AdvancedGlassController(
                            sdkInt = sdkInt,
                            advancedBlurEnabled = parentEnabled,
                            enhancedAdvancedBlurEnabled = childEnabled,
                            backendReady = backendReady
                        )
                        val expected = sdkInt >= ADVANCED_GLASS_MIN_SDK &&
                            parentEnabled && childEnabled && backendReady
                        val baseBlurRequested = sdkInt >= ADVANCED_GLASS_MIN_SDK &&
                            parentEnabled && backendReady

                        assertEquals(baseBlurRequested, controller.isBaseBlurRequested)
                        assertEquals(
                            "sdk=$sdkInt parent=$parentEnabled child=$childEnabled " +
                                "backend=$backendReady",
                            expected,
                            controller.isEnabled
                        )
                    }
                }
            }
        }
    }

    @Test
    fun parentDisableKeepsSavedChildChoiceButDisablesEffectiveState() {
        val controller = AdvancedGlassController(
            sdkInt = ADVANCED_GLASS_MIN_SDK,
            advancedBlurEnabled = false,
            enhancedAdvancedBlurEnabled = true,
            backendReady = true
        )

        assertTrue(controller.enhancedAdvancedBlurEnabled)
        assertFalse(controller.isEnabled)
        assertTrue(controller.copy(advancedBlurEnabled = true).isEnabled)
    }

    @Test
    fun backendFailureDisablesWholeEnhancedLayerForSession() {
        val controller = enabledController()
        val failedController = controller.afterBackendFailure()

        assertTrue(controller.isEnabled)
        assertTrue(failedController.isBaseBlurRequested)
        assertFalse(failedController.isBaseBlurEnabled)
        assertFalse(failedController.isEnabled)
    }

    @Test
    fun backdropOnlyUsesOffscreenCompositionWhileAnEffectExists() {
        assertEquals(
            CompositingStrategy.Auto,
            resolveAdvancedGlassCompositingStrategy(hasRenderEffect = false)
        )
        assertEquals(
            CompositingStrategy.Offscreen,
            resolveAdvancedGlassCompositingStrategy(hasRenderEffect = true)
        )
    }

    @Test
    fun nestedGlassAndInlineControlsNeverSampleBackdrop() {
        val controller = enabledController()

        assertTrue(
            canSampleAdvancedGlassBackdrop(
                controller,
                glassDepth = 0,
                role = AdvancedGlassRole.SettingsSection
            )
        )
        assertFalse(
            canSampleAdvancedGlassBackdrop(
                controller,
                glassDepth = 1,
                role = AdvancedGlassRole.SemanticCard
            )
        )
        assertFalse(
            canSampleAdvancedGlassBackdrop(
                controller,
                glassDepth = 0,
                role = AdvancedGlassRole.InlineControl
            )
        )
    }

    @Test
    fun parentBlurKeepsMiniPlayerAndBottomNavigationActiveWithoutEnhancedLayer() {
        val controller = enabledController().copy(enhancedAdvancedBlurEnabled = false)

        assertTrue(
            canSampleAdvancedGlassBackdrop(
                controller,
                glassDepth = 0,
                role = AdvancedGlassRole.MiniPlayer
            )
        )
        assertTrue(
            canSampleAdvancedGlassBackdrop(
                controller,
                glassDepth = 0,
                role = AdvancedGlassRole.BottomNavigation
            )
        )
        assertFalse(
            canSampleAdvancedGlassBackdrop(
                controller,
                glassDepth = 0,
                role = AdvancedGlassRole.SettingsSection
            )
        )
    }

    @Test
    fun rolesResolveStableOpticalTokens() {
        val bottom = advancedGlassTokens(AdvancedGlassRole.BottomNavigation, isDarkTheme = false)
        val darkBottom = advancedGlassTokens(
            AdvancedGlassRole.BottomNavigation,
            isDarkTheme = true
        )
        val top = advancedGlassTokens(AdvancedGlassRole.ScreenTopTab, isDarkTheme = false)
        val settings = advancedGlassTokens(AdvancedGlassRole.SettingsSection, isDarkTheme = true)
        val adjustable = advancedGlassTokens(
            role = AdvancedGlassRole.SettingsSection,
            isDarkTheme = false,
            enhancedBlurRadiusDp = 52f
        )

        assertEquals(28f, bottom.blurRadiusDp)
        assertEquals(0.75f, bottom.tintAlpha)
        assertEquals(0.75f, darkBottom.tintAlpha)
        assertEquals(22f, top.blurRadiusDp)
        assertEquals(0.28f, settings.tintAlpha)
        assertEquals(52f, adjustable.blurRadiusDp)
        assertTrue(bottom.samplesBackdrop)
    }

    @Test
    fun settingVisibilityRequiresSupportedApiAndEnabledParent() {
        assertFalse(
            shouldShowEnhancedAdvancedBlurSetting(
                sdkInt = ADVANCED_GLASS_MIN_SDK - 1,
                advancedBlurEnabled = true
            )
        )
        assertFalse(
            shouldShowEnhancedAdvancedBlurSetting(
                sdkInt = ADVANCED_GLASS_MIN_SDK,
                advancedBlurEnabled = false
            )
        )
        assertTrue(
            shouldShowEnhancedAdvancedBlurSetting(
                sdkInt = ADVANCED_GLASS_MIN_SDK,
                advancedBlurEnabled = true
            )
        )
    }

    private fun enabledController() = AdvancedGlassController(
        sdkInt = ADVANCED_GLASS_MIN_SDK,
        advancedBlurEnabled = true,
        enhancedAdvancedBlurEnabled = true,
        backendReady = true
    )
}
