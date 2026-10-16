package moe.ouom.neriplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.effect.glass.resolveLiquidGlassDeviceAllowed
import moe.ouom.neriplayer.ui.theme.reveal.AppThemeRevealPresentation
import moe.ouom.neriplayer.ui.theme.reveal.appThemeRevealPresentation
import moe.ouom.neriplayer.ui.theme.reveal.shouldReverseThemeReveal
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewThemePolicyTest {
    @Test
    fun blackAndGreySeedsUseMonochromeInsteadOfInventedPurple() {
        listOf(Color.Black, Color(0xFF0B0A0C), Color(0xFF808080), Color.White).forEach { seed ->
            assertTrue(isAchromaticSeed(seed))
            assertEquals(PaletteStyle.Monochrome, resolveSeedPaletteStyle(seed, PaletteStyle.TonalSpot))
        }
    }

    @Test
    fun colorfulSeedsKeepRequestedPaletteStyle() {
        val seed = Color(0xFF3366FF)
        assertFalse(isAchromaticSeed(seed))
        assertEquals(PaletteStyle.Vibrant, resolveSeedPaletteStyle(seed, PaletteStyle.Vibrant))
    }

    @Test
    fun darkAndGreyCoverSeedsLeanBlueWhileColorfulOnesStay() {
        listOf("000000", "120A10", "808080").forEach { hex ->
            val biased = Color(("FF" + biasCoverSeedTowardBlue(hex)).toLong(16))
            assertTrue("$hex should lean blue", biased.blue > biased.red && biased.blue > biased.green)
            assertFalse(isAchromaticSeed(biased))
        }
        assertEquals("E8402C", biasCoverSeedTowardBlue("E8402C"))
    }

    @Test
    fun cleanCornersGrowSmallRadiiAndConvergeOnLargeOnes() {
        assertEquals(0.dp, cleanCornerRadiusFor(0.dp))
        assertEquals(12.dp, cleanCornerRadiusFor(8.dp))
        assertEquals(20.dp, cleanCornerRadiusFor(16.dp))
        assertEquals(26.dp, cleanCornerRadiusFor(24.dp))
        assertEquals(30.dp, cleanCornerRadiusFor(30.dp))
    }

    @Test
    fun liquidGlassBacksOffOnConstrainedDevices() {
        assertTrue(resolveLiquidGlassDeviceAllowed(false, false, false, AdvancedBlurQuality.Default))
        assertFalse(resolveLiquidGlassDeviceAllowed(true, false, false, AdvancedBlurQuality.High))
        assertFalse(resolveLiquidGlassDeviceAllowed(false, true, false, AdvancedBlurQuality.High))
        assertFalse(resolveLiquidGlassDeviceAllowed(false, false, true, AdvancedBlurQuality.Default))
        assertTrue(resolveLiquidGlassDeviceAllowed(false, false, true, AdvancedBlurQuality.High))
    }

    @Test
    fun switchingToLightCollapsesTheDarkSnapshot() {
        assertTrue(shouldReverseThemeReveal(nextDark = false))
        assertFalse(shouldReverseThemeReveal(nextDark = true))
        val origin = Offset(4f, 8f)
        assertEquals(
            AppThemeRevealPresentation(origin, 1, 2, reverse = true),
            appThemeRevealPresentation(origin, 1, 2, reverse = true)
        )
    }

    @Test
    fun unknownStoredStyleFallsBackToMaterial() {
        assertEquals(UiStyle.Clean, UiStyle.fromStorage("clean"))
        assertEquals(UiStyle.Material, UiStyle.fromStorage(null))
        assertEquals(UiStyle.Material, UiStyle.fromStorage("aurora"))
    }

    @Test
    fun designTokensFollowStyle() {
        assertFalse(neriDesignTokensFor(UiStyle.Material).isClean)
        assertTrue(neriDesignTokensFor(UiStyle.Clean).isClean)
        assertTrue(neriDesignTokensFor(UiStyle.Clean).pressScaleEnabled)
    }
}
