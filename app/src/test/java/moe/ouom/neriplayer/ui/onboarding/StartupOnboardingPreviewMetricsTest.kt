package moe.ouom.neriplayer.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupOnboardingPreviewMetricsTest {

    private val boundaryScales = listOf(0.1f, 0.5f, 0.74f, 0.7401f, 0.94f, 0.9401f, 1.16f, 1.1601f, 1.38f, 1.6f, 5f, Float.NaN)

    @Test
    fun `cover preview shows more lines only for small lyric scales`() {
        assertEquals(
            listOf(8, 8, 8, 6, 6, 3, 3, 3, 3, 3, 3, 3),
            boundaryScales.map(::resolveOnboardingCoverPreviewLineCount)
        )
    }

    @Test
    fun `playback preview lyric height steps down with scale and widens again for large scales`() {
        assertEquals(
            listOf(136f, 136f, 136f, 124f, 124f, 112f, 112f, 120f, 120f, 120f, 120f, 120f),
            boundaryScales.map { scale -> resolveOnboardingPlaybackPreviewLyricHeight(scale).value }
        )
    }

    @Test
    fun `playback preview height follows the lyric height`() {
        assertEquals(496f, resolveOnboardingPlaybackPreviewHeight(0.5f).value, 0f)
        assertEquals(472f, resolveOnboardingPlaybackPreviewHeight(1f).value, 0f)
    }
}
