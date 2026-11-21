package moe.ouom.neriplayer.ui.effect.glass

import android.graphics.Path as AndroidPath
import android.graphics.Region
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdvancedGlassRegionPathsTest {
    @Test
    fun opacityChangesKeepNativePathsAndUpdateTheirWeights() {
        assertWeightChangeKeepsPaths { region -> region.copy(opacity = region.opacity / 2f) }
    }

    @Test
    fun lensChangesKeepNativePathsAndUpdateTheirWeights() {
        assertWeightChangeKeepsPaths { region -> region.copy(lens = 1f - region.lens) }
    }

    @Test
    fun changingAnyBoundOrCornerRefreshesTheNativeMasks() {
        val initial = firstRegion()
        val changes = listOf(
            initial.copy(left = initial.left + 10f),
            initial.copy(top = initial.top + 10f),
            initial.copy(right = initial.right + 10f),
            initial.copy(bottom = initial.bottom + 10f),
            initial.copy(cornerRadiiPx = initial.cornerRadiiPx.copy(topLeft = 28f)),
            initial.copy(cornerRadiiPx = initial.cornerRadiiPx.copy(topRight = 36f)),
            initial.copy(cornerRadiiPx = initial.cornerRadiiPx.copy(bottomRight = 44f)),
            initial.copy(cornerRadiiPx = initial.cornerRadiiPx.copy(bottomLeft = 52f))
        )

        changes.forEach { changed ->
            val paths = AdvancedGlassRegionPaths()
            paths.update(listOf(initial))
            val beforeGeneration = paths.outputPath.asAndroidPath().generationId

            paths.update(listOf(changed))

            assertTrue(
                "geometry change did not refresh the combined mask: $changed",
                beforeGeneration != paths.outputPath.asAndroidPath().generationId
            )
            assertMasksMatchRegions(paths, listOf(changed))
        }
    }

    @Test
    fun reorderingRegionsKeepsEachWeightAttachedToItsOwnMask() {
        val first = firstRegion()
        val second = secondRegion()
        val paths = AdvancedGlassRegionPaths()
        paths.update(listOf(first, second))

        paths.update(listOf(second, first))

        assertMasksMatchRegions(paths, listOf(second, first))
    }

    @Test
    fun insertingRemovingAndClearingRegionsLeaveNoStaleMasks() {
        val first = firstRegion()
        val second = secondRegion()
        val third = first.copy(left = 260f, right = 400f, opacity = 0.6f)
        val paths = AdvancedGlassRegionPaths()
        paths.update(listOf(first, second))

        listOf(
            listOf(first, third, second),
            listOf(second),
            emptyList(),
            listOf(first)
        ).forEach { regions ->
            paths.update(regions)
            assertMasksMatchRegions(paths, regions)
        }
    }

    private fun assertWeightChangeKeepsPaths(
        change: (AdvancedGlassRenderRegion) -> AdvancedGlassRenderRegion
    ) {
        val initial = listOf(firstRegion(), secondRegion())
        val paths = AdvancedGlassRegionPaths()
        paths.update(initial)
        assertMasksMatchRegions(paths, initial)
        val combinedGeneration = paths.outputPath.asAndroidPath().generationId
        val individualPaths = paths.outputRegions.map { it.path }
        val individualGenerations = individualPaths.map { it.asAndroidPath().generationId }
        val changed = initial.map(change)

        paths.update(changed)

        assertEquals(
            "changing weights must not rebuild the combined mask",
            combinedGeneration,
            paths.outputPath.asAndroidPath().generationId
        )
        individualPaths.forEachIndexed { index, path ->
            assertSame("changing weights must retain each native path", path, paths.outputRegions[index].path)
            assertEquals(individualGenerations[index], paths.outputRegions[index].path.asAndroidPath().generationId)
        }
        assertMasksMatchRegions(paths, changed)
    }

    private fun assertMasksMatchRegions(
        paths: AdvancedGlassRegionPaths,
        regions: List<AdvancedGlassRenderRegion>
    ) {
        assertEquals(regions, paths.outputRegions.map { it.region })
        assertSameMask(expectedPath(regions), paths.outputPath)
        paths.outputRegions.forEach { output ->
            assertSameMask(expectedPath(listOf(output.region)), output.path)
        }
    }

    private fun assertSameMask(expected: AndroidPath, actual: Path) {
        val difference = rasterRegion(actual.asAndroidPath()).apply {
            op(rasterRegion(expected), Region.Op.XOR)
        }
        assertTrue("native mask differs from its region geometry", difference.isEmpty)
    }

    private fun rasterRegion(path: AndroidPath): Region = Region().apply {
        setPath(path, Region(0, 0, 512, 512))
    }

    private fun expectedPath(regions: List<AdvancedGlassRenderRegion>): AndroidPath = AndroidPath().apply {
        regions.forEach { region ->
            val radii = region.cornerRadiiPx
            addRoundRect(
                region.left,
                region.top,
                region.right,
                region.bottom,
                floatArrayOf(
                    radii.topLeft, radii.topLeft,
                    radii.topRight, radii.topRight,
                    radii.bottomRight, radii.bottomRight,
                    radii.bottomLeft, radii.bottomLeft
                ),
                AndroidPath.Direction.CCW
            )
        }
    }

    private fun firstRegion() = AdvancedGlassRenderRegion(
        left = 20f,
        top = 20f,
        right = 160f,
        bottom = 140f,
        cornerRadiiPx = AdvancedGlassCornerRadii(8f, 16f, 24f, 32f),
        opacity = 0.35f,
        lens = 0.2f
    )

    private fun secondRegion() = AdvancedGlassRenderRegion(
        left = 110f,
        top = 75f,
        right = 245f,
        bottom = 205f,
        cornerRadiiPx = AdvancedGlassCornerRadii(30f, 20f, 10f, 0f),
        opacity = 0.85f,
        lens = 0.7f
    )
}
