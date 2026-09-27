package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationScenePresentationTest {
    @Test
    fun visibilityMotionDistinguishesUnderlyingAndExitingScenes() {
        val playlist = Destinations.PlaylistDetail.route
        val album = Destinations.NeteaseAlbumDetail.route

        val underlying = navHostSceneMotionIntent(
            sceneRoute = playlist,
            currentRoute = playlist,
            visibleRoutes = setOf(playlist, album)
        )
        assertEquals(1, underlying.sceneDepth)
        assertTrue(underlying.enteringFromDeeperScene)
        assertFalse(underlying.exitingToDeeperScene)

        val exiting = navHostSceneMotionIntent(
            sceneRoute = playlist,
            currentRoute = album,
            visibleRoutes = setOf(playlist, album)
        )
        assertFalse(exiting.enteringFromDeeperScene)
        assertTrue(exiting.exitingToDeeperScene)
    }

    @Test
    fun drawerMotionOnlyMovesRootBackgroundWhileCustomWallpaperStaysFixed() {
        val motion = MainTabNavigationMotionState(
            backgroundMotion = MainTabBackgroundMotion.DRAWER_SINK,
            backgroundTransform = MainTabBackgroundTransform(0.2f, 0.9f, 1f),
            tabLayerTransform = MainTabBackgroundTransform(0f, 1f, 1f)
        )
        val root = mainTabScenePresentation(0.1f, 0.8f, 0, true, motion)
        assertEquals(0.3f, root.translationYFraction, 0.0001f)
        assertEquals(0.72f, root.scale, 0.0001f)
        assertTrue(root.fixedBackground)

        val detail = mainTabScenePresentation(0.1f, 0.8f, 1, true, motion)
        assertFalse(detail.fixedBackground)
        val noDrawer = mainTabScenePresentation(
            0.1f, 0.8f, 0, false, motion.copy(backgroundMotion = MainTabBackgroundMotion.NONE)
        )
        assertEquals(0.1f, noDrawer.translationYFraction, 0.0001f)
        assertEquals(0.8f, noDrawer.scale, 0.0001f)
    }
}
