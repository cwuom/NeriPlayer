package moe.ouom.neriplayer.ui.screen.lyrics

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsScreenPortraitLayoutTest {
    @Test
    fun `only portrait tablets use the reading layout`() {
        assertTrue(isLyricsTabletPortrait(600, isLandscape = false))
        assertTrue(isLyricsTabletPortrait(800, isLandscape = false))
        assertFalse(isLyricsTabletPortrait(360, isLandscape = false))
        assertFalse(isLyricsTabletPortrait(800, isLandscape = true))
    }

    @Test
    fun `large portrait tablets align the header and reading column with compact controls`() {
        assertEquals(
            LyricsTabletPortraitWidths(560.dp, 560.dp, 440.dp, 400.dp),
            resolveLyricsTabletPortraitWidths(1000.dp)
        )
    }

    @Test
    fun `narrow windows never request widths outside the available content`() {
        assertEquals(
            LyricsTabletPortraitWidths(320.dp, 320.dp, 320.dp, 320.dp),
            resolveLyricsTabletPortraitWidths(320.dp)
        )
        assertEquals(
            LyricsTabletPortraitWidths(0.dp, 0.dp, 0.dp, 0.dp),
            resolveLyricsTabletPortraitWidths((-20).dp)
        )
    }
}
