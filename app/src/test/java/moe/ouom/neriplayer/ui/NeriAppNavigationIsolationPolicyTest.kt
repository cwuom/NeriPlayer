package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeriAppNavigationIsolationPolicyTest {
    @Test
    fun `every transparent detail route uses paired main tab handoff`() {
        val detailRoutes = listOf(
            Destinations.PlaylistDetail.route,
            Destinations.NeteaseArtistDetail.route,
            Destinations.BiliPlaylistDetail.route,
            Destinations.LocalPlaylistDetail.route,
            Destinations.Recent.route,
            Destinations.PlaybackStats.route,
            Destinations.DownloadManager.route,
            Destinations.DownloadProgress.route
        )

        detailRoutes.forEach { detailRoute ->
            assertEquals(
                detailRoute,
                MainTabDetailHandoff.OPEN_DETAIL,
                resolveMainTabDetailHandoff(
                    initialRoute = Destinations.Home.route,
                    targetRoute = detailRoute
                )
            )
            assertEquals(
                detailRoute,
                MainTabDetailHandoff.RETURN_TO_TAB,
                resolveMainTabDetailHandoff(
                    initialRoute = detailRoute,
                    targetRoute = Destinations.Home.route
                )
            )
        }
    }

    @Test
    fun `debug root and every first level tool use paired handoff`() {
        val firstLevelRoutes = listOf(
            Destinations.DebugListenTogether.route,
            Destinations.DebugUsbExclusive.route,
            Destinations.DebugYouTube.route,
            Destinations.DebugBili.route,
            Destinations.DebugNetease.route,
            Destinations.DebugSearch.route,
            Destinations.DebugLogsList.route,
            Destinations.DebugCrashLogsList.route
        )

        firstLevelRoutes.forEach { detailRoute ->
            assertEquals(
                detailRoute,
                1,
                resolveDebugNavigationTransitionDirection(
                    initialRoute = Destinations.Debug.route,
                    targetRoute = detailRoute
                )
            )
            assertEquals(
                detailRoute,
                -1,
                resolveDebugNavigationTransitionDirection(
                    initialRoute = detailRoute,
                    targetRoute = Destinations.Debug.route
                )
            )
        }
    }

    @Test
    fun `debug log viewer uses a deeper paired handoff`() {
        listOf(
            Destinations.DebugLogsList.route,
            Destinations.DebugCrashLogsList.route
        ).forEach { listRoute ->
            assertEquals(
                1,
                resolveDebugNavigationTransitionDirection(
                    initialRoute = listRoute,
                    targetRoute = Destinations.DebugLogViewer.route
                )
            )
            assertEquals(
                -1,
                resolveDebugNavigationTransitionDirection(
                    initialRoute = Destinations.DebugLogViewer.route,
                    targetRoute = listRoute
                )
            )
        }
    }

    @Test
    fun `same depth and unrelated routes do not animate as debug hierarchy`() {
        assertNull(
            resolveDebugNavigationTransitionDirection(
                initialRoute = Destinations.DebugYouTube.route,
                targetRoute = Destinations.DebugBili.route
            )
        )
        assertNull(
            resolveDebugNavigationTransitionDirection(
                initialRoute = Destinations.Library.route,
                targetRoute = Destinations.DebugListenTogether.route
            )
        )
        assertNull(
            resolveDebugNavigationTransitionDirection(
                initialRoute = null,
                targetRoute = Destinations.Debug.route
            )
        )
    }
}
