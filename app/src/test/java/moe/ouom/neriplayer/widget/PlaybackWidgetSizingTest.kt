package moe.ouom.neriplayer.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWidgetSizingTest {
    @Test
    fun `strip remains usable before the host reports its options`() {
        assertEquals(PlaybackWidgetSize(250, 56), playbackWidgetSizeFromOptions(null, false, isStrip = true))
    }

    @Test
    fun `full widget fills the actual host and scales information density`() {
        val minimum = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 250, heightDp = 110),
            hasProgress = true,
        )
        val oversized = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 420, heightDp = 240),
            hasProgress = true,
        )

        assertEquals(110, minimum.cardHeightDp)
        assertEquals(240, oversized.cardHeightDp)
        assertEquals(32, minimum.albumSizeDp)
        assertEquals(44, minimum.controlsHeightDp)
        assertEquals(24, minimum.controlSizeDp)
        assertEquals(36, minimum.primaryControlSizeDp)
        assertEquals(14, minimum.progressRowHeightDp)
        assertEquals(16f, minimum.titleTextSizeSp)
        assertTrue(minimum.usesFullWidthControls)
        assertTrue(oversized.albumSizeDp > minimum.albumSizeDp)
        assertEquals(80, oversized.albumSizeDp)
        val medium = playbackWidgetLayoutSpec(PlaybackWidgetSize(340, 160), hasProgress = true)
        assertEquals(64, medium.albumSizeDp)
        assertEquals(8, medium.topPaddingDp)
        val narrow = playbackWidgetLayoutSpec(PlaybackWidgetSize(250, 240), hasProgress = true)
        assertEquals(63, narrow.albumSizeDp)
        assertTrue(oversized.controlsHeightDp >= minimum.controlsHeightDp)
    }

    @Test
    fun `full widget keeps a usable minimum footprint`() {
        val spec = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 1, heightDp = 1),
            hasProgress = true,
        )

        assertEquals(1, spec.cardHeightDp)
        assertEquals(14, spec.horizontalPaddingDp)
        assertEquals(8, spec.topPaddingDp)
        assertEquals(4, spec.bottomPaddingDp)
        assertEquals(28, spec.albumSizeDp)
        assertEquals(24, spec.controlSizeDp)
        assertEquals(36, spec.primaryControlSizeDp)
        assertEquals(14, spec.progressRowHeightDp)
        assertTrue(spec.usesFullWidthControls)
    }

    @Test
    fun `compact widget uses tighter padding and three control sizing`() {
        val minimum = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 110, heightDp = 110),
            hasProgress = false,
        )
        val large = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 300, heightDp = 200),
            hasProgress = false,
        )

        assertEquals(6, minimum.horizontalPaddingDp)
        assertTrue(large.horizontalPaddingDp > minimum.horizontalPaddingDp)
        assertEquals(44, large.controlsHeightDp)
        assertEquals(28, large.controlSizeDp)
        assertEquals(0, minimum.albumSizeDp)
        assertEquals(0, large.progressRowHeightDp)
        assertTrue(!large.usesFullWidthControls)

        val tallNarrow = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 110, heightDp = 200),
            hasProgress = false,
        )
        assertTrue(tallNarrow.controlSizeDp <= 36 - tallNarrow.controlGapDp)
    }

    @Test
    fun `widget sizing uses the first valid orientation dimension`() {
        assertEquals(
            250,
            resolvePlaybackWidgetDimension(minDp = 250, maxDp = 344, defaultDp = 250),
        )
        assertEquals(
            110,
            resolvePlaybackWidgetDimension(minDp = 110, maxDp = 190, defaultDp = 110),
        )
        assertEquals(
            110,
            resolvePlaybackWidgetDimension(minDp = 0, maxDp = 0, defaultDp = 110),
        )
    }

    @Test
    fun `full card never exceeds a short host`() {
        assertEquals(
            1,
            fullPlaybackWidgetCardHeightDp(
                PlaybackWidgetSize(widthDp = 420, heightDp = 1),
            ),
        )
    }

    @Test
    fun `full widget uses the larger card only after a compatible vertical resize`() {
        assertFalse(
            shouldUseExpandedFullPlaybackWidgetLayout(
                size = PlaybackWidgetSize(widthDp = 250, heightDp = 110),
            ),
        )
        assertFalse(
            shouldUseExpandedFullPlaybackWidgetLayout(
                size = PlaybackWidgetSize(widthDp = 250, heightDp = 179),
            ),
        )
        assertTrue(
            shouldUseExpandedFullPlaybackWidgetLayout(
                size = PlaybackWidgetSize(widthDp = 250, heightDp = 180),
            ),
        )
        assertTrue(
            shouldUseExpandedFullPlaybackWidgetLayout(
                size = PlaybackWidgetSize(widthDp = 250, heightDp = 180),
            ),
        )
    }
}
