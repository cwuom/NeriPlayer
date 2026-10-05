package moe.ouom.neriplayer.ui.navigation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDragBoundsOwnerTest {
    @Test
    fun unmeasuredControlsHaveNoDragBoundary() {
        assertEquals(
            AppDragBounds(null, null),
            AppDragBoundsOwner().visibleBounds(hasSong = true, showNowPlaying = false)
        )
    }

    @Test
    fun measuredVisibleControlsExposeTheirRootBounds() {
        val owner = measuredOwner()

        assertEquals(
            AppDragBounds(MiniPlayerBounds, TabBarBounds),
            owner.visibleBounds(hasSong = true, showNowPlaying = false)
        )
    }

    @Test
    fun noSongRemovesOnlyTheMiniPlayerBoundary() {
        assertEquals(
            AppDragBounds(null, TabBarBounds),
            measuredOwner().visibleBounds(hasSong = false, showNowPlaying = false)
        )
    }

    @Test
    fun nowPlayingHidesBothBoundariesWithOrWithoutASong() {
        val owner = measuredOwner()

        for (hasSong in listOf(false, true)) {
            assertEquals(
                AppDragBounds(null, null),
                owner.visibleBounds(hasSong = hasSong, showNowPlaying = true)
            )
        }
    }

    @Test
    fun returningFromNowPlayingRestoresMeasuredBoundaries() {
        val owner = measuredOwner()

        assertEquals(AppDragBounds(null, null), owner.visibleBounds(true, true))
        assertEquals(AppDragBounds(MiniPlayerBounds, TabBarBounds), owner.visibleBounds(true, false))
        assertEquals(AppDragBounds(null, TabBarBounds), owner.visibleBounds(false, false))
        assertEquals(AppDragBounds(MiniPlayerBounds, TabBarBounds), owner.visibleBounds(true, false))
    }

    @Test
    fun measurementsWhileHiddenBecomeVisibleWhenControlsReturn() {
        val owner = measuredOwner()
        val nextMiniPlayer = MiniPlayerBounds.translate(0f, -20f)
        val nextTabBar = TabBarBounds.translate(0f, -40f)

        assertEquals(AppDragBounds(null, null), owner.visibleBounds(true, true))
        owner.updateMiniPlayerBounds(nextMiniPlayer)
        owner.updateTabBarBounds(nextTabBar)

        assertEquals(AppDragBounds(null, null), owner.visibleBounds(true, true))
        assertEquals(AppDragBounds(nextMiniPlayer, nextTabBar), owner.visibleBounds(true, false))
    }

    @Test
    fun zeroOrInvertedBoundsCannotBecomeDragBoundaries() {
        val owner = measuredOwner()
        val emptyBounds = listOf(
            Rect.Zero,
            Rect(10f, 20f, 10f, 40f),
            Rect(10f, 20f, 30f, 20f),
            Rect(30f, 20f, 10f, 40f),
            Rect(10f, 40f, 30f, 20f)
        )

        for (bounds in emptyBounds) {
            owner.updateMiniPlayerBounds(bounds)
            owner.updateTabBarBounds(TabBarBounds)
            assertEquals(AppDragBounds(null, TabBarBounds), owner.visibleBounds(true, false))

            owner.updateMiniPlayerBounds(MiniPlayerBounds)
            owner.updateTabBarBounds(bounds)
            assertEquals(AppDragBounds(MiniPlayerBounds, null), owner.visibleBounds(true, false))
        }
    }

    @Test
    fun anEmptyRemeasurementReplacesThePreviousBoundary() {
        val owner = measuredOwner()
        owner.updateMiniPlayerBounds(Rect.Zero)
        owner.updateTabBarBounds(Rect.Zero)

        assertEquals(AppDragBounds(null, null), owner.visibleBounds(true, false))
        owner.updateTabBarBounds(TabBarBounds)
        assertNull(owner.visibleBounds(true, false).miniPlayer)
        assertEquals(TabBarBounds, owner.visibleBounds(true, false).bottomTabBar)
    }

    @Test
    fun zeroInsetsPreserveAllRootCoordinates() {
        val bounds = Rect(100f, 200f, 420f, 880f)

        assertEquals(bounds, resolveBottomTabDragBounds(bounds, 0, 0, 0))
    }

    @Test
    fun systemInsetsRemoveOnlyTheSidesAndBottomOfTheTabBounds() {
        assertEquals(
            Rect(112f, 200f, 390f, 860f),
            resolveBottomTabDragBounds(Rect(100f, 200f, 420f, 880f), 12, 30, 20)
        )
    }

    @Test
    fun excessiveInsetsCollapseBoundsInsteadOfInvertingThem() {
        val bounds = resolveBottomTabDragBounds(Rect(100f, 200f, 420f, 880f), 400, 500, 800)

        assertEquals(Rect(500f, 200f, 500f, 200f), bounds)
        val owner = measuredOwner()
        owner.updateTabBarBounds(bounds)
        assertEquals(AppDragBounds(MiniPlayerBounds, null), owner.visibleBounds(true, false))
    }

    @Test
    fun layoutResolvedLeftAndRightInsetsKeepTheirPhysicalSides() {
        val bounds = Rect(100f, 200f, 420f, 880f)

        assertEquals(Rect(112f, 200f, 390f, 860f), resolveBottomTabDragBounds(bounds, 12, 30, 20))
        assertEquals(Rect(130f, 200f, 408f, 860f), resolveBottomTabDragBounds(bounds, 30, 12, 20))
    }

    private fun measuredOwner(): AppDragBoundsOwner = AppDragBoundsOwner().apply {
        updateMiniPlayerBounds(MiniPlayerBounds)
        updateTabBarBounds(TabBarBounds)
    }

    private companion object {
        val MiniPlayerBounds = Rect(12f, 720f, 408f, 784f)
        val TabBarBounds = Rect(0f, 796f, 420f, 864f)
    }
}
