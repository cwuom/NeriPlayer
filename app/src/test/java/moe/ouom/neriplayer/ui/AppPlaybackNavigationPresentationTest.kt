package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.navigation.playbackSourceNavigationAction
import moe.ouom.neriplayer.ui.navigation.shouldSuppressPlaybackNavigation
import moe.ouom.neriplayer.ui.navigation.reservedMiniPlayerHeight
import moe.ouom.neriplayer.ui.navigation.appSnackbarStartInset
import moe.ouom.neriplayer.ui.navigation.AppNavigationRailWidth
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.settings.route.isAppSettingsVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPlaybackNavigationPresentationTest {
    @Test
    fun `page insets reserve the visible player height for the actual device class`() {
        assertEquals(NeriMiniPlayerDefaults.Height, reservedMiniPlayerHeight(true, false, 599))
        assertEquals(NeriMiniPlayerDefaults.TabletHeight, reservedMiniPlayerHeight(true, false, 600))
        for (width in listOf(360, 800)) {
            assertEquals(0.dp, reservedMiniPlayerHeight(false, false, width))
            assertEquals(0.dp, reservedMiniPlayerHeight(false, true, width))
            assertEquals(0.dp, reservedMiniPlayerHeight(true, true, width))
        }
    }

    @Test
    fun `snackbar avoids the visible rail but uses full width over the player`() {
        assertEquals(AppNavigationRailWidth, appSnackbarStartInset(true, false))
        assertEquals(0.dp, appSnackbarStartInset(true, true))
        assertEquals(0.dp, appSnackbarStartInset(false, false))
        assertEquals(0.dp, appSnackbarStartInset(false, true))
    }

    @Test
    fun `phone navigation starts returning while the player exit is still mounted`() {
        for (width in listOf(360, 599)) {
            assertFalse(shouldSuppressPlaybackNavigation(false, false, width))
            assertTrue(shouldSuppressPlaybackNavigation(true, false, width))
            assertTrue(shouldSuppressPlaybackNavigation(true, true, width))
            assertFalse(shouldSuppressPlaybackNavigation(false, true, width))
        }
    }

    @Test
    fun `tablet navigation remains suppressed throughout playback entry and exit`() {
        for (width in listOf(600, 800)) {
            assertFalse(shouldSuppressPlaybackNavigation(false, false, width))
            assertTrue(shouldSuppressPlaybackNavigation(true, false, width))
            assertTrue(shouldSuppressPlaybackNavigation(true, true, width))
            assertTrue(shouldSuppressPlaybackNavigation(false, true, width))
        }
    }

    @Test
    fun `settings side effects run only for the selected tab without the player`() {
        assertTrue(isAppSettingsVisible(Destinations.Settings.route, playbackOpen = false))
        assertFalse(isAppSettingsVisible(Destinations.Settings.route, playbackOpen = true))
        assertFalse(isAppSettingsVisible(Destinations.Home.route, playbackOpen = false))
        assertFalse(isAppSettingsVisible(Destinations.Home.route, playbackOpen = true))
    }

    @Test
    fun `missing playback source does not expose a navigation action`() {
        val visited = mutableListOf<String>()
        assertNull(playbackSourceNavigationAction(null, visited::add))
        assertTrue(visited.isEmpty())
    }

    @Test
    fun `mini and expanded player share deferred navigation to the bound source`() {
        val visited = mutableListOf<String>()
        val route = "playlist/123"
        val action = requireNotNull(playbackSourceNavigationAction(route, visited::add))
        assertTrue(visited.isEmpty())
        action()
        assertEquals(listOf(route), visited)
        val nextAction = requireNotNull(playbackSourceNavigationAction("artist/456", visited::add))
        nextAction()
        assertEquals(listOf(route, "artist/456"), visited)
    }
}
