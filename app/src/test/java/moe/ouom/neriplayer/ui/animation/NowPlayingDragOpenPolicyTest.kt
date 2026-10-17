package moe.ouom.neriplayer.ui.animation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingDragOpenPolicyTest {
    @Test
    fun opensPastThresholdOrOnUpwardFling() {
        assertTrue(shouldOpenNowPlayingFromDrag(progress = 0.4f, velocityY = 0f))
        assertTrue(shouldOpenNowPlayingFromDrag(progress = 0.1f, velocityY = -1500f))
        assertFalse(shouldOpenNowPlayingFromDrag(progress = 0.2f, velocityY = -200f))
    }

    @Test
    fun downwardFlingCancelsEvenWhenFarOpen() {
        assertFalse(shouldOpenNowPlayingFromDrag(progress = 0.7f, velocityY = 1200f))
    }

    @Test
    fun tinyFlickDoesNotOpen() {
        assertFalse(shouldOpenNowPlayingFromDrag(progress = 0.01f, velocityY = -2000f))
    }
}
