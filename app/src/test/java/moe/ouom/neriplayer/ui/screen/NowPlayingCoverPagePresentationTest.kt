package moe.ouom.neriplayer.ui.screen

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingImmersiveCoverSize
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingTrackIdentityLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.resolveNowPlayingCoverPagePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingCoverPagePresentationTest {
    @Test
    fun `regular mode keeps the existing portrait spacing and toolbar lyrics action`() {
        val regular = resolveNowPlayingCoverPagePresentation(immersiveMode = false, lyricsAdjustBehavior = true)

        assertEquals(8.dp, regular.beforeCoverSpacing)
        assertEquals(16.dp, regular.afterCoverSpacing)
        assertNull(regular.preferredCoverSize)
        assertEquals(NowPlayingTrackIdentityLayout.Expanded, regular.trackIdentityLayout)
        assertTrue(regular.lyricsAdjustBehavior)
        assertFalse(resolveNowPlayingCoverPagePresentation(false, false).lyricsAdjustBehavior)
    }

    @Test
    fun `immersive mode expands artwork and keeps the toolbar on the lyrics page action`() {
        val immersive = resolveNowPlayingCoverPagePresentation(immersiveMode = true, lyricsAdjustBehavior = true)

        assertEquals(16.dp, immersive.beforeCoverSpacing)
        assertEquals(28.dp, immersive.afterCoverSpacing)
        assertEquals(NowPlayingImmersiveCoverSize, immersive.preferredCoverSize)
        assertEquals(NowPlayingTrackIdentityLayout.LargeCover, immersive.trackIdentityLayout)
        assertFalse(immersive.lyricsAdjustBehavior)
        assertFalse(resolveNowPlayingCoverPagePresentation(true, false).lyricsAdjustBehavior)
    }
}
