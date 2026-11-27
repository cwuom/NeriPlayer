package moe.ouom.neriplayer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingUnderlayDrawTest {
    @Test
    fun `only a fully settled and visible player covers the underlay`() {
        assertTrue(isNowPlayingUnderlayCovered(true, 0f, null, false, 1f))
        assertFalse(isNowPlayingUnderlayCovered(false, 0f, null, false, 1f))
        assertFalse(isNowPlayingUnderlayCovered(true, 0.2f, null, false, 1f))
        assertFalse(isNowPlayingUnderlayCovered(true, 0f, 0.5f, false, 1f))
        assertFalse(isNowPlayingUnderlayCovered(true, 0f, null, true, 1f))
        assertFalse(isNowPlayingUnderlayCovered(true, 0f, null, false, 0.99f))
        assertFalse(isNowPlayingUnderlayCovered(true, 0f, null, false, null))
    }
}
