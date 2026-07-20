package moe.ouom.neriplayer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.effect.glass.DRAWER_BACKGROUND_SINK_FRACTION
import moe.ouom.neriplayer.ui.effect.glass.DRAWER_RECESSED_CONTENT_SCALE
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
    fun `coherent feedback keeps the existing full height handoff`() {
        val motion = resolveMainTabBackgroundMotion(
            route = Destinations.Recent.route,
            coherentFeedbackEnabled = true
        )
        val transform = resolveMainTabBackgroundTransform(motion, progress = 1f)

        assertEquals(MainTabBackgroundMotion.COHERENT_EXIT, motion)
        assertEquals(-1f, transform.translationYFraction, 0f)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(1f, transform.alpha, 0f)
        assertEquals(
            MAIN_TAB_DETAIL_OPEN_DURATION_MS,
            resolveMainTabBackgroundMotionDurationMillis(
                targetProgress = 1f,
                coherentFeedbackEnabled = true,
                debugSceneVisible = false
            )
        )
    }

    @Test
    fun `default detail motion keeps the page position and scales only its content`() {
        val drawerRoutes = listOf(
            Destinations.PlaylistDetail.route,
            Destinations.BiliPlaylistDetail.route,
            Destinations.LocalPlaylistDetail.route,
            Destinations.Recent.route,
            Destinations.PlaybackStats.route,
            Destinations.DownloadManager.route,
            Destinations.DownloadProgress.route
        )

        drawerRoutes.forEach { route ->
            assertEquals(
                route,
                MainTabBackgroundMotion.DRAWER_SINK,
                resolveMainTabBackgroundMotion(
                    route = route,
                    coherentFeedbackEnabled = false
                )
            )
        }

        val midpoint = resolveMainTabBackgroundTransform(
            motion = MainTabBackgroundMotion.DRAWER_SINK,
            progress = 0.5f
        )
        val settled = resolveMainTabBackgroundTransform(
            motion = MainTabBackgroundMotion.DRAWER_SINK,
            progress = 1f
        )
        assertEquals(DRAWER_BACKGROUND_SINK_FRACTION / 2f, midpoint.translationYFraction, 0f)
        assertEquals(
            1f - (1f - DRAWER_RECESSED_CONTENT_SCALE) / 2f,
            midpoint.scale,
            0f
        )
        assertEquals(1f, midpoint.alpha, 0f)
        assertEquals(DRAWER_BACKGROUND_SINK_FRACTION, settled.translationYFraction, 0f)
        assertEquals(DRAWER_RECESSED_CONTENT_SCALE, settled.scale, 0f)
        assertEquals(1f, settled.alpha, 0f)
        assertEquals(
            DRAWER_DETAIL_OPEN_DURATION_MS,
            resolveMainTabBackgroundMotionDurationMillis(
                targetProgress = 1f,
                coherentFeedbackEnabled = false,
                debugSceneVisible = false
            )
        )
    }

    @Test
    fun `default debug child keeps the debug home content in the drawer background`() {
        val motion = resolveMainTabBackgroundMotion(
            route = Destinations.DebugListenTogether.route,
            coherentFeedbackEnabled = false
        )
        val transform = resolveMainTabBackgroundTransform(motion, progress = 1f)

        assertEquals(MainTabBackgroundMotion.DRAWER_SINK, motion)
        assertEquals(DRAWER_BACKGROUND_SINK_FRACTION, transform.translationYFraction, 0f)
        assertEquals(DRAWER_RECESSED_CONTENT_SCALE, transform.scale, 0f)
        assertEquals(1f, transform.alpha, 0f)
        assertEquals(
            DRAWER_DETAIL_OPEN_DURATION_MS,
            resolveMainTabBackgroundMotionDurationMillis(
                targetProgress = 1f,
                coherentFeedbackEnabled = false,
                debugSceneVisible = true
            )
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
