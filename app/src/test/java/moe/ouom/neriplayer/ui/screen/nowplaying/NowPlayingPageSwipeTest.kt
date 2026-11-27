package moe.ouom.neriplayer.ui.screen.nowplaying

import androidx.compose.ui.unit.Density
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingPageSwipeDirection.Left
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingPageSwipeDirection.Right
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingPageSwipeTest {

    private val thresholdPx = with(Density(3f)) { NowPlayingPageSwipeThreshold.toPx() }

    @Test
    fun `old 20px and 60px drags near the progress bar no longer switch pages`() {
        listOf(20f, 50f, 60f, 150f).forEach { dragPx ->
            assertFalse("右滑 ${dragPx}px 不应返回", isNowPlayingPageSwipe(dragPx, thresholdPx, Right))
            assertFalse("左滑 ${dragPx}px 不应切到歌词", isNowPlayingPageSwipe(-dragPx, thresholdPx, Left))
        }
    }

    @Test
    fun `deliberate swipe past the threshold switches pages`() {
        assertTrue(isNowPlayingPageSwipe(thresholdPx, thresholdPx, Right))
        assertTrue(isNowPlayingPageSwipe(-thresholdPx, thresholdPx, Left))
        assertTrue(isNowPlayingPageSwipe(thresholdPx * 2, thresholdPx, Right))
    }

    @Test
    fun `swipe in the opposite direction never triggers`() {
        assertFalse(isNowPlayingPageSwipe(-thresholdPx * 2, thresholdPx, Right))
        assertFalse(isNowPlayingPageSwipe(thresholdPx * 2, thresholdPx, Left))
    }

    @Test
    fun `threshold stays at least a quarter of a phone width`() {
        assertTrue(NowPlayingPageSwipeThreshold.value >= 90f)
    }
}
