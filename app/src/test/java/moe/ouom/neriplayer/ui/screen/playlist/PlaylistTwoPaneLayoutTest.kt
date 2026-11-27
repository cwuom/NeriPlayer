package moe.ouom.neriplayer.ui.screen.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTwoPaneLayoutTest {

    @Test
    fun `only landscape tablets split the playlist into two panes`() {
        assertTrue(isPlaylistTwoPaneWindow(widthDp = 1280, heightDp = 800))
        assertTrue(isPlaylistTwoPaneWindow(widthDp = 840, heightDp = 600))
        assertFalse(isPlaylistTwoPaneWindow(widthDp = 800, heightDp = 1280))
        // 横屏手机同样左右分栏, 比 411dp 高的单列能多露出歌曲
        assertTrue(isPlaylistTwoPaneWindow(widthDp = 891, heightDp = 411))
        assertFalse(isPlaylistTwoPaneWindow(widthDp = 411, heightDp = 891))
        assertFalse(isPlaylistTwoPaneWindow(widthDp = 839, heightDp = 600))
    }
}
