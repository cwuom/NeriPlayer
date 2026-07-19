package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeriAppMainTabTransitionPolicyTest {
    @Test
    fun `later tabs enter from the right`() {
        assertEquals(
            1,
            resolveMainTabTransitionDirection(
                initialRoute = Destinations.Explore.route,
                targetRoute = Destinations.Settings.route
            )
        )
    }

    @Test
    fun `earlier tabs enter from the left`() {
        assertEquals(
            -1,
            resolveMainTabTransitionDirection(
                initialRoute = Destinations.Settings.route,
                targetRoute = Destinations.Library.route
            )
        )
    }

    @Test
    fun `non tab and same tab navigation do not use tab slide`() {
        assertNull(
            resolveMainTabTransitionDirection(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.Library.route
            )
        )
        assertNull(
            resolveMainTabTransitionDirection(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.PlaybackStats.route
            )
        )
    }

    @Test
    fun `main tab and transparent detail use paired vertical handoff`() {
        assertEquals(
            MainTabDetailHandoff.OPEN_DETAIL,
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.Recent.route
            )
        )
        assertEquals(
            MainTabDetailHandoff.OPEN_DETAIL,
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.PlaybackStats.route
            )
        )
        assertEquals(
            MainTabDetailHandoff.RETURN_TO_TAB,
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.PlaybackStats.route,
                targetRoute = Destinations.Library.route
            )
        )
    }

    @Test
    fun `tab slide and incomplete routes do not use detail handoff`() {
        assertNull(
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.Settings.route
            )
        )
        assertNull(
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.Library.route
            )
        )
        assertNull(
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.LocalPlaylistDetail.route
            )
        )
        assertNull(
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Library.route,
                targetRoute = null
            )
        )
    }
}
