package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.ui.navigation.shouldShowMiniPlayer
import moe.ouom.neriplayer.ui.navigation.shouldShowFloatingPlaybackChrome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMiniPlayerVisibilityTest {
    @Test
    fun miniPlayerIsVisibleOnlyWithSongAndCollapsedNowPlaying() {
        assertFalse(shouldShowMiniPlayer(hasSong = false, showNowPlaying = false))
        assertFalse(shouldShowMiniPlayer(hasSong = true, showNowPlaying = true))
        assertTrue(shouldShowMiniPlayer(hasSong = true, showNowPlaying = false))
    }

    @Test
    fun requestedPlaybackChromeStepsAsideForTheKeyboard() {
        assertTrue(shouldShowFloatingPlaybackChrome(requested = true, yieldsToIme = false))
        assertFalse(shouldShowFloatingPlaybackChrome(requested = true, yieldsToIme = true))
    }

    @Test
    fun keyboardDismissalDoesNotRevealChromeThatThePlaybackPageStillHides() {
        assertFalse(shouldShowFloatingPlaybackChrome(requested = false, yieldsToIme = true))
        assertFalse(shouldShowFloatingPlaybackChrome(requested = false, yieldsToIme = false))
    }
}
