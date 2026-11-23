package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.ui.screen.nowplaying.cover.nowPlayingCoverRestScale
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingPrimaryGlyphRatio
import moe.ouom.neriplayer.ui.screen.nowplaying.nowPlayingWaitingIndicatorSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingCoverRestScaleTest {

    @Test
    fun `cover settles smaller only while paused`() {
        assertTrue(nowPlayingCoverRestScale(playing = false, wideLandscape = false) < 1f)
        assertEquals(1f, nowPlayingCoverRestScale(playing = true, wideLandscape = false), 0f)
    }

    @Test
    fun `wide landscape keeps the cover still beside the lyrics`() {
        assertEquals(1f, nowPlayingCoverRestScale(playing = false, wideLandscape = true), 0f)
    }

    @Test
    fun `clean glyph fills more of the key because it has no container`() {
        assertTrue(nowPlayingPrimaryGlyphRatio(clean = true) > nowPlayingPrimaryGlyphRatio(clean = false))
    }

    @Test
    fun `waiting spinner stays modest even on the enlarged primary key`() {
        assertEquals(26.dp, nowPlayingWaitingIndicatorSize(96.dp))
        assertEquals(23.04f, nowPlayingWaitingIndicatorSize(64.dp).value, 0.01f)
        assertEquals(18.dp, nowPlayingWaitingIndicatorSize(36.dp))
    }
}
