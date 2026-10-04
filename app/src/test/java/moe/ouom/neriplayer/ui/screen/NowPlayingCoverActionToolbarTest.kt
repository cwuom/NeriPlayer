package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import moe.ouom.neriplayer.ui.screen.nowplaying.PlaybackActionToolbarLayout
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverToolbarLayoutSpec
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.toolbarPreferredPadding
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.toolbarRowArrangement
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.toolbarRowVerticalPadding

class NowPlayingCoverActionToolbarTest {
    @Test
    fun `toolbar padding preserves compact and docked layouts`() {
        assertEquals(0.dp, toolbarPreferredPadding(spec(compact = true)))
        assertEquals(6.dp, toolbarPreferredPadding(spec()))
        assertEquals(18.dp, toolbarPreferredPadding(spec(docked = true)))
        assertEquals(18.dp, toolbarPreferredPadding(spec(wide = true)))
        assertEquals(8.dp, toolbarRowVerticalPadding(spec()))
        assertEquals(12.dp, toolbarRowVerticalPadding(spec(docked = true)))
        assertEquals(12.dp, toolbarRowVerticalPadding(spec(wide = true)))
    }

    @Test
    fun `equal width slots override spaced action arrangement`() {
        assertSame(Arrangement.Start, toolbarRowArrangement(layout(equalSlots = true), spec(docked = true)))
        assertSame(Arrangement.SpaceBetween, toolbarRowArrangement(layout(), spec()))
        assertSame(Arrangement.SpaceEvenly, toolbarRowArrangement(layout(), spec(docked = true)))
        assertSame(Arrangement.SpaceEvenly, toolbarRowArrangement(layout(), spec(wide = true)))
    }

    @Test
    fun `short landscape removes decorative padding while keeping action slots`() {
        val compactLandscape = spec(wide = true).copy(compactHeight = true)
        assertEquals(0.dp, toolbarPreferredPadding(compactLandscape))
        assertEquals(0.dp, toolbarRowVerticalPadding(compactLandscape))
        assertSame(Arrangement.SpaceEvenly, toolbarRowArrangement(layout(), compactLandscape))
    }

    private fun spec(
        wide: Boolean = false,
        compact: Boolean = false,
        docked: Boolean = false
    ) = NowPlayingCoverToolbarLayoutSpec(wide, compact, docked, 20.dp, 48.dp)

    private fun layout(equalSlots: Boolean = false) =
        PlaybackActionToolbarLayout(6.dp, 48.dp, 20.dp, equalSlots)
}
