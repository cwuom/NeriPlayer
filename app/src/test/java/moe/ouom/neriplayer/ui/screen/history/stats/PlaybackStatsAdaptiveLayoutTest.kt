package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatsAdaptiveLayoutTest {
    @Test
    fun `tablet landscape caps dashboard width and leaves room for the ranking`() {
        val layout = resolvePlaybackStatsLayout(1600.dp, tablet = true)
        assertEquals(1200.dp, layout.contentWidth)
        assertTrue(layout.useColumns)
        assertEquals(320.dp, layout.overviewWidth)
        assertTrue(layout.contentWidth - layout.overviewWidth - 24.dp >= 400.dp)
    }

    @Test
    fun `tablet portrait supports columns when both panes fit and stacks in narrower windows`() {
        val portrait = resolvePlaybackStatsLayout(800.dp, tablet = true)
        assertTrue(portrait.useColumns)
        assertTrue(portrait.contentWidth - portrait.overviewWidth - 24.dp >= 400.dp)
        assertFalse(resolvePlaybackStatsLayout(600.dp, tablet = true).useColumns)
    }

    @Test
    fun `wide phones keep the original single column and padding`() {
        val phone = resolvePlaybackStatsLayout(840.dp, tablet = false)
        assertFalse(phone.useColumns)
        assertEquals(8.dp, phone.horizontalPadding)
        assertEquals(824.dp, phone.contentWidth)
    }
}
