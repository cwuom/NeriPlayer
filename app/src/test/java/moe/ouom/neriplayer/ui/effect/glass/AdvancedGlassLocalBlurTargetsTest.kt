package moe.ouom.neriplayer.ui.effect.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGlassLocalBlurTargetsTest {
    @Test
    fun changingPlansKeepsTheExistingWorkingList() {
        val cache = AdvancedGlassLocalBlurTargets()
        val first = plan()
        val next = plan(region().copy(opacity = 0.25f))
        val workingList = cache.resolve(first, 180, 160)

        val changed = cache.resolve(next, 180, 160)

        assertSame("animated plan changes must retain the original working list", workingList, changed)
        assertSame(next.groups.single(), changed.single().group)
    }

    @Test
    fun stablePlanReusesTargetsAcrossBackdropFrames() {
        val cache = AdvancedGlassLocalBlurTargets()
        val plan = plan()
        val initial = cache.resolve(plan, 180, 160)
        val initialTarget = initial.single()
        val initialBounds = initialTarget.inputBounds

        repeat(240) {
            val current = cache.resolve(plan, 180, 160)
            assertSame("unchanged geometry must retain each target", initialTarget, current.single())
            assertSame(initialBounds, current.single().inputBounds)
            assertSame("unchanged geometry must not allocate a target list per frame", initial, current)
        }
    }

    @Test
    fun widerViewportRestoresAGroupThatWasEntirelyClipped() {
        val cache = AdvancedGlassLocalBlurTargets()
        val region = region().copy(left = 160f, right = 190f)
        val plan = plan(region)
        assertTrue(cache.resolve(plan, 100, 160).isEmpty())

        val visible = cache.resolve(plan, 220, 160).single()

        assertSame(plan.groups.single(), visible.group)
        assertEquals(AdvancedGlassLocalBlurBounds(150f, 10f, 200f, 150f), visible.inputBounds)
        assertTrue(cache.resolve(plan, 100, 160).isEmpty())
    }

    @Test
    fun heightChangesReclipTheSamePlan() {
        val cache = AdvancedGlassLocalBlurTargets()
        val plan = plan()
        val initial = cache.resolve(plan, 180, 80).single()
        assertEquals(80f, initial.inputBounds.bottom)

        val expanded = cache.resolve(plan, 180, 200).single()

        assertNotSame(initial, expanded)
        assertEquals(150f, expanded.inputBounds.bottom)
        assertEquals(70f, cache.resolve(plan, 180, 70).single().inputBounds.bottom)
    }

    @Test
    fun regionWeightAndMaskChangesUseTheCurrentGroupWithIdenticalBounds() {
        val region = region()
        val original = plan(region)
        val changes = listOf(
            region.copy(opacity = 0.25f),
            region.copy(lens = 0.75f),
            region.copy(cornerRadiiPx = region.cornerRadiiPx.copy(topLeft = 28f)),
            region.copy(cornerRadiiPx = region.cornerRadiiPx.copy(topRight = 30f)),
            region.copy(cornerRadiiPx = region.cornerRadiiPx.copy(bottomRight = 32f)),
            region.copy(cornerRadiiPx = region.cornerRadiiPx.copy(bottomLeft = 34f))
        )

        changes.forEach { changed ->
            val cache = AdvancedGlassLocalBlurTargets()
            val before = cache.resolve(original, 180, 160).single()
            val nextGroup = original.groups.single().copy(regions = listOf(changed))
            val next = original.copy(groups = listOf(nextGroup))

            val after = cache.resolve(next, 180, 160).single()

            assertSame(nextGroup, after.group)
            assertEquals(listOf(changed), after.group.regions)
            assertEquals(before.inputBounds, after.inputBounds)
        }
    }

    @Test
    fun reorderedAddedRemovedAndEmptyGroupsNeverRetainOldTargets() {
        val cache = AdvancedGlassLocalBlurTargets()
        val first = plan().groups.single()
        val second = plan(region().copy(left = 210f, right = 280f)).groups.single()
        val third = plan(region().copy(top = 220f, bottom = 270f)).groups.single()
        val original = plan().copy(groups = listOf(first, second))
        cache.resolve(original, 400, 400)

        listOf(
            listOf(second, first),
            listOf(second, third, first),
            listOf(third),
            emptyList(),
            listOf(first)
        ).forEach { groups ->
            val next = original.copy(groups = groups)
            val actual = cache.resolve(next, 400, 400)
            assertEquals(groups, actual.map { it.group })
            actual.forEachIndexed { index, target -> assertSame(groups[index], target.group) }
        }
    }

    @Test
    fun geometryMatchesTheOriginalExpansionAndIntersectionAcrossPlanChanges() {
        val cache = AdvancedGlassLocalBlurTargets()
        val original = plan()
        val bounds = listOf(
            AdvancedGlassLocalBlurBounds(-10.25f, 10.5f, 95.75f, 160.5f),
            AdvancedGlassLocalBlurBounds(105f, 5f, 120f, 25f),
            AdvancedGlassLocalBlurBounds(-120f, -100f, -60f, -40f),
            AdvancedGlassLocalBlurBounds(10f, 10f, 70f, 80f)
        )

        for (padding in listOf(0f, 6.25f, 24f)) {
            for (width in listOf(0, 64, 100, 180)) {
                for (height in listOf(0, 72, 150, 240)) {
                    val next = original.copy(
                        inputPaddingPx = padding,
                        groups = bounds.map { original.groups.single().copy(bounds = it) }
                    )
                    val expected = next.groups.mapNotNull { group ->
                        group.bounds.expand(padding).intersect(0f, 0f, width.toFloat(), height.toFloat())
                            ?.let { group to it }
                    }
                    val actual = cache.resolve(next, width, height)
                    assertEquals(expected, actual.map { it.group to it.inputBounds })
                }
            }
        }
    }

    @Test
    fun equalButNewPlansKeepTheirOwnCurrentGroupReferences() {
        val cache = AdvancedGlassLocalBlurTargets()
        val first = plan()
        val second = first.copy(groups = first.groups.map { it.copy(regions = it.regions.toList()) })
        assertEquals(first, second)
        cache.resolve(first, 180, 160)

        val current = cache.resolve(second, 180, 160)

        assertSame(second.groups.single(), current.single().group)
    }

    private fun plan(region: AdvancedGlassRenderRegion = region()) = AdvancedGlassLocalBlurPlan(
        radiusPx = 5f,
        downscaleFactor = 1,
        inputPaddingPx = 10f,
        groups = listOf(
            AdvancedGlassLocalBlurGroup(
                bounds = AdvancedGlassLocalBlurBounds(region.left, region.top, region.right, region.bottom),
                regions = listOf(region)
            )
        )
    )

    private fun region() = AdvancedGlassRenderRegion(
        left = 20f,
        top = 20f,
        right = 140f,
        bottom = 140f,
        cornerRadiiPx = AdvancedGlassCornerRadii(8f, 12f, 16f, 20f),
        opacity = 0.6f,
        lens = 0.2f
    )
}
