package moe.ouom.neriplayer.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSeekPreviewTest {

    @Test
    fun `resolveLyricPreviewTimeMs prioritizes dragging then pending preview then playback`() {
        assertEquals(
            12_000L,
            resolveLyricPreviewTimeMs(
                isDraggingSlider = true,
                sliderPreviewPositionMs = 12_000L,
                pendingSeekPreviewPositionMs = 8_500L,
                playbackPositionMs = 6_000L
            )
        )
        assertEquals(
            8_500L,
            resolveLyricPreviewTimeMs(
                isDraggingSlider = false,
                sliderPreviewPositionMs = 12_000L,
                pendingSeekPreviewPositionMs = 8_500L,
                playbackPositionMs = 6_000L
            )
        )
        assertEquals(
            6_000L,
            resolveLyricPreviewTimeMs(
                isDraggingSlider = false,
                sliderPreviewPositionMs = 12_000L,
                pendingSeekPreviewPositionMs = null,
                playbackPositionMs = 6_000L
            )
        )
    }

    @Test
    fun `shouldReleaseLyricSeekPreview waits until playback catches preview`() {
        assertFalse(
            shouldReleaseLyricSeekPreview(
                playbackPositionMs = 9_000L,
                pendingSeekPreviewPositionMs = 12_000L
            )
        )
        assertTrue(
            shouldReleaseLyricSeekPreview(
                playbackPositionMs = 11_780L,
                pendingSeekPreviewPositionMs = 12_000L
            )
        )
    }

    @Test
    fun `shouldAnimateAdvancedLyricsFromPlayback disables interpolation while dragging or settling preview`() {
        assertFalse(
            shouldAnimateAdvancedLyricsFromPlayback(
                isPlaying = true,
                isDraggingSlider = true,
                pendingSeekPreviewPositionMs = null
            )
        )
        assertFalse(
            shouldAnimateAdvancedLyricsFromPlayback(
                isPlaying = true,
                isDraggingSlider = false,
                pendingSeekPreviewPositionMs = 12_000L
            )
        )
        assertTrue(
            shouldAnimateAdvancedLyricsFromPlayback(
                isPlaying = true,
                isDraggingSlider = false,
                pendingSeekPreviewPositionMs = null
            )
        )
    }
}
