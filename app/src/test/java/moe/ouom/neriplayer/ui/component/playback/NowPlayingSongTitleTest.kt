package moe.ouom.neriplayer.ui.component.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingSongTitleTest {
    @Test
    fun `only enabled overflowing titles need a bounce distance`() {
        assertEquals(
            0,
            songTitleBounceDistancePx(
                marqueeEnabled = false,
                contentWidthPx = 320,
                viewportWidthPx = 200
            )
        )
        assertEquals(
            0,
            songTitleBounceDistancePx(
                marqueeEnabled = true,
                contentWidthPx = 200,
                viewportWidthPx = 200
            )
        )
        assertEquals(
            120,
            songTitleBounceDistancePx(
                marqueeEnabled = true,
                contentWidthPx = 320,
                viewportWidthPx = 200
            )
        )
    }

    @Test
    fun `bounce travel duration follows distance within animation bounds`() {
        assertEquals(900, songTitleBounceTravelDurationMillis(0, 36f))
        assertEquals(1_000, songTitleBounceTravelDurationMillis(36, 36f))
        assertEquals(5_000, songTitleBounceTravelDurationMillis(10_000, 36f))
    }
}
