package moe.ouom.neriplayer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            MainTabDetailHandoff.OPEN_DETAIL,
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Home.route,
                targetRoute = Destinations.NeteaseAlbumDetail.route
            )
        )
        assertEquals(
            MainTabDetailHandoff.RETURN_TO_TAB,
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.PlaybackStats.route,
                targetRoute = Destinations.Library.route
            )
        )
        assertEquals(
            MainTabDetailHandoff.RETURN_TO_TAB,
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.NeteaseAlbumDetail.route,
                targetRoute = Destinations.Home.route
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
                targetRoute = "unknown_detail"
            )
        )
        assertNull(
            resolveMainTabDetailHandoff(
                initialRoute = Destinations.Library.route,
                targetRoute = null
            )
        )
    }

    @Test
    fun `transparent details move main tab content out of the viewport`() {
        assertEquals(
            -1f,
            resolveMainTabDetailContentOffsetTarget(Destinations.Recent.route),
            0f
        )
        assertEquals(
            -1f,
            resolveMainTabDetailContentOffsetTarget(Destinations.PlaybackStats.route),
            0f
        )
        assertEquals(
            -1f,
            resolveMainTabDetailContentOffsetTarget(Destinations.NeteaseAlbumDetail.route),
            0f
        )
        assertEquals(
            0f,
            resolveMainTabDetailContentOffsetTarget(Destinations.Library.route),
            0f
        )
        assertEquals(
            MAIN_TAB_DETAIL_OPEN_DURATION_MS,
            resolveMainTabDetailContentOffsetDurationMillis(-1f)
        )
        assertEquals(
            MAIN_TAB_DETAIL_CLOSE_DURATION_MS,
            resolveMainTabDetailContentOffsetDurationMillis(0f)
        )
    }

    @Test
    fun `detail handoff curve is nonlinear like playlist opening motion`() {
        val midpoint = mainTabDetailContentOffsetEasing().transform(0.5f)

        assertEquals(
            FastOutSlowInEasing.transform(0.5f),
            midpoint,
            0f
        )
        assertTrue(
            "Detail handoff easing must be nonlinear",
            midpoint != 0.5f
        )
    }
}
