package moe.ouom.neriplayer.ui

import moe.ouom.neriplayer.ui.navigation.shouldShowMiniPlayer
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
}
