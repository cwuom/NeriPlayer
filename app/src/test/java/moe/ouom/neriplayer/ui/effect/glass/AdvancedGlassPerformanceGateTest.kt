package moe.ouom.neriplayer.ui.effect.glass

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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

    @Test
    fun parentScaleKeepsMaskBoundsAndCornersInBackdropCoordinates() {
        val initial = resolveStableAdvancedGlassRenderRegions(
            backdropPositionInWindow = Offset(100f, 50f),
            regions = listOf(testRegion(Rect(120f, 80f, 300f, 180f)))
        )

        listOf(0.94f, 0.97f, 1f).forEach { scale ->
            val translatedOrigin = Offset(240f, 160f)
            val scaledBounds = Rect(
                left = translatedOrigin.x + 20f * scale,
                top = translatedOrigin.y + 30f * scale,
                right = translatedOrigin.x + 200f * scale,
                bottom = translatedOrigin.y + 130f * scale
            )
            val windowRegion = testRegion(scaledBounds).copy(
                cornerRadiiPx = AdvancedGlassCornerRadii(
                    24f * scale, 24f * scale, 24f * scale, 24f * scale
                )
            )

            assertEquals(
                "Parent scale changed the local mask at scale=$scale",
                initial,
                resolveStableAdvancedGlassRenderRegions(
                    backdropPositionInWindow = translatedOrigin,
                    regions = listOf(windowRegion),
                    backdropScaleInWindow = Offset(scale, scale)
                )
            )
        }
    }

    @Test
    fun surfaceScaleAgainstFixedBackdropMovesItsMaskAndCorners() {
        val region = testRegion(Rect(120f, 80f, 300f, 180f)).copy(
            cornerRadiiPx = AdvancedGlassCornerRadii(22.56f, 22.56f, 22.56f, 22.56f)
        )

        val rendered = resolveStableAdvancedGlassRenderRegions(
            backdropPositionInWindow = Offset(100f, 50f),
            regions = listOf(region)
        ).single()

        assertEquals(20f, rendered.left)
        assertEquals(30f, rendered.top)
        assertEquals(200f, rendered.right)
        assertEquals(130f, rendered.bottom)
        assertEquals(AdvancedGlassCornerRadii(23f, 23f, 23f, 23f), rendered.cornerRadiiPx)
    }

    @Test
    fun fadingMasksChangeTheRenderKeyAndInvisibleMasksProduceNoBlur() {
        val region = testRegion(Rect(20f, 30f, 200f, 130f))
        val opaque = resolveStableAdvancedGlassRenderRegions(Offset.Zero, listOf(region))
        val fading = resolveStableAdvancedGlassRenderRegions(
            Offset.Zero, listOf(region.copy(opacity = 0.25f))
        )

        assertNotEquals(opaque, fading)
        assertEquals(0.25f, fading.single().opacity)
        assertEquals(opaque.single().left, fading.single().left)
        assertEquals(
            emptyList<AdvancedGlassRenderRegion>(),
            resolveStableAdvancedGlassRenderRegions(Offset.Zero, listOf(region.copy(opacity = 0f)))
        )
    }

    @Test
    fun currentSceneSnapshotUpdatesTheRenderPlanBeforeAnotherPositionCallback() {
        val bounds = mutableStateOf(Rect(0f, 80f, 440f, 400f))
        val opacity = mutableFloatStateOf(1f)
        val registry = AdvancedGlassRegionRegistry()
        val registered = testRegion(bounds.value).copy(
            regionProvider = { testRegion(bounds.value).copy(opacity = opacity.floatValue) }
        )
        registry.update(Any(), registered)
        val regions = derivedStateOf {
            resolveStableAdvancedGlassRenderRegions(
                Offset.Zero,
                resolveCurrentAdvancedGlassRegions(registry.regions)
            )
        }
        val plan = derivedStateOf {
            resolveAdvancedGlassLocalBlurPlan(
                regions = regions.value,
                radiusPx = 48f,
                maximumMergedInputAreaRatio = 1.08f,
                downscaleFactor = 2
            )
        }
        assertEquals(1f, requireNotNull(plan.value).groups.single().regions.single().opacity, 0f)

        bounds.value = Rect(10.1f, 84f, 439f, 396f)
        opacity.floatValue = 0.5782508f

        val current = regions.value.single()
        assertEquals(10f, current.left, 0f)
        assertEquals(84f, current.top, 0f)
        assertEquals(439f, current.right, 0f)
        assertEquals(396f, current.bottom, 0f)
        assertEquals(opacity.floatValue, current.opacity, 0f)
        assertEquals(current, requireNotNull(plan.value).groups.single().regions.single())
        assertEquals("This fixture must not dispatch another position callback", registered, registry.regions.single())

        opacity.floatValue = 0f
        assertEquals(emptyList<AdvancedGlassRenderRegion>(), regions.value)
        assertEquals(null, plan.value)
        opacity.floatValue = 1f
        assertEquals(1f, requireNotNull(plan.value).groups.single().regions.single().opacity, 0f)
    }

    @Test
    fun detachedSceneSnapshotDoesNotFallBackToItsOldRegisteredBounds() {
        val attached = mutableStateOf(true)
        val region = testRegion(Rect(20f, 30f, 200f, 130f))
        val registry = AdvancedGlassRegionRegistry()
        registry.update(Any(), region.copy(regionProvider = { if (attached.value) region else null }))
        val current = derivedStateOf { resolveCurrentAdvancedGlassRegions(registry.regions) }
        assertEquals(listOf(region), current.value)

        attached.value = false

        assertEquals(1, registry.regions.size)
        assertEquals(emptyList<AdvancedGlassRegion>(), current.value)
        assertEquals(emptyList<AdvancedGlassRenderRegion>(), resolveStableAdvancedGlassRenderRegions(Offset.Zero, current.value))
    }

    private fun testRegion(bounds: Rect) = AdvancedGlassRegion(
        role = AdvancedGlassRole.SettingsSection,
        boundsInWindow = bounds,
        cornerRadiiPx = AdvancedGlassCornerRadii(24f, 24f, 24f, 24f),
        navigationOwner = null
    )
}
