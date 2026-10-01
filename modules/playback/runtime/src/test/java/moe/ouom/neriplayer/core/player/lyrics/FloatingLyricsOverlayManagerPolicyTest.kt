package moe.ouom.neriplayer.core.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLyricsOverlayManagerPolicyTest {

    @Test
    fun landscapeLayoutUsesTheWiderScreenDimension() {
        assertFalse(isFloatingLyricsLandscape(screenWidthPx = 1080, screenHeightPx = 2400))
        assertTrue(isFloatingLyricsLandscape(screenWidthPx = 2400, screenHeightPx = 1080))
        assertFalse(isFloatingLyricsLandscape(screenWidthPx = 1200, screenHeightPx = 1200))
    }

    @Test
    fun draggedPositionIsNormalizedInsideVisibleRanges() {
        val position = resolveFloatingLyricsDragPosition(
            xPx = 300,
            yPx = 620,
            horizontalRangePx = 600,
            verticalRange = -80..1320
        )

        assertEquals(0.5f, position.x, 0.001f)
        assertEquals(0.5f, position.y, 0.001f)
    }

    @Test
    fun draggedPositionClampsWhenTheOverlayReachesAScreenEdge() {
        val position = resolveFloatingLyricsDragPosition(
            xPx = -40,
            yPx = 1600,
            horizontalRangePx = 600,
            verticalRange = -80..1320
        )

        assertEquals(0f, position.x, 0.001f)
        assertEquals(1f, position.y, 0.001f)
    }
}
