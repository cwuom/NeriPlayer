package moe.ouom.neriplayer.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.navigation.DEBUG_NAVIGATION_CLOSE_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.DEBUG_NAVIGATION_OPEN_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.DRAWER_DETAIL_CLOSE_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.DRAWER_DETAIL_OPEN_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.MAIN_TAB_DETAIL_CLOSE_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.MAIN_TAB_DETAIL_OPEN_DURATION_MS
import moe.ouom.neriplayer.ui.navigation.MainTabBackgroundMotion
import moe.ouom.neriplayer.ui.navigation.debugNavigationEnterTransition
import moe.ouom.neriplayer.ui.navigation.debugNavigationExitTransition
import moe.ouom.neriplayer.ui.navigation.mainTabEnterTransition
import moe.ouom.neriplayer.ui.navigation.mainTabExitTransition
import moe.ouom.neriplayer.ui.navigation.resolveMainStartDestination
import moe.ouom.neriplayer.ui.navigation.resolveMainTabBackgroundMotion
import moe.ouom.neriplayer.ui.navigation.resolveMainTabBackgroundMotionDurationMillis
import moe.ouom.neriplayer.ui.navigation.resolveMainTabNavigationMotionState
import moe.ouom.neriplayer.ui.navigation.resolveMainTabNavigationMotionTarget
import moe.ouom.neriplayer.ui.navigation.resolveMainTabTransitionDirection
import moe.ouom.neriplayer.ui.navigation.shouldAcceptObservedMainTabRoute
import moe.ouom.neriplayer.ui.navigation.transparentDetailEnterTransition
import moe.ouom.neriplayer.ui.navigation.transparentDetailExitTransition
import moe.ouom.neriplayer.ui.navigation.transparentDetailPopEnterTransition
import moe.ouom.neriplayer.ui.navigation.transparentDetailPopExitTransition
import moe.ouom.neriplayer.ui.navigation.transparentNavigationDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class MainTabNavigationMotionTest {
    @Test
    fun `navigation depth distinguishes nested details and debug tools`() {
        listOf(
            null to 0,
            Destinations.Home.route to 0,
            Destinations.Debug.route to 0,
            Destinations.DebugListenTogether.route to 1,
            Destinations.DebugLogViewer.route to 2,
            Destinations.Recent.route to 1,
            Destinations.NeteaseAlbumDetail.route to 2,
            Destinations.YouTubeMusicPlaylistDetail.route to 2,
            Destinations.DownloadProgress.route to 2
        ).forEach { (route, depth) ->
            assertEquals(route, depth, transparentNavigationDepth(route))
        }
    }

    @Test
    fun `tab direction rejects missing and unchanged routes`() {
        assertEquals(
            -1,
            resolveMainTabTransitionDirection(
                Destinations.Settings.route,
                Destinations.Home.route
            )
        )
        assertEquals(
            1,
            resolveMainTabTransitionDirection(
                Destinations.Home.route,
                Destinations.Settings.route
            )
        )
        listOf(
            null to Destinations.Home.route,
            Destinations.Home.route to null,
            Destinations.Home.route to Destinations.Home.route
        ).forEach { (initial, target) ->
            assertEquals(null, resolveMainTabTransitionDirection(initial, target))
        }
    }

    @Test
    fun `stale observed tab never overrides a pending newer tab`() {
        assertFalse(shouldAcceptObservedMainTabRoute(null, null))
        assertFalse(shouldAcceptObservedMainTabRoute(Destinations.Recent.route, null))
        assertFalse(
            shouldAcceptObservedMainTabRoute(
                Destinations.Explore.route,
                Destinations.Library.route
            )
        )
        assertTrue(shouldAcceptObservedMainTabRoute(Destinations.Explore.route, null))
        assertTrue(
            shouldAcceptObservedMainTabRoute(
                Destinations.Explore.route,
                Destinations.Explore.route
            )
        )
    }

    @Test
    fun `startup tab respects home visibility and developer access`() {
        assertEquals(
            Destinations.Explore.route,
            resolveMainStartDestination(Destinations.Home.route, false, false)
        )
        assertEquals(
            Destinations.Home.route,
            resolveMainStartDestination(Destinations.Home.route, true, false)
        )
        assertEquals(
            Destinations.Debug.route,
            resolveMainStartDestination(Destinations.Debug.route, true, true)
        )
        assertEquals(
            Destinations.Explore.route,
            resolveMainStartDestination(Destinations.Debug.route, false, false)
        )
        assertEquals(
            Destinations.Library.route,
            resolveMainStartDestination(Destinations.Library.route, false, false)
        )
    }

    @Test
    fun `debug and detail backgrounds share the same motion policy`() {
        listOf(
            Destinations.DebugLogsList.route,
            Destinations.PlaylistDetail.route
        ).forEach { route ->
            assertEquals(
                MainTabBackgroundMotion.COHERENT_EXIT,
                resolveMainTabBackgroundMotion(route, coherentFeedbackEnabled = true)
            )
            assertEquals(
                MainTabBackgroundMotion.DRAWER_SINK,
                resolveMainTabBackgroundMotion(route, coherentFeedbackEnabled = false)
            )
        }
        assertEquals(
            MainTabBackgroundMotion.NONE,
            resolveMainTabBackgroundMotion(Destinations.Home.route, coherentFeedbackEnabled = true)
        )
    }

    @Test
    fun `each background style has paired open and close duration`() {
        val durations = listOf(
            Triple(true, true, DEBUG_NAVIGATION_OPEN_DURATION_MS to DEBUG_NAVIGATION_CLOSE_DURATION_MS),
            Triple(true, false, MAIN_TAB_DETAIL_OPEN_DURATION_MS to MAIN_TAB_DETAIL_CLOSE_DURATION_MS),
            Triple(false, false, DRAWER_DETAIL_OPEN_DURATION_MS to DRAWER_DETAIL_CLOSE_DURATION_MS),
            Triple(false, true, DRAWER_DETAIL_OPEN_DURATION_MS to DRAWER_DETAIL_CLOSE_DURATION_MS)
        )
        durations.forEach { (coherent, debugVisible, expected) ->
            assertEquals(
                expected.first,
                resolveMainTabBackgroundMotionDurationMillis(1f, coherent, debugVisible)
            )
            assertEquals(
                expected.second,
                resolveMainTabBackgroundMotionDurationMillis(0f, coherent, debugVisible)
            )
        }
    }

    @Test
    fun `visible detail keeps the handoff while the tab becomes current`() {
        val activeDetail = resolveMainTabNavigationMotionTarget(
            currentRoute = Destinations.Recent.route,
            visibleRoutes = setOf(Destinations.Home.route, Destinations.Recent.route),
            coherentFeedbackEnabled = true
        )
        assertEquals(MainTabBackgroundMotion.COHERENT_EXIT, activeDetail.backgroundMotion)
        assertEquals(1f, activeDetail.targetProgress, 0f)
        assertFalse(activeDetail.debugSceneVisible)

        val returningToTab = resolveMainTabNavigationMotionTarget(
            currentRoute = Destinations.Home.route,
            visibleRoutes = setOf(Destinations.Home.route, Destinations.Recent.route),
            coherentFeedbackEnabled = true
        )
        assertEquals(MainTabBackgroundMotion.COHERENT_EXIT, returningToTab.backgroundMotion)
        assertEquals(0f, returningToTab.targetProgress, 0f)

        val debugChild = resolveMainTabNavigationMotionTarget(
            currentRoute = Destinations.DebugLogsList.route,
            visibleRoutes = setOf(Destinations.Debug.route, Destinations.DebugLogsList.route),
            coherentFeedbackEnabled = false
        )
        assertEquals(MainTabBackgroundMotion.DRAWER_SINK, debugChild.backgroundMotion)
        assertTrue(debugChild.debugSceneVisible)

        val settledTab = resolveMainTabNavigationMotionTarget(
            currentRoute = Destinations.Home.route,
            visibleRoutes = setOf(Destinations.Home.route),
            coherentFeedbackEnabled = true
        )
        assertEquals(MainTabBackgroundMotion.NONE, settledTab.backgroundMotion)
    }

    @Test
    fun `drawer motion leaves the tab layer fixed while coherent handoff moves it`() {
        val coherent = resolveMainTabNavigationMotionState(
            MainTabBackgroundMotion.COHERENT_EXIT,
            progress = 0.5f
        )
        assertEquals(coherent.backgroundTransform, coherent.tabLayerTransform)
        assertEquals(-0.5f, coherent.tabLayerTransform.translationYFraction, 0f)

        val drawer = resolveMainTabNavigationMotionState(
            MainTabBackgroundMotion.DRAWER_SINK,
            progress = 0.5f
        )
        assertNotEquals(drawer.backgroundTransform, drawer.tabLayerTransform)
        assertEquals(0f, drawer.tabLayerTransform.translationYFraction, 0f)
        assertEquals(1f, drawer.tabLayerTransform.scale, 0f)
    }

    @Test
    fun `tab and detail transitions keep the paired handoff`() {
        val tabToTab = transitionScope(Destinations.Home.route, Destinations.Settings.route)
        assertEquals(EnterTransition.None, tabToTab.mainTabEnterTransition())
        assertEquals(ExitTransition.None, tabToTab.mainTabExitTransition())

        val tabToDetail = transitionScope(Destinations.Home.route, Destinations.Recent.route)
        assertEquals(EnterTransition.None, tabToDetail.mainTabEnterTransition())
        assertNotEquals(ExitTransition.None, tabToDetail.mainTabExitTransition())
        assertEquals(
            ExitTransition.None,
            tabToDetail.mainTabExitTransition(coherentFeedbackEnabled = false)
        )

        val detailToTab = transitionScope(Destinations.Recent.route, Destinations.Home.route)
        assertNotEquals(EnterTransition.None, detailToTab.mainTabEnterTransition())
        assertEquals(ExitTransition.None, detailToTab.mainTabExitTransition())
        assertEquals(
            EnterTransition.None,
            detailToTab.mainTabEnterTransition(coherentFeedbackEnabled = false)
        )
    }

    @Test
    fun `debug transitions use both feedback modes and ignore unrelated routes`() {
        val forward = transitionScope(
            Destinations.Debug.route,
            Destinations.DebugLogsList.route
        )
        val backward = transitionScope(
            Destinations.DebugLogsList.route,
            Destinations.Debug.route
        )
        for (scope in listOf(forward, backward)) {
            assertNotEquals(EnterTransition.None, scope.mainTabEnterTransition())
            assertNotEquals(ExitTransition.None, scope.mainTabExitTransition())
            assertNotEquals(
                EnterTransition.None,
                scope.mainTabEnterTransition(coherentFeedbackEnabled = false)
            )
            assertNotEquals(
                ExitTransition.None,
                scope.mainTabExitTransition(coherentFeedbackEnabled = false)
            )
            assertNotEquals(EnterTransition.None, scope.debugNavigationEnterTransition())
            assertNotEquals(ExitTransition.None, scope.debugNavigationExitTransition())
            assertNotEquals(
                EnterTransition.None,
                scope.debugNavigationEnterTransition(coherentFeedbackEnabled = false)
            )
            assertNotEquals(
                ExitTransition.None,
                scope.debugNavigationExitTransition(coherentFeedbackEnabled = false)
            )
        }
        val unrelated = transitionScope(Destinations.Home.route, Destinations.Recent.route)
        assertEquals(EnterTransition.None, unrelated.debugNavigationEnterTransition())
        assertEquals(ExitTransition.None, unrelated.debugNavigationExitTransition())
    }

    @Test
    fun `transparent detail transitions skip the Bili handoff`() {
        val instant = transitionScope(
            Destinations.BiliUploaderDetail.route,
            Destinations.BiliPlaylistDetail.route
        )
        assertEquals(EnterTransition.None, instant.transparentDetailEnterTransition())
        assertEquals(ExitTransition.None, instant.transparentDetailExitTransition())
        assertEquals(EnterTransition.None, instant.transparentDetailPopEnterTransition())
        assertEquals(ExitTransition.None, instant.transparentDetailPopExitTransition())
    }

    @Test
    fun `transparent detail transitions retain coherent and drawer behaviors`() {
        val opening = transitionScope(Destinations.Home.route, Destinations.Recent.route)
        val closing = transitionScope(Destinations.Recent.route, Destinations.Home.route)
        for (scope in listOf(opening, closing)) {
            assertNotEquals(EnterTransition.None, scope.transparentDetailEnterTransition())
            assertNotEquals(
                EnterTransition.None,
                scope.transparentDetailEnterTransition(coherentFeedbackEnabled = false)
            )
            assertNotEquals(
                ExitTransition.None,
                scope.transparentDetailExitTransition(coherentFeedbackEnabled = false)
            )
            assertNotEquals(EnterTransition.None, scope.transparentDetailPopEnterTransition())
            assertNotEquals(
                EnterTransition.None,
                scope.transparentDetailPopEnterTransition(coherentFeedbackEnabled = false)
            )
            assertNotEquals(ExitTransition.None, scope.transparentDetailPopExitTransition())
            assertNotEquals(
                ExitTransition.None,
                scope.transparentDetailPopExitTransition(coherentFeedbackEnabled = false)
            )
        }
        assertNotEquals(ExitTransition.None, closing.transparentDetailExitTransition())
        assertNotEquals(ExitTransition.None, opening.transparentDetailExitTransition())
    }

    @Test
    fun `drawer exits reuse the same retained transition across route families`() {
        val debug = transitionScope(
            Destinations.Debug.route,
            Destinations.DebugLogsList.route
        )
        val detail = transitionScope(Destinations.Home.route, Destinations.Recent.route)
        val retained = debug.mainTabExitTransition(coherentFeedbackEnabled = false)

        assertNotEquals(ExitTransition.None, retained)
        assertSame(retained, debug.debugNavigationExitTransition(coherentFeedbackEnabled = false))
        assertSame(retained, detail.transparentDetailExitTransition(coherentFeedbackEnabled = false))
        assertSame(retained, detail.transparentDetailPopExitTransition(coherentFeedbackEnabled = false))
        assertNotEquals(retained, detail.transparentDetailExitTransition())
    }

    private fun transitionScope(
        initialRoute: String,
        targetRoute: String
    ): AnimatedContentTransitionScope<NavBackStackEntry> {
        val scope = mock(AnimatedContentTransitionScope::class.java)
        val initialEntry = mock(NavBackStackEntry::class.java)
        val targetEntry = mock(NavBackStackEntry::class.java)
        val initialDestination = mock(NavDestination::class.java)
        val targetDestination = mock(NavDestination::class.java)
        `when`(initialDestination.route).thenReturn(initialRoute)
        `when`(targetDestination.route).thenReturn(targetRoute)
        `when`(initialEntry.destination).thenReturn(initialDestination)
        `when`(targetEntry.destination).thenReturn(targetDestination)
        `when`(scope.initialState).thenReturn(initialEntry)
        `when`(scope.targetState).thenReturn(targetEntry)
        // mockito 在运行时擦除了转场范围的状态类型
        @Suppress("UNCHECKED_CAST")
        return scope as AnimatedContentTransitionScope<NavBackStackEntry>
    }
}
