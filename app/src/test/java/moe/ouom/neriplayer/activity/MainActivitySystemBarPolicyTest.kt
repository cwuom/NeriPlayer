package moe.ouom.neriplayer.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySystemBarPolicyTest {

    @Test
    fun `normal landscape tablets hide only the navigation bar`() {
        assertTrue(shouldHideTabletNavigationBar(600, isLandscape = true, safeModeActive = false))
        assertTrue(shouldHideTabletNavigationBar(800, isLandscape = true, safeModeActive = false))
        assertFalse(shouldHideTabletNavigationBar(599, isLandscape = true, safeModeActive = false))
        assertFalse(shouldHideTabletNavigationBar(360, isLandscape = true, safeModeActive = false))
    }

    @Test
    fun `portrait and safe mode always retain navigation`() {
        for (width in listOf(360, 599, 600, 800)) {
            assertFalse(shouldHideTabletNavigationBar(width, isLandscape = false, safeModeActive = false))
            assertFalse(shouldHideTabletNavigationBar(width, isLandscape = false, safeModeActive = true))
            assertFalse(shouldHideTabletNavigationBar(width, isLandscape = true, safeModeActive = true))
        }
    }

    @Test
    fun `now playing uses light system bar icons in a light app theme`() {
        assertTrue(
            shouldUseLightSystemBarIcons(
                isDarkTheme = false,
                isNowPlayingVisible = true
            )
        )
    }

    @Test
    fun `non player screens continue to follow the app theme`() {
        assertFalse(
            shouldUseLightSystemBarIcons(
                isDarkTheme = false,
                isNowPlayingVisible = false
            )
        )
        assertTrue(
            shouldUseLightSystemBarIcons(
                isDarkTheme = true,
                isNowPlayingVisible = false
            )
        )
    }
}
