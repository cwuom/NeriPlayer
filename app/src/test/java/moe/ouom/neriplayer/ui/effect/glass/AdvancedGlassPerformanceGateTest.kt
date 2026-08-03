package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AdvancedGlassPerformanceGateTest {
    @Test
    fun sameLayerTranslationKeepsTheRenderRegionCacheKeyStable() {
        val region = testRegion(Rect(120.25f, 80.75f, 300.25f, 180.75f))
        val translatedRegion = testRegion(Rect(460.25f, 280.75f, 640.25f, 380.75f))

        val initial = resolveStableAdvancedGlassRenderRegions(
            backdropPositionInWindow = Offset(100.25f, 50.75f),
            regions = listOf(region)
        )
        val translated = resolveStableAdvancedGlassRenderRegions(
            backdropPositionInWindow = Offset(440.25f, 250.75f),
            regions = listOf(translatedRegion)
        )

        assertEquals(initial, translated)
    }

    @Test
    fun regionMovementRelativeToBackdropInvalidatesTheRenderRegionCacheKey() {
        val initial = resolveStableAdvancedGlassRenderRegions(
            backdropPositionInWindow = Offset(100f, 50f),
            regions = listOf(testRegion(Rect(120f, 80f, 300f, 180f)))
        )
        val moved = resolveStableAdvancedGlassRenderRegions(
            backdropPositionInWindow = Offset(100f, 50f),
            regions = listOf(testRegion(Rect(180f, 80f, 360f, 180f)))
        )

        assertNotEquals(initial, moved)
    }

    private fun testRegion(bounds: Rect) = AdvancedGlassRegion(
        role = AdvancedGlassRole.SettingsSection,
        boundsInWindow = bounds,
        cornerRadiiPx = AdvancedGlassCornerRadii(24f, 24f, 24f, 24f),
        navigationOwner = null
    )
}
