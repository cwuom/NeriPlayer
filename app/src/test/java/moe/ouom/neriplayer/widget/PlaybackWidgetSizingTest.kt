package moe.ouom.neriplayer.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWidgetSizingTest {
    @Test
    fun `full widget keeps the reference card ratio across reported host sizes`() {
        val minimum = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 250, heightDp = 110),
            hasProgress = true,
        )
        val oversized = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 420, heightDp = 240),
            hasProgress = true,
        )

        assertEquals(170, minimum.cardHeightDp)
        assertEquals(180, oversized.cardHeightDp)
        assertEquals(80, minimum.albumSizeDp)
        assertEquals(48, minimum.controlsHeightDp)
        assertEquals(30, minimum.controlSizeDp)
        assertEquals(48, minimum.primaryControlSizeDp)
        assertEquals(14, minimum.progressRowHeightDp)
        assertEquals(18f, minimum.titleTextSizeSp)
        assertTrue(minimum.usesFullWidthControls)
        assertEquals(minimum.albumSizeDp, oversized.albumSizeDp)
        assertEquals(minimum.controlsHeightDp, oversized.controlsHeightDp)
    }

    @Test
    fun `full widget keeps a usable minimum footprint`() {
        val spec = playbackWidgetLayoutSpec(
            size = PlaybackWidgetSize(widthDp = 1, heightDp = 1),
            hasProgress = true,
        )

        assertEquals(170, spec.cardHeightDp)
        assertEquals(14, spec.horizontalPaddingDp)
        assertEquals(20, spec.topPaddingDp)
        assertEquals(8, spec.bottomPaddingDp)
        assertEquals(80, spec.albumSizeDp)
        assertEquals(30, spec.controlSizeDp)
        assertEquals(48, spec.primaryControlSizeDp)
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

        assertEquals(10, minimum.horizontalPaddingDp)
        assertTrue(large.horizontalPaddingDp > minimum.horizontalPaddingDp)
        assertTrue(large.controlsHeightDp > minimum.controlsHeightDp)
        assertTrue(large.controlSizeDp > minimum.controlSizeDp)
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
    fun `widget sizing uses the expanded option dimension when available`() {
        assertEquals(
            344,
            resolvePlaybackWidgetDimension(minDp = 250, maxDp = 344, defaultDp = 250),
        )
        assertEquals(
            190,
            resolvePlaybackWidgetDimension(minDp = 110, maxDp = 190, defaultDp = 110),
        )
        assertEquals(
            110,
            resolvePlaybackWidgetDimension(minDp = 0, maxDp = 0, defaultDp = 110),
        )
    }

    @Test
    fun `full card height is derived from width instead of host height`() {
        assertEquals(
            180,
            fullPlaybackWidgetCardHeightDp(
                PlaybackWidgetSize(widthDp = 420, heightDp = 1),
            ),
        )
    }
}
